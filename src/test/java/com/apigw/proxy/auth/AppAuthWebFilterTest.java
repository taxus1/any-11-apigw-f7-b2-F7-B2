package com.apigw.proxy.auth;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.app.ClientApp;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接入鉴权过滤器端到端测试（真实 Netty + 真实 HTTP 客户端，无库无 Redis）。
 *
 * 覆盖硬性要求：
 * - 缺凭据/编号不存在/密钥错/过期 → 401 APP_UNAUTHENTICATED；
 * - 停用、来源不在名单 → 403 APP_FORBIDDEN；
 * - 凭据正确才放行，且 X-App-No 被改写成网关认定的规范值；
 * - 来源按 ClientIpResolver 口径（XFF 最左合法 → X-Real-IP → 对端），多机轮询/隔代理认最初来源；
 * - 严格精确匹配：名单 192.168.1.1 绝不放进 192.168.1.10；
 * - 名单为空 = 任意来源放行；
 * - 停用/改名单经事件刷新快照后，**下一笔新请求立即**按新状态判定。
 */
class AppAuthWebFilterTest {

    private FakeRepository repository;
    private AppCredentialCatalog catalog;
    private DisposableServer server;
    private String baseUrl;
    private WebClient client;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);
    private final Map<String, String> secrets = new HashMap<>();

    /** 一次请求抓回来的结果。 */
    record Result(int status, String errorHeader, String body) {
    }

    private static final String SECRET = "0123456789abcdef0123456789abcdef01234567";

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        catalog = new AppCredentialCatalog(repository, clock);
        catalog.refreshBlock(Duration.ofSeconds(5));

        AppAuthWebFilter filter = new AppAuthWebFilter(catalog, new ObjectMapper());
        WebHandler tail = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            // 把网关认定的应用编号回显，验证入站头已被可信值覆盖
            String authedApp = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.APP_NO_HEADER);
            byte[] body = ("OK app=" + authedApp).getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        };
        WebHandler filtering = new FilteringWebHandler(tail, List.of(filter));
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(filtering);
        adapter.afterPropertiesSet();

        server = HttpServer.create().handle(new ReactorHttpHandlerAdapter((HttpHandler) adapter)).bindNow();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = WebClient.builder().build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
    }

    private void createApp(String no, String secret, Instant expiresAt, int enabled) {
        ClientApp app = ClientApp.create(no, no, secret, expiresAt, enabled, null, null, null, clock);
        repository.insert(app);
        secrets.put(no, secret);
        catalog.refreshBlock(Duration.ofSeconds(5));
    }

    private void addOrigin(String no, String ip) {
        repository.addOrigin(no, com.apigw.common.web.ClientIpResolver.canonicalize(ip), Instant.now(clock));
        catalog.refreshBlock(Duration.ofSeconds(5));
    }

    private void removeOrigin(String no, String ip) {
        repository.removeOrigin(no, com.apigw.common.web.ClientIpResolver.canonicalize(ip));
        catalog.refreshBlock(Duration.ofSeconds(5));
    }

    private Result call(String path, String appNo, String secret, String xff, String xRealIp) {
        return client.get().uri(baseUrl + path)
                .headers(h -> {
                    if (appNo != null) {
                        h.set(GatewayHeaders.APP_NO_HEADER, appNo);
                    }
                    if (secret != null) {
                        h.set(GatewayHeaders.APP_SECRET_HEADER, secret);
                    }
                    if (xff != null) {
                        h.set(com.apigw.common.web.ClientIpResolver.XFF_HEADER, xff);
                    }
                    if (xRealIp != null) {
                        h.set(com.apigw.common.web.ClientIpResolver.REAL_IP_HEADER, xRealIp);
                    }
                })
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new Result(
                                resp.statusCode().value(),
                                resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"),
                                b)))
                .block(Duration.ofSeconds(5));
    }

    private Result authed(String path, String appNo) {
        return call(path, appNo, secrets.get(appNo), null, null);
    }

    private Result authedXff(String path, String appNo, String xff) {
        return call(path, appNo, secrets.get(appNo), xff, null);
    }

    // ---- 用例 ----

    @Test
    void missingCredentials_401() {
        createApp("app-1", SECRET, null, 1);
        Result r = call("/order/1", null, null, null, null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorHeader()).isEqualTo("APP_UNAUTHENTICATED");
    }

    @Test
    void unknownApp_401_sameAsBadCredential() {
        createApp("app-1", SECRET, null, 1);
        Result r = call("/order/1", "stranger", SECRET, null, null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorHeader()).isEqualTo("APP_UNAUTHENTICATED");
    }

    @Test
    void wrongSecret_401() {
        createApp("app-1", SECRET, null, 1);
        Result r = call("/order/1", "app-1", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", null, null);
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void expiredSecretAtBoundary_401() {
        // 创建发生在截止前一天（通过聚合「有效期必须是未来时刻」），校验时钟固定在截止时刻本身
        Clock createClock = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);
        ClientApp app = ClientApp.create("app-1", "app-1", SECRET,
                Instant.parse("2026-09-26T00:00:00Z"), 1, null, null, null, createClock);
        repository.insert(app);
        secrets.put("app-1", SECRET);
        catalog.refreshBlock(Duration.ofSeconds(5));

        Result r = authed("/order/1", "app-1");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorHeader()).isEqualTo("APP_UNAUTHENTICATED");
    }

    @Test
    void disable_takesEffectImmediately_andEnableRestores() {
        createApp("app-1", SECRET, null, 1);
        assertThat(authed("/order/1", "app-1").status()).isEqualTo(200);

        repository.updateEnabled("app-1", 0);
        catalog.refreshBlock(Duration.ofSeconds(5));
        Result denied = authed("/order/1", "app-1");
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.errorHeader()).isEqualTo("APP_FORBIDDEN");

        // 重复停用幂等：结果不变
        repository.updateEnabled("app-1", 0);
        catalog.refreshBlock(Duration.ofSeconds(5));
        assertThat(authed("/order/1", "app-1").status()).isEqualTo(403);

        repository.updateEnabled("app-1", 1);
        catalog.refreshBlock(Duration.ofSeconds(5));
        assertThat(authed("/order/1", "app-1").status()).isEqualTo(200);
    }

    @Test
    void emptyOriginList_allowsAnySource_includingProxied() {
        createApp("app-1", SECRET, null, 1);
        assertThat(authedXff("/order/1", "app-1", "203.0.113.55, 10.0.0.1").status()).isEqualTo(200);
    }

    @Test
    void originList_strictExactMatch_andXffOrder() {
        createApp("app-1", SECRET, null, 1);
        addOrigin("app-1", "192.168.1.1");

        // 名单内地址（经代理，XFF 最左是最初客户端）放行
        assertThat(authedXff("/order/1", "app-1", "192.168.1.1, 10.0.0.1").status()).isEqualTo(200);

        // 关键安全点：.10 不能被 .1 放进来
        Result nearMiss = authedXff("/order/1", "app-1", "192.168.1.10, 10.0.0.1");
        assertThat(nearMiss.status()).isEqualTo(403);
        assertThat(nearMiss.errorHeader()).isEqualTo("APP_FORBIDDEN");

        // XFF 最左非法不整串放弃：找到后面的合法跳 10.0.0.2，但它不在名单 → 403
        assertThat(authedXff("/order/1", "app-1", "evil-garbage, 10.0.0.2").status()).isEqualTo(403);

        // XFF 全不可信时回退 X-Real-IP：名单内放行
        assertThat(call("/order/1", "app-1", SECRET, "garbage", "192.168.1.1").status()).isEqualTo(200);
        // XFF/X-Real-IP 都不可信时回退传输层对端（127.0.0.1，不在名单）→ 403
        assertThat(call("/order/1", "app-1", SECRET, "garbage", "garbage").status()).isEqualTo(403);
    }

    @Test
    void ipv6Origin_matchesRegardlessOfSpelling() {
        createApp("app-1", SECRET, null, 1);
        addOrigin("app-1", "2001:db8::1");
        // 名单存压缩小写，请求带大写全写，同一地址必须命中
        Result r = call("/order/1", "app-1", SECRET, "2001:DB8:0:0:0:0:0:1", null);
        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    void originChange_takesEffectImmediately_forNewRequests() {
        createApp("app-1", SECRET, null, 1);
        // 空名单：任意来源放行
        assertThat(authedXff("/order/1", "app-1", "203.0.113.77").status()).isEqualTo(200);

        // 刚配上名单，下一笔新请求立即受限：旧来源立刻 403
        addOrigin("app-1", "198.51.100.9");
        assertThat(authedXff("/order/1", "app-1", "203.0.113.77").status()).isEqualTo(403);
        // 名单内新来源立即放行
        assertThat(authedXff("/order/1", "app-1", "198.51.100.9").status()).isEqualTo(200);

        // 删掉唯一一条，立即恢复「不限」
        removeOrigin("app-1", "198.51.100.9");
        assertThat(authedXff("/order/1", "app-1", "203.0.113.77").status()).isEqualTo(200);
    }

    @Test
    void managementApi_isNotSubjectToAuth() {
        // /api 直接放行，不被鉴权挡（tail 回 200）
        Result r = call("/api/gateway/apps", null, null, null, null);
        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    void encodedOrSlashedManagementPath_isStillNotSubjectToAuth() {
        // passthrough 边界按规范路径判：编码斜杠/重复斜杠不能把管理面请求伪装成第三方流量
        // （若按原始串判，这些请求会因「没带凭据」被 401 挡在管理接口外，甚至绕入转发匹配）
        Result encoded = client.get().uri(java.net.URI.create(baseUrl + "/api%2Fgateway/apps"))
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> new Result(resp.statusCode().value(),
                                resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"), b))).block();
        assertThat(encoded.status()).isEqualTo(200);

        // 用原始 URI 避免 WebClient 客户端侧先把 // 归一；服务端必须自己按规范路径守住边界
        Result doubled = client.get().uri(java.net.URI.create(baseUrl + "//api/gateway/apps"))
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> new Result(resp.statusCode().value(),
                                resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"), b))).block();
        assertThat(doubled.status()).isEqualTo(200);
    }

    @Test
    void acceptedRequest_overwritesAppNoHeaderWithTrustedValue() {
        createApp("app-1", SECRET, null, 1);
        Result r = authed("/order/1", "app-1");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("OK app=app-1");
    }

    /** 内存假仓储（与应用服务测试同款语义，独立放此避免跨包依赖）。 */
    static class FakeRepository implements AppCredentialRepository {
        final Map<String, ClientApp> map = new HashMap<>();
        final Map<String, Set<String>> origins = new HashMap<>();
        private long seq = 0;

        @Override
        public void insert(ClientApp app) {
            app.assignId(++seq);
            map.put(app.getAppNo(), app);
            origins.put(app.getAppNo(), new LinkedHashSet<>());
        }

        @Override
        public Optional<ClientApp> findByAppNo(String appNo) {
            return Optional.ofNullable(map.get(appNo));
        }

        @Override
        public AppPage page(String keyword, long offset, int limit) {
            return new AppPage(List.of(), 0);
        }

        @Override
        public int updateEnabled(String appNo, int target) {
            ClientApp a = map.get(appNo);
            if (a == null) {
                return 0;
            }
            a.changeEnabled(target);
            return 1;
        }

        @Override
        public void addOrigin(String appNo, String ip, Instant now) {
            origins.get(appNo).add(ip);
        }

        @Override
        public void removeOrigin(String appNo, String ip) {
            origins.get(appNo).remove(ip);
        }

        @Override
        public List<AuthApp> loadAllForAuth() {
            List<AuthApp> out = new ArrayList<>();
            for (ClientApp a : map.values()) {
                out.add(new AuthApp(a.getAppNo(), a.getSecretHash(), a.getSecretExpiresAt(),
                        a.getEnabled() == 1, List.copyOf(origins.get(a.getAppNo()))));
            }
            return out;
        }
    }
}
