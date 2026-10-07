package com.apigw.proxy.ratelimit;

import com.apigw.common.web.ClientIpResolver;
import com.apigw.common.web.GatewayHeaders;
import com.apigw.proxy.auth.AppAuthWebFilter;
import com.apigw.proxy.error.GatewayErrors;
import com.apigw.proxy.error.UpstreamFailureKind;
import com.apigw.proxy.match.PathNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 限流过滤器。仅在 {@code apigw.rate-limit.enabled=true} 时装配。
 *
 * 位置：在接入鉴权 {@link AppAuthWebFilter}（HIGHEST_PRECEDENCE+5）<b>之后</b>、
 * 转发过滤器（HIGHEST_PRECEDENCE+10）<b>之前</b>。所以到达这里的请求：
 * - 已通过应用凭据校验，入站 {@code X-App-No} 已被改写成网关认定的可信编号，
 *   限流按「认证后的真实应用」计数，调用方伪造别的编号过不了鉴权这一关；
 * - 一旦本过滤器判超，链路在此短路返回，<b>根本不会匹配路由、不会打上游</b>。
 *
 * 两层额度（应用总量 / 应用下来源地址）在 Redis 上由一段 Lua 原子判完：
 * - 超应用总量 → 429 {@code RATE_LIMITED_APP}；
 * - 超来源额度 → 429 {@code RATE_LIMITED_IP}（只卡该来源，其他来源不受影响）；
 *   两层同时满时先报应用总量（脚本先查应用层）。
 * 两类 429 都带 {@code Retry-After}（delta-seconds）与 JSON 体，口径一致：
 * 「当前这个固定窗口结束、下一窗从零重新计数」还要等的整秒数，等到点必然可重试。
 *
 * 计数存储故障的处理见 {@link RateLimiter}：短超时 + 熔断，默认 fail-open（放行并告警），
 * 配成 fail-closed 时这里回 503 {@code RATE_LIMIT_STORE_UNAVAILABLE}。
 */
@Slf4j
public class RateLimitWebFilter implements WebFilter, Ordered {

    /** 比接入鉴权晚（先认人，拿到可信应用编号），比转发早（超限不打上游）。 */
    public static final int ORDER = AppAuthWebFilter.ORDER + 2;

    /** RFC 9110 的重试等待头：429 时给 delta-seconds。 */
    public static final String RETRY_AFTER_HEADER = "Retry-After";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitWebFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        // 与接入鉴权、转发过滤器同一个路径口径：编码/重复斜杠不能把管理面请求伪装进来绕过限流
        if (isPassthrough(PathNormalizer.normalize(path))) {
            return chain.filter(exchange);
        }

        // 到达这里的应用编号已被接入鉴权过滤器改写为可信值；限流关闭接入鉴权独立部署时
        // 理论上不会到这（见装配说明），取不到编号就不限（不拦自己认不出的流量）。
        String appNo = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.APP_NO_HEADER);
        if (appNo == null || appNo.isBlank()) {
            return chain.filter(exchange);
        }
        String canonicalIp = ClientIpResolver.canonicalize(
                ClientIpResolver.resolve(exchange.getRequest()));

        String traceId = resolveTraceId(exchange);
        return rateLimiter.check(appNo, canonicalIp)
                .flatMap(gate -> {
                    if (gate.allowed()) {
                        return chain.filter(exchange);
                    }
                    if (gate.storeUnavailable()) {
                        // fail-closed 口径：计数读不出来时不裸放行
                        log.warn("限流计数不可用且配置为 fail-closed，拒绝本次请求 traceId={}", traceId);
                        return GatewayErrors.write(exchange, objectMapper,
                                UpstreamFailureKind.RATE_LIMIT_STORE_UNAVAILABLE, traceId, null);
                    }
                    UpstreamFailureKind kind = "IP".equals(gate.verdict().blockedScope())
                            ? UpstreamFailureKind.RATE_LIMITED_IP
                            : UpstreamFailureKind.RATE_LIMITED_APP;
                    log.debug("限流拒绝 scope={} app={} ip={} retryAfter={}s traceId={}",
                            gate.verdict().blockedScope(), appNo, canonicalIp,
                            gate.verdict().retryAfterSec(), traceId);
                    // 被挡在这里：不匹配路由、不打上游。等待时长口径见类注释/lua 脚本
                    return GatewayErrors.write(exchange, objectMapper, kind, traceId, null,
                            Map.of(RETRY_AFTER_HEADER, String.valueOf(gate.verdict().retryAfterSec())));
                });
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        String incoming = GatewayHeaders.normalizeTraceId(
                exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACE_ID_HEADER));
        return incoming != null ? incoming : GatewayHeaders.newRequestId();
    }

    private boolean isPassthrough(String path) {
        return path.equals("/api") || path.startsWith("/api/")
                || path.equals("/actuator") || path.startsWith("/actuator/");
    }
}
