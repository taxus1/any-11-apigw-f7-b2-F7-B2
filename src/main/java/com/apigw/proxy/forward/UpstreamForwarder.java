package com.apigw.proxy.forward;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.proxy.action.HeaderActionApplier;
import com.apigw.proxy.resilience.UpstreamOutcomes;
import com.apigw.proxy.userauth.OutboundAuth;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 把「已经匹配好路由」的请求发往上游。提供两种发送方式：
 *
 * <ol>
 *   <li>{@link #forward}：单发、请求体/响应体全程流式，不在网关缓冲。
 *       不重试的路由（POST 下单/支付等）走这条，行为与本功能上线前完全一致。</li>
 *   <li>{@link #attemptBuffered}：单发，但对「上游病了的 5xx 响应」把（有上限的）响应体读下来，
 *       交给上层决定要不要换发；正常 2xx/3xx/4xx 仍在 WebClient 回调内就地流式写回调用方，
 *       不缓冲。重试编排在 {@code ResilientForwarder} 里，本类只负责「好好打一发」。</li>
 * </ol>
 *
 * 请求方向做的事（顺序固定，重试时这些准备<b>只做一次</b>，见 {@link #prepareRequest}）：
 * 1. 复制调用方请求头，剔除「逐跳头」和与具体报文绑定的头；
 * 2. 清掉网关独占的身份/通行头（X-User-Id / X-Tenant-Id / X-Gateway-Pass），
 *    用户鉴权启用时连 Authorization 一起剥掉；
 * 3. 补 X-Forwarded-For / X-Forwarded-Proto / X-Forwarded-Host；
 * 4. 写入网关认定的身份头与通行标记；
 * 5. 按顺序号执行本路由的请求类动作（补头覆盖、删头删除）。
 * 因为准备只做一次，重试换发时这些补头/删头不会被重复执行，也不会丢。
 */
@Component
public class UpstreamForwarder {

    /** RFC 7230 逐跳头 + 与报文强绑定的头：这些绝不原样转发。 */
    private static final Set<String> HOP_BY_HOP = new LinkedHashSet<>(List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "trailer", "transfer-encoding", "upgrade", "http2-settings",
            // 内容长度/传输编码与具体报文绑死，交给客户端/框架按实际报文重算
            "content-length", "host"));

    private final WebClient webClient;

    public UpstreamForwarder(WebClient upstreamWebClient) {
        this.webClient = upstreamWebClient;
    }

    /** 重试路径里「打一发」的结果：要么已终局（响应已就地交回调用方），要么是可重试的上游失败。 */
    public sealed interface AttemptResult {

        /** 已终局：成功（2xx/3xx/4xx）已就地流式写回，或上游 5xx 已由上层用缓存体兜底写回。 */
        record Terminal() implements AttemptResult {
        }

        /**
         * 上游回了「病了」的状态码（5xx，非 501），响应体已按上限读出（可能被截断/超大），
         * 尚未写回调用方：上层可据此换发；不换发时用 {@link #buffered()} 原样兜底返回。
         */
        record FailureStatus(int statusCode, HttpHeaders headers,
                             BufferedUpstreamResponse buffered) implements AttemptResult {
        }

        /** 传输层根本没拿到响应（连不上/IO 故障/超时）：错误原样带上，由上层分类与决定换发。 */
        record TransportError(Throwable error) implements AttemptResult {
        }
    }

    /**
     * 发往上游（单发、双向流式），并在「仍持有上游连接」的 exchangeToMono 回调里把响应交给
     * handler 处理。不重试的路由走这条，请求体与响应体都不在网关全量缓冲。
     */
    public Mono<Void> forward(GatewayRoute route, ServerHttpRequest incoming,
                              String traceId, URI targetUri, OutboundAuth auth,
                              Function<UpstreamResponse, Mono<Void>> responseHandler) {
        ServerHttpRequest mutated = prepareRequest(route, incoming, traceId, auth);

        WebClient.RequestBodySpec spec = webClient
                .method(HttpMethod.valueOf(incoming.getMethod().name()))
                .uri(targetUri)
                .headers(h -> h.addAll(mutated.getHeaders()));

        return spec.body(BodyInserters.fromDataBuffers(incoming.getBody()))
                .exchangeToMono(response -> {
                    UpstreamResponse upstreamResponse = new UpstreamResponse(
                            response.statusCode().value(),
                            response.headers().asHttpHeaders(),
                            response.bodyToFlux(DataBuffer.class));
                    return responseHandler.apply(upstreamResponse);
                });
    }

    /**
     * 为可重试请求把请求体一次性读成字节数组（重试时重放同一字节，不二次订阅调用方连接流）。
     * 体超过 {@code capBytes} 时结果标记 {@link AggregatedRequestBody#retryable()}=false：
     * 调用方据此放弃重试资格、退回单发流式（安全优先，不缓存大请求体）。
     */
    public Mono<AggregatedRequestBody> aggregateRequestBody(ServerHttpRequest incoming, long capBytes) {
        // DataBufferUtils.join 的上限是 int；可重放上限现实里远小于 2GiB，超 int 直接按过大处理
        int joinCap = capBytes > Integer.MAX_VALUE - 1L ? Integer.MAX_VALUE : (int) (capBytes + 1);
        return DataBufferUtils.join(incoming.getBody(), joinCap)
                .<AggregatedRequestBody>handle((joined, sink) -> {
                    try {
                        int n = joined.readableByteCount();
                        if (n > capBytes) {
                            sink.next(AggregatedRequestBody.tooLarge());
                            return;
                        }
                        byte[] bytes = new byte[n];
                        joined.read(bytes);
                        sink.next(AggregatedRequestBody.of(bytes));
                    } finally {
                        DataBufferUtils.release(joined);
                    }
                })
                // 请求体为空（join 不发任何元素）：零字节、仍可重试
                .switchIfEmpty(Mono.just(AggregatedRequestBody.of(new byte[0])))
                .onErrorResume(DataBufferLimitException.class,
                        e -> Mono.just(AggregatedRequestBody.tooLarge()));
    }

    /** 请求体聚合结果：{@code retryable=false} 表示体太大、已放弃重试资格。 */
    public record AggregatedRequestBody(byte[] bytes, boolean retryable) {
        static AggregatedRequestBody of(byte[] bytes) {
            return new AggregatedRequestBody(bytes, true);
        }

        static AggregatedRequestBody tooLarge() {
            return new AggregatedRequestBody(new byte[0], false);
        }
    }

    /**
     * 用准备好的请求打一发（可重试路径）。请求头只准备一次（{@link #prepareRequest}），
     * 请求体字节每次重放同一份。
     *
     * <p>响应处理口径（在 WebClient 回调内完成，满足「必须在持有连接时消费 body」）：
     * <ul>
     *   <li>非失败状态（2xx/3xx/4xx）：<b>终局</b>，就地把活的响应体流交给 handler 写回，
     *       大响应也不缓冲；这种状态永远不需要重试。</li>
     *   <li>5xx（上游病了，非 501）：把响应体读到上限为止。结果交回上层：
     *       上层要换发就丢掉这份体；不换发（已到次数/总时限）就用缓存体把真实 5xx 兜底写回。
     *       体超过上限视为异常大的错误体，按「不再换发」处理（仍可原样返回已读部分由上层决定）。</li>
     * </ul>
     *
     * @param responseHandler 终局响应（成功流 / 最终 5xx 缓存）的写回动作，只在终局那发调用一次
     */
    public Mono<AttemptResult> attemptBuffered(GatewayRoute route,
                                               ServerHttpRequest preparedRequest,
                                               byte[] bodyBytes,
                                               URI targetUri,
                                               long responseCapBytes,
                                               Function<UpstreamResponse, Mono<Void>> responseHandler) {
        WebClient.RequestBodySpec spec = webClient
                .method(HttpMethod.valueOf(preparedRequest.getMethod().name()))
                .uri(targetUri)
                .headers(h -> h.addAll(preparedRequest.getHeaders()));

        return spec.body(BodyInserters.fromValue(bodyBytes))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (!UpstreamOutcomes.isUpstreamFailureStatus(status)) {
                        // 终局：正常结果就地流式写回（不在网关缓冲），成功/4xx 都不重试
                        UpstreamResponse live = new UpstreamResponse(
                                status, response.headers().asHttpHeaders(),
                                response.bodyToFlux(DataBuffer.class));
                        return responseHandler.apply(live).thenReturn((AttemptResult) new AttemptResult.Terminal());
                    }
                    // 5xx：读体（有上限），交上层决定换发还是兜底
                    HttpHeaders failureHeaders = response.headers().asHttpHeaders();
                    return readBounded(response.bodyToFlux(DataBuffer.class), responseCapBytes)
                            .map(buf -> (AttemptResult) new AttemptResult.FailureStatus(status,
                                    failureHeaders, buf));
                })
                .onErrorResume(err -> Mono.just(new AttemptResult.TransportError(err)));
    }

    /** 读响应体到上限；超过上限的字节被释放并标记 oversized（用于决定「不再换发」）。 */
    private Mono<BufferedUpstreamResponse> readBounded(Flux<DataBuffer> body, long capBytes) {
        return body.reduce(new BufferAccumulator(capBytes), BufferAccumulator::add)
                .map(BufferAccumulator::finish)
                .defaultIfEmpty(new BufferedUpstreamResponse(new byte[0], false));
    }

    /**
     * 拼上游目标地址：上游 base（scheme://host:port）+ 路径（含原始 query）。
     *
     * <p>路径用调用方显式给出的 {@code canonicalPath}（即 {@code PathNormalizer} 的产物，
     * 也是路由匹配实际依据的那个路径），而不是请求里的原始编码串。理由是路由边界安全：
     * 若上游收到的还是 {@code /order%2F..%2Fadmin} 这种写法，而网关按解码摊平后的路径
     * 定了路由，后端再自行解码一次就可能把同一请求解释到别的资源，网关与上游各看一条路径。
     * 统一转发规范路径后，「网关按哪条路径放行，上游就收到哪条路径」，中间没有第二种解释；
     * 查询串不属于路径，原样透传。
     */
    public static URI resolveTargetUri(String upstreamBase, ServerHttpRequest request, String canonicalPath) {
        URI base = URI.create(upstreamBase);
        StringBuilder sb = new StringBuilder();
        sb.append(base.getScheme()).append("://").append(base.getRawAuthority());
        String basePath = base.getRawPath();
        if (basePath != null && !basePath.isBlank() && !"/".equals(basePath)) {
            sb.append(basePath);
        }
        sb.append(canonicalPath);
        if (request.getURI().getRawQuery() != null) {
            sb.append('?').append(request.getURI().getRawQuery());
        }
        return URI.create(sb.toString());
    }

    /**
     * 构造发往上游的请求（只改头）：头清洗 → 独占头清零 → X-Forwarded-* → 身份/通行头 → 请求动作。
     * <b>重试链路只在首发前调用一次</b>：重试换发复用同一个准备结果，补头/删头天然不丢、不重复。
     */
    public ServerHttpRequest prepareRequest(GatewayRoute route, ServerHttpRequest incoming,
                                            String traceId, OutboundAuth auth) {
        return incoming.mutate().headers(headers -> {
            // 1. 剔除逐跳头与报文绑定头（头名大小写不敏感，统一小写比对）
            HOP_BY_HOP.forEach(headers::remove);
            String te = headers.getFirst("te");
            headers.remove("te");
            if (te != null && te.toLowerCase(Locale.ROOT).contains("trailers")) {
                headers.set("te", HttpHeaderValues.TRAILERS.toString());
            }

            // 2. 网关独占头清零：身份头/通行标记只由网关写，调用方塞的同名头先全部清掉
            headers.remove(GatewayHeaders.USER_ID_HEADER);
            headers.remove(GatewayHeaders.TENANT_ID_HEADER);
            headers.remove(GatewayHeaders.GATEWAY_PASS_HEADER);
            if (auth.stripAuthorization()) {
                headers.remove(GatewayHeaders.AUTHORIZATION_HEADER);
            }

            // 3. 透传原始链路信息
            String remote = incoming.getRemoteAddress() == null
                    ? null : incoming.getRemoteAddress().getAddress().getHostAddress();
            if (remote != null) {
                String prev = headers.getFirst("X-Forwarded-For");
                headers.set("X-Forwarded-For",
                        prev == null || prev.isBlank() ? remote : prev + ", " + remote);
            }
            headers.set("X-Forwarded-Proto", incoming.getURI().getScheme() == null
                    ? "http" : incoming.getURI().getScheme());
            String originalHost = incoming.getHeaders().getFirst("Host");
            if (originalHost != null) {
                headers.set("X-Forwarded-Host", originalHost);
            }
            headers.set("X-Gateway-Trace-Id", traceId);

            // 4. 网关认定的身份与通行标记
            if (auth.identity() != null) {
                headers.set(GatewayHeaders.USER_ID_HEADER, auth.identity().userId());
                headers.set(GatewayHeaders.TENANT_ID_HEADER, auth.identity().tenantId());
            }
            if (auth.gatewayPass() != null) {
                headers.set(GatewayHeaders.GATEWAY_PASS_HEADER, auth.gatewayPass());
            }

            // 5. 请求类动作按顺序号执行（整条重试链路只执行这一次）
            HeaderActionApplier.applyRequestActions(route, headers);
        }).build();
    }

    /** 有上限地累积响应体字节，超限后丢弃后续缓冲并打标记。 */
    private static final class BufferAccumulator {
        private final long cap;
        private byte[] data;
        private int len;
        private boolean oversized;

        BufferAccumulator(long cap) {
            this.cap = cap;
            this.data = new byte[Math.min((int) Math.max(cap, 1024), 8192)];
        }

        BufferAccumulator add(DataBuffer buf) {
            try {
                if (oversized) {
                    return this;
                }
                int available = buf.readableByteCount();
                if ((long) len + available > cap) {
                    oversized = true;
                    int room = (int) Math.max(0, cap - len);
                    if (room > 0) {
                        ensure(room);
                        buf.read(data, len, room);
                        len += room;
                    }
                    return this;
                }
                ensure(available);
                buf.read(data, len, available);
                len += available;
                return this;
            } finally {
                DataBufferUtils.release(buf);
            }
        }

        private void ensure(int more) {
            if (len + more <= data.length) {
                return;
            }
            int grown = Math.max(data.length * 2, len + more);
            byte[] copy = new byte[grown];
            System.arraycopy(data, 0, copy, 0, len);
            data = copy;
        }

        BufferedUpstreamResponse finish() {
            byte[] exact = len == data.length ? data : java.util.Arrays.copyOf(data, len);
            return new BufferedUpstreamResponse(exact, oversized);
        }
    }
}
