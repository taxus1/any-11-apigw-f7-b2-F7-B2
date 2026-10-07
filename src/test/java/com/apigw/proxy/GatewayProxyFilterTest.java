package com.apigw.proxy;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.proxy.accesslog.AccessLogRecorder;
import com.apigw.proxy.config.GatewayProxyProperties;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.apigw.proxy.route.RoutesChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转发链路端到端测试（真实 Netty 服务端 + 真实 WebClient 上游 + JDK HttpServer 上游，无 Redis）。
 *
 * 覆盖题目硬性要求：
 * - 按条件匹配路由，把请求（方法/路径/查询串/请求体）真正送到上游并把响应带回来；
 * - 请求补头覆盖调用方同名头、删头上游收不到；响应补头/删头作用到回给调用方的响应；
 * - 路径前缀边界（/order/ 命中 /order/abc）；多路由稳定定序；
 * - 无路由回 404 且带 X-Gateway-Error: NO_ROUTE，前端一眼认出是网关没找到路；
 * - 上游连不上 502 UPSTREAM_UNAVAILABLE、上游超时 504 UPSTREAM_TIMEOUT，与 404 区分开；
 * - 上游 content-length 等报文绑定头不原样照抄，响应长度仍与实际内容对得上；
 * - 新建路由经变更事件刷新后立刻走通，不重启；
 * - traceId 通过 X-Gateway-Trace-Id 回给调用方，与访问日志两段串联。
 */
class GatewayProxyFilterTest {

    private FakeUpstream upstream;
    private InMemoryRouteStore store;
    private RouteCatalog catalog;

    /** 落库出口在测试里用同步收集器替身：不引 JDBC，直接抓住每笔完整流水做断言。 */
    private final java.util.List<com.apigw.domain.accesslog.AccessLogEntry> recordedEntries =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private DisposableServer server;
    private String baseUrl;
    private WebClient client;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new FakeUpstream();
        store = new InMemoryRouteStore();
        var props = new GatewayProxyProperties(
                Duration.ofMillis(500), Duration.ofMillis(800), Duration.ofHours(1));
        catalog = new RouteCatalog(store, props);

        var nettyClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofMillis(800))
                .doOnConnected(c -> c.addHandlerLast(
                        new io.netty.handler.timeout.ReadTimeoutHandler(
                                800, java.util.concurrent.TimeUnit.MILLISECONDS)));
        WebClient webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(nettyClient))
                .build();

        UpstreamForwarder upstreamForwarder = new UpstreamForwarder(webClient);
        var filter = new GatewayProxyWebFilter(
                catalog, new RouteMatcher(), upstreamForwarder,
                new com.apigw.proxy.resilience.ResilientForwarder(
                        upstreamForwarder,
                        new com.apigw.proxy.resilience.CircuitBreakerRegistry(),
                        new com.apigw.proxy.config.ResilienceProperties(
                                1024 * 1024L, 2 * 1024 * 1024L, 5)),
                new AccessLogRecorder(), e -> recordedEntries.add(e), new ObjectMapper(),
                // 本测试不涉用户令牌：装一个「未启用」的守门人，验证老链路行为零变化
                new com.apigw.proxy.userauth.UserAuthGatekeeper(null, null),
                new com.apigw.proxy.gray.GrayReleaseSelector(
                        new com.apigw.proxy.gray.GrayProperties(null)));

        // 链尾 WebHandler：到这里的只有被判定为非转发流量（/api），回一个占位 200
        WebHandler tail = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        WebHandler filtering = new FilteringWebHandler(tail, List.of(filter));
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(filtering);
        adapter.afterPropertiesSet();
        HttpHandler httpHandler = adapter;

        server = HttpServer.create()
                .handle(new ReactorHttpHandlerAdapter(httpHandler))
                .bindNow();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = WebClient.builder().build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
        upstream.close();
    }

    // ---- 造路由的小工具 ----

    private GatewayRule cond(String type, String name, String value, int sort) {
        return GatewayRule.create("REQUEST", type, name, value, sort);
    }

    private GatewayRule act(String type, String name, String value, int sort) {
        return GatewayRule.create(type.startsWith("RESP_") ? "RESPONSE" : "REQUEST",
                type, name, value, sort);
    }

    private GatewayRoute route(String no, String upstreamBase,
                               List<GatewayRule> conditions, List<GatewayRule> actions) {
        GatewayRoute r = GatewayRoute.create(no, no, upstreamBase, 1, null);
        r.replaceRules(conditions, actions);
        return r;
    }

    private void loadRoutes(GatewayRoute... routes) {
        store.setRoutes(List.of(routes));
        catalog.refresh().block();
    }

    /** 构造保留百分号编码的请求 URI（WebClient 对裸字符串会再编码，必须给 URI 对象）。 */
    private java.net.URI rawUri(String rawPathAndQuery) {
        return java.net.URI.create(baseUrl).resolve(rawPathAndQuery);
    }

    // ---- 用例 ----

    @Test
    void forwardsMethodPathQueryAndBody_toMatchedUpstream() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)),
                List.of()));

        String resp = client.post()
                .uri(baseUrl + "/order/abc?from=cart")
                .header("Content-Type", "text/plain")
                .bodyValue("hello-body")
                .retrieve().bodyToMono(String.class).block();

        assertThat(resp).contains("\"method\":\"POST\"")
                .contains("\"path\":\"/order/abc\"")
                .contains("\"query\":\"from=cart\"");
        HttpExchange got = upstream.lastExchange();
        assertThat(got.getRequestMethod().toString()).isEqualTo("POST");
        assertThat(got.getRequestURI().getPath()).isEqualTo("/order/abc");
    }

    @Test
    void requestAddHeader_overwritesCallerHeader_andRemoveHeaderStripsIt() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)),
                List.of(
                        act("REQ_ADD_HEADER", "X-Gw", "from-gw", 1),
                        act("REQ_REMOVE_HEADER", "X-Internal", null, 2))));

        client.get().uri(baseUrl + "/order/1")
                .header("X-Gw", "caller-wants-this")   // 应被覆盖
                .header("X-Internal", "secret")        // 应被删除
                .retrieve().bodyToMono(String.class).block();

        HttpExchange got = upstream.lastExchange();
        assertThat(got.getRequestHeaders().getFirst("X-Gw")).isEqualTo("from-gw");
        assertThat(got.getRequestHeaders().get("X-Internal"))
                .as("删头后上游绝不能收到 X-Internal").isNullOrEmpty();
        // Host 必须是上游的，而不是网关收到的调用方 Host
        assertThat(got.getRequestHeaders().getFirst("Host")).contains("127.0.0.1:" + upstream.port());
        assertThat(got.getRequestHeaders().getFirst("X-Gateway-Trace-Id")).isNotBlank();
    }

    @Test
    void responseAddAndRemoveHeaders_landOnCallerResponse_notOnRequest() {
        upstream.setCustomBody("{\"ok\":true}");
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)),
                List.of(
                        act("RESP_ADD_HEADER", "X-Trace", "t-1", 1),
                        act("RESP_REMOVE_HEADER", "Content-Type", null, 2))));

        var resp = client.get().uri(baseUrl + "/order/1").exchange().block();

        assertThat(resp.headers().asHttpHeaders().getFirst("X-Trace")).isEqualTo("t-1");
        // 上游本来回了 Content-Type，响应动作要求删掉，调用方就拿不到
        assertThat(resp.headers().asHttpHeaders().get("Content-Type")).isNullOrEmpty();
        // 响应动作不串到请求：上游收到的请求里不该出现 X-Trace
        assertThat(upstream.lastExchange().getRequestHeaders().getFirst("X-Trace")).isNull();
        resp.releaseBody().block();
    }

    @Test
    void pathBoundary_trailingSlashPrefixHits_andLookalikeMisses() {
        loadRoutes(
                route("order", upstream.baseUrl(),
                        List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        // /order/abc 命中
        client.get().uri(baseUrl + "/order/abc").retrieve().bodyToMono(String.class).block();
        assertThat(upstream.lastExchange().getRequestURI().getPath()).isEqualTo("/order/abc");

        // /order 自身对 /order/ 前缀不命中 → 网关 404 NO_ROUTE，不是上游 404
        var missExact = client.get().uri(baseUrl + "/order").exchange().block();
        assertThat(missExact.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missExact.headers().asHttpHeaders().getFirst("X-Gateway-Error")).isEqualTo("NO_ROUTE");
        missExact.releaseBody().block();

        // /ordering 这种「只是字符串前缀像」的绝不命中
        var lookalike = client.get().uri(baseUrl + "/ordering").exchange().block();
        assertThat(lookalike.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(lookalike.headers().asHttpHeaders().getFirst("X-Gateway-Error")).isEqualTo("NO_ROUTE");
        lookalike.releaseBody().block();
    }

    @Test
    void encodedSlash_hitsSameRouteAsPlainWriting_andUpstreamReceivesRawPath() {
        // 事故 1：/order%2Fabc 真实打进来曾回 404，但排查说命中。两边口径统一后必须命中，
        // 且发给上游的仍是原始（编码）路径——网关只在判定时归一，不改写资源路径
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        String body = client.get().uri(rawUri("/order%2Fabc")).retrieve().bodyToMono(String.class).block();
        assertThat(body).contains("\"path\":\"/order/abc\"");
        // JDK HttpExchange 的 getPath() 会解码，但请求行里的原始（编码）字节在 getRawPath()
        assertThat(upstream.lastExchange().getRequestURI().getRawPath()).isEqualTo("/order%2Fabc");

        // 大写 %2f 等价；带查询串时查询串不参与路径判定
        String body2 = client.get().uri(rawUri("/order%2fabc?from=cart"))
                .retrieve().bodyToMono(String.class).block();
        assertThat(body2).contains("\"query\":\"from=cart\"");
        assertThat(upstream.lastExchange().getRequestURI().getRawPath()).isEqualTo("/order%2fabc");
    }

    @Test
    void encodedTraversal_cannotEscapePrefixBoundary_onRealWire() {
        // 安全红线：/order%2F..%2Fadmin 归一后是 /admin，绝不被 /order/ 子树收下
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var escaped = client.get().uri(rawUri("/order%2F..%2Fadmin")).exchange().block();
        assertThat(escaped.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(escaped.headers().asHttpHeaders().getFirst("X-Gateway-Error")).isEqualTo("NO_ROUTE");
        escaped.releaseBody().block();

        // 配了 /admin 前缀时，穿越写法落到它该去的 admin 路由，而不是 order
        loadRoutes(
                route("order", upstream.baseUrl(),
                        List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()),
                route("admin", upstream.baseUrl(),
                        List.of(cond("PATH_PREFIX", null, "/admin", 1)), List.of()));
        String body = client.get().uri(rawUri("/order%2F..%2Fadmin")).retrieve().bodyToMono(String.class).block();
        assertThat(body).contains("\"path\":\"/order/../admin\"");
        assertThat(upstream.lastExchange().getRequestURI().getRawPath())
                .isEqualTo("/order%2F..%2Fadmin");
    }

    @Test
    void duplicateAndTrailingSlashes_followSameCanonicalVerdict() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        // 重复斜杠合并：/order//abc 命中 /order/ 子树，上游收到原始路径
        String dup = client.get().uri(rawUri("/order//abc")).retrieve().bodyToMono(String.class).block();
        assertThat(dup).contains("\"path\":\"/order//abc\"");

        // 结尾斜杠：/order/ 命中（子树根），/order 不命中（不是它的「下面」）
        assertThat(client.get().uri(rawUri("/order/")).retrieve().bodyToMono(String.class).block()).isNotBlank();
        var exact = client.get().uri(rawUri("/order")).exchange().block();
        assertThat(exact.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        exact.releaseBody().block();
    }

    @Test
    void multipleMatches_areOrderedStably() {
        FakeUpstream upstream2;
        try {
            upstream2 = new FakeUpstream();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            loadRoutes(
                    route("broad", upstream.baseUrl(),
                            List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()),
                    route("specific", upstream2.baseUrl(),
                            List.of(cond("PATH_PREFIX", null, "/order/abc/", 1)), List.of()));

            // 更长前缀稳定走 specific 上游，连打多次结果一致（不能时有时无）
            for (int i = 0; i < 3; i++) {
                client.get().uri(baseUrl + "/order/abc/x").retrieve().bodyToMono(String.class).block();
                assertThat(upstream2.lastExchange()).isNotNull();
                assertThat(upstream2.lastExchange().getRequestURI().getPath()).isEqualTo("/order/abc/x");
            }
        } finally {
            upstream2.close();
        }
    }

    @Test
    void noRoute_returns404MarkedAsGatewayNoRoute_notBackendError() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/nope").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Error")).isEqualTo("NO_ROUTE");
        String body = resp.bodyToMono(String.class).block();
        assertThat(body).contains("NO_ROUTE").contains("traceId");
    }

    @Test
    void upstreamConnectRefused_is502DistinctFrom404() {
        loadRoutes(route("dead", "http://127.0.0.1:1",
                List.of(cond("PATH_PREFIX", null, "/dead/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/dead/x").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"))
                .isEqualTo("UPSTREAM_UNAVAILABLE");
        String body = resp.bodyToMono(String.class).block();
        // 只能看到网关的固定文案，不能是内部堆栈
        assertThat(body).contains("UPSTREAM_UNAVAILABLE").doesNotContain("Exception");
    }

    @Test
    void upstreamHang_is504DistinctFrom502and404() {
        upstream.setHangForever(true);
        loadRoutes(route("slow", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/slow/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/slow/x").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"))
                .isEqualTo("UPSTREAM_TIMEOUT");
        resp.releaseBody().block();
    }

    @Test
    void upstreamContentLength_isNotBlindlyCopied_butBodyIsIntact() {
        // 上游故意回一个与 content-length 不一致的内容，验证网关不照抄该头也不截断内容
        String payload = "{\"x\":\"this body is intentionally longer than declared length\"}";
        upstream.setCustomBody(payload);
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/order/1").exchange().block();
        String body = resp.bodyToMono(String.class).block();
        assertThat(body).isEqualTo(payload);
        // 连接能干净读完、没有因为报文绑定头错乱导致截断/挂起，即说明长度由网关/框架按实际报文处理
        assertThat(body.length()).isEqualTo(payload.length());
    }

    @Test
    void upstreamStatus_isPassedThrough_including4xx5xx() {
        upstream.setResponseStatus(418);
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/order/1").exchange().block();
        assertThat(resp.statusCode().value()).isEqualTo(418);
        resp.releaseBody().block();
    }

    @Test
    void newlyCreatedRoute_takesEffectImmediatelyViaChangeEvent() {
        // 初始只有一条路由
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));
        var before = client.get().uri(baseUrl + "/fresh/1").exchange().block();
        assertThat(before.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        before.releaseBody().block();

        // 「新建」另一条前缀的路由并发事件（模拟管理接口写完 Redis），不重启、不等轮询
        GatewayRoute fresh = route("fresh", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/fresh/", 1)), List.of());
        store.setRoutes(List.of(
                route("order", upstream.baseUrl(),
                        List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()),
                fresh));
        catalog.onRoutesChanged(RoutesChangedEvent.created("fresh"));

        var after = client.get().uri(baseUrl + "/fresh/1").exchange().block();
        assertThat(after.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.bodyToMono(String.class).block()).contains("\"path\":\"/fresh/1\"");
    }

    @Test
    void managementApi_isPassedThrough_notProxied() {
        var resp = client.get().uri(baseUrl + "/api/gateway/routes").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();
    }

    @Test
    void traceId_isReturnedOnBothSuccessAndError() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var ok = client.get().uri(baseUrl + "/order/1").exchange().block();
        String traceOk = ok.headers().asHttpHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(traceOk).isNotBlank();
        ok.releaseBody().block();

        var notFound = client.get().uri(baseUrl + "/missing").exchange().block();
        String traceErr = notFound.headers().asHttpHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(traceErr).isNotBlank();
        // 不同请求的 traceId 不能串
        assertThat(traceOk).isNotEqualTo(traceErr);
        notFound.releaseBody().block();
    }

    @Test
    void callerTraceId_isHonored_andBecomesRequestIdOfAccessRow() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/order/9")
                .header("X-Trace-Id", "caller-trace-001")
                .header("X-App-No", "app-billing")
                .exchange().block();
        // 沿用调用方的号，响应头也能对上，跨服务可串联
        assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Trace-Id"))
                .isEqualTo("caller-trace-001");
        resp.releaseBody().block();

        com.apigw.domain.accesslog.AccessLogEntry row = awaitRow("caller-trace-001");
        assertThat(row.statusCode()).isEqualTo(200);
        assertThat(row.routeNo()).isEqualTo("order");
        assertThat(row.appNo()).isEqualTo("app-billing");
        assertThat(row.method()).isEqualTo("GET");
        assertThat(row.path()).isEqualTo("/order/9");
        assertThat(row.elapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(row.clientIp()).isNotBlank();
    }

    @Test
    void illegalCallerTraceId_isIgnored_gatewayGeneratesOne() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        // "bad" 是 HTTP 合法头值、但不满足追踪号白名单（长度 8..64）：网关必须不采信、自己生成。
        // 含 CR/LF 的伪造值在 Netty HTTP 解码层就会被拒（到不了过滤器），白名单本身的
        // 注入字符拦截由 GatewayHeadersTest 覆盖
        var resp = client.get().uri(baseUrl + "/order/9")
                .header("X-Trace-Id", "bad")
                .exchange().block();
        String traceId = resp.headers().asHttpHeaders().getFirst("X-Gateway-Trace-Id");
        resp.releaseBody().block();

        assertThat(traceId).isNotBlank().isNotEqualTo("bad");
        assertThat(traceId).matches("[A-Za-z0-9._-]{8,64}");
        assertThat(awaitRow(traceId)).isNotNull();
    }

    @Test
    void failedRequests_alsoLeaveOneRowWithFinalGatewayStatus_andNeverMixRows() {
        loadRoutes(route("dead", "http://127.0.0.1:1",
                List.of(cond("PATH_PREFIX", null, "/dead/", 1)), List.of()));

        // 上游连不上：拿不到上游状态码，但流水照样留痕，状态码=回给调用方的 502
        var resp = client.get().uri(baseUrl + "/dead/x").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        resp.releaseBody().block();

        // 没匹配上路由也留一行：routeNo=null、状态码=404
        var resp404 = client.get().uri(baseUrl + "/nowhere").exchange().block();
        assertThat(resp404.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        resp404.releaseBody().block();

        // 并发乱序打两种请求，每笔一行：路径、状态码、路由必须各归各，不能串
        var threads = new java.util.ArrayList<Thread>();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 20; i++) {
            String path = rnd.nextBoolean() ? "/dead/" + i : "/nowhere/" + i;
            Thread t = new Thread(() -> {
                var r = client.get().uri(baseUrl + path).exchange().block();
                r.releaseBody().block();
            });
            threads.add(t);
        }
        threads.forEach(Thread::start);
        threads.forEach(t -> {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        org.awaitility.Awaitility.await().untilAsserted(() ->
                assertThat(recordedEntries).hasSizeGreaterThanOrEqualTo(22));
        for (com.apigw.domain.accesslog.AccessLogEntry e : recordedEntries) {
            if (e.path().startsWith("/dead")) {
                assertThat(e.statusCode()).isEqualTo(502);
                assertThat(e.routeNo()).isEqualTo("dead");
            } else if (e.path().startsWith("/nowhere")) {
                assertThat(e.statusCode()).isEqualTo(404);
                assertThat(e.routeNo()).isNull();
            }
        }
    }

    private com.apigw.domain.accesslog.AccessLogEntry awaitRow(String requestId) {
        org.awaitility.Awaitility.await().untilAsserted(() ->
                assertThat(recordedEntries).anyMatch(e -> e.requestId().equals(requestId)));
        return recordedEntries.stream()
                .filter(e -> e.requestId().equals(requestId))
                .findFirst().orElseThrow();
    }

    // ---- 灰度发布（标记优先 + 按权重分流） ----

    /** 起一个返回自定义标识的上游，响应体里写明自己是谁，调用方据此断言落到了哪一组。 */
    private FakeUpstream labeledUpstream(String label) throws Exception {
        FakeUpstream u = new FakeUpstream();
        u.setCustomBody("{\"version\":\"" + label + "\"}");
        return u;
    }

    private GatewayRoute grayRoute(String no, String mainUpstream,
                                   com.apigw.domain.route.GrayGroup... groups) {
        GatewayRoute r = GatewayRoute.create(no, no, mainUpstream, 1, null);
        r.replaceRules(List.of(cond("PATH_PREFIX", null, "/g/", 1)), List.of());
        r.replaceGrayGroups(List.of(groups));
        return r;
    }

    private com.apigw.domain.route.GrayGroup group(String name, String baseUrl, int weight,
                                                   String... tags) {
        return com.apigw.domain.route.GrayGroup.create(name, baseUrl, weight,
                tags.length == 0 ? List.of() : List.of(tags));
    }

    private String getGray(String tagHeader) {
        var spec = client.get().uri(baseUrl + "/g/1");
        if (tagHeader != null) {
            spec.header("X-Gray-Tag", tagHeader);
        }
        return spec.retrieve().bodyToMono(String.class).block();
    }

    @Test
    void gray_tagPinsToCanary_evenWithZeroWeight_wrongTagFallsToOld() throws Exception {
        try (FakeUpstream oldUp = labeledUpstream("old");
             FakeUpstream newUp = labeledUpstream("new")) {
            // 新版一成量都没给（0），但带对标记的请求必须稳稳到新版；其余 100% 老版
            loadRoutes(grayRoute("g", oldUp.baseUrl(),
                    group("stable", oldUp.baseUrl(), 100),
                    group("canary", newUp.baseUrl(), 0, "v2")));

            assertThat(getGray("v2")).contains("\"version\":\"new\"");
            // 大小写不一致 / 看着像对不上 → 一律当没带，落老版。
            // 注：以空格开头/结尾的头值 HTTP 客户端在编解码层就发不出去（Netty 直接拒），
            // 「多空格也当没带」这条由 GrayReleaseSelectorTest 用 mock 请求覆盖。
            assertThat(getGray("V2")).contains("\"version\":\"old\"");
            assertThat(getGray("v3")).contains("\"version\":\"old\"");
            assertThat(getGray(null)).contains("\"version\":\"old\"");
        }
    }

    @Test
    void gray_weightSplit10_90_hitsBothUpstreamsInProportion() throws Exception {
        try (FakeUpstream oldUp = labeledUpstream("old");
             FakeUpstream newUp = labeledUpstream("new")) {
            loadRoutes(grayRoute("g", oldUp.baseUrl(),
                    group("stable", oldUp.baseUrl(), 90),
                    group("canary", newUp.baseUrl(), 10)));

            int toNew = 0;
            for (int i = 0; i < 100; i++) {
                if (getGray(null).contains("\"version\":\"new\"")) {
                    toNew++;
                }
            }
            assertThat(toNew).isEqualTo(10);
            // 新版不是 0 个请求（「配了权重却分不到」不允许）
            assertThat(newUp.lastExchange()).isNotNull();
            assertThat(oldUp.lastExchange()).isNotNull();
        }
    }

    @Test
    void gray_extreme100to0_isStable_andTagStillReachesPaused() throws Exception {
        try (FakeUpstream oldUp = labeledUpstream("old");
             FakeUpstream newUp = labeledUpstream("new")) {
            loadRoutes(grayRoute("g", oldUp.baseUrl(),
                    group("stable", oldUp.baseUrl(), 100),
                    group("paused", newUp.baseUrl(), 0, "internal")));

            for (int i = 0; i < 20; i++) {
                assertThat(getGray(null)).contains("\"version\":\"old\"");
            }
            assertThat(newUp.lastExchange()).as("0 权重组无标记时一个请求都收不到").isNull();
            // 配置留着：标记直达，一键可开
            assertThat(getGray("internal")).contains("\"version\":\"new\"");
        }
    }

    @Test
    void gray_weightChange_takesEffectImmediatelyViaChangeEvent() throws Exception {
        try (FakeUpstream oldUp = labeledUpstream("old");
             FakeUpstream newUp = labeledUpstream("new")) {
            // 初始：老版全量
            GatewayRoute v0 = grayRoute("g", oldUp.baseUrl(),
                    group("stable", oldUp.baseUrl(), 100),
                    group("canary", newUp.baseUrl(), 0, "v2"));
            loadRoutes(v0);
            assertThat(getGray(null)).contains("\"version\":\"old\"");

            // 改配置（版本 +1）：对半分，事件即时生效，不重启、不等轮询
            v0.setVersion(1);
            v0.replaceGrayGroups(List.of(
                    group("stable", oldUp.baseUrl(), 50),
                    group("canary", newUp.baseUrl(), 50, "v2")));
            store.setRoutes(List.of(v0));
            catalog.onRoutesChanged(RoutesChangedEvent.updated("g"));

            int toNew = 0;
            for (int i = 0; i < 100; i++) {
                if (getGray(null).contains("\"version\":\"new\"")) {
                    toNew++;
                }
            }
            assertThat(toNew).isEqualTo(50);
        }
    }
}
