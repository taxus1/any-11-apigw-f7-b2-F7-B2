package com.apigw.interfaces.rest.route;

import com.apigw.application.route.RouteExplainService;
import com.apigw.common.exception.GlobalExceptionHandler;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.infrastructure.store.RouteStore;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.apigw.proxy.route.RouteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 排查接口 Web 切片测试：Controller + Service + 匹配器全部真实，
 * 只把 RouteCatalog（快照来源）与 RouteStore（全量来源）mock 掉，不依赖 Redis。
 *
 * 覆盖：
 * - 正常解释：最终落点、逐条件明细、被更靠前路由抢走的说辞；
 * - 没命中也是 code=0 的正常结论（对应线上 404 NO_ROUTE）；
 * - 停用/无条件的路由列进 excluded 并带原因；
 * - 请求描述不像样（路径空、路径不带 /、方法空）回业务失败。
 */
class RouteExplainControllerWebTest {

    private RouteCatalog catalog;
    private RouteStore store;
    private WebTestClient web;

    @BeforeEach
    void setUp() {
        catalog = mock(RouteCatalog.class);
        store = mock(RouteStore.class);
        var service = new RouteExplainService(catalog, store, new RouteMatcher());
        web = WebTestClient.bindToController(new RouteExplainController(service))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private GatewayRoute route(String no, Integer enabled, List<GatewayRule> conditions) {
        GatewayRoute r = GatewayRoute.create(no, no, "http://upstream-" + no + ":8080", enabled, null);
        r.replaceRules(conditions, List.of());
        return r;
    }

    private GatewayRule path(String v, int sort) {
        return GatewayRule.create(null, "PATH_PREFIX", null, v, sort);
    }

    private GatewayRule method(String v, int sort) {
        return GatewayRule.create(null, "METHOD", null, v, sort);
    }

    /** 快照里放参与匹配的路由；findAll 返回全量（含停用）。 */
    private void stubSnapshot(List<GatewayRoute> active, List<GatewayRoute> all) {
        when(catalog.snapshot()).thenReturn(Mono.just(
                new RouteSnapshot(7, "sha256:test", active, Instant.now(), Instant.now())));
        when(store.findAll()).thenReturn(Flux.fromIterable(all));
    }

    private Map<String, Object> explainBody(String path, String method,
                                            Map<String, String> headers, Map<String, String> query) {
        var m = new HashMap<String, Object>();
        if (path != null) {
            m.put("path", path);
        }
        if (method != null) {
            m.put("method", method);
        }
        if (headers != null) {
            m.put("headers", headers);
        }
        if (query != null) {
            m.put("query", query);
        }
        return m;
    }

    @Test
    void explain_hit_reportsWinnerAndLoser() {
        GatewayRoute broad = route("broad", 1, List.of(path("/order/", 1)));
        GatewayRoute specific = route("specific", 1, List.of(path("/order/abc/", 1), method("GET", 2)));
        stubSnapshot(List.of(broad, specific), List.of(broad, specific));

        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/order/abc/x", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.matched").isEqualTo(true)
                .jsonPath("$.data.routeNo").isEqualTo("specific")
                .jsonPath("$.data.upstream").isEqualTo("http://upstream-specific:8080")
                .jsonPath("$.data.snapshotRevision").isEqualTo(7)
                .jsonPath("$.data.routes[0].routeNo").isEqualTo("specific")
                .jsonPath("$.data.routes[0].outcome").isEqualTo("WINNER")
                .jsonPath("$.data.routes[0].rank").isEqualTo(1)
                .jsonPath("$.data.routes[1].routeNo").isEqualTo("broad")
                .jsonPath("$.data.routes[1].outcome").isEqualTo("LOST_PRECEDENCE")
                .jsonPath("$.data.routes[1].note").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString()).contains("specific"));
    }

    @Test
    void explain_conditionFailure_showsWhichConditionAndWhy() {
        GatewayRoute r = route("order", 1, List.of(path("/order/", 1), method("POST", 2)));
        stubSnapshot(List.of(r), List.of(r));

        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/order/x", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.matched").isEqualTo(false)
                .jsonPath("$.data.routes[0].outcome").isEqualTo("CONDITION_FAILED")
                .jsonPath("$.data.routes[0].conditions[0].matched").isEqualTo(true)
                .jsonPath("$.data.routes[0].conditions[1].matched").isEqualTo(false)
                .jsonPath("$.data.routes[0].conditions[1].expected").isEqualTo("POST")
                .jsonPath("$.data.routes[0].conditions[1].actual").isEqualTo("GET");
    }

    @Test
    void explain_noHitAtAll_isNormalAnswerNotError() {
        GatewayRoute r = route("order", 1, List.of(path("/order/", 1)));
        stubSnapshot(List.of(r), List.of(r));

        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/nowhere", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.matched").isEqualTo(false)
                .jsonPath("$.data.routeNo").doesNotExist();
    }

    @Test
    void explain_disabledAndConditionlessRoutes_areListedAsExcluded() {
        GatewayRoute active = route("active", 1, List.of(path("/a/", 1)));
        GatewayRoute disabled = route("disabled", 0, List.of(path("/d/", 1)));
        GatewayRoute noConds = route("empty", 1, List.of());
        stubSnapshot(List.of(active), List.of(active, disabled, noConds));

        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/a/1", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.excluded.length()").isEqualTo(2)
                .jsonPath("$.data.excluded[0].routeNo").isEqualTo("disabled")
                .jsonPath("$.data.excluded[0].reason").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString()).contains("停用"))
                .jsonPath("$.data.excluded[1].routeNo").isEqualTo("empty")
                .jsonPath("$.data.excluded[1].reason").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString()).contains("没有配置匹配条件"));
    }

    @Test
    void explain_headerAndQueryAreTakenFromDescription() {
        GatewayRoute r = route("h", 1, List.of(
                GatewayRule.create(null, "HEADER", "X-Caller", "web", 1),
                GatewayRule.create(null, "QUERY", "from", "cart", 2)));
        stubSnapshot(List.of(r), List.of(r));

        // 头名小写也认（头名大小写不敏感），查询参数从 query 描述取
        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/x", "GET",
                        Map.of("x-caller", "web"), Map.of("from", "cart")))
                .exchange().expectBody()
                .jsonPath("$.data.matched").isEqualTo(true)
                .jsonPath("$.data.routeNo").isEqualTo("h");

        // 头值不对就不中
        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/x", "GET",
                        Map.of("X-Caller", "WEB"), Map.of("from", "cart")))
                .exchange().expectBody()
                .jsonPath("$.data.matched").isEqualTo(false);
    }

    @Test
    void explain_encodedSlash_givesSameVerdictAsRealForwarding() {
        // 事故 1：排查口子填 /order%2Fabc 必须和线上真实收到该写法一致——命中
        GatewayRoute r = route("order", 1, List.of(path("/order/", 1)));
        stubSnapshot(List.of(r), List.of(r));

        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/order%2Fabc", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.matched").isEqualTo(true)
                .jsonPath("$.data.routeNo").isEqualTo("order")
                // 回显的是归一后的路径，排查者能直接看到口径
                .jsonPath("$.data.request.path").isEqualTo("/order/abc");

        // 大写 %2f、重复斜杠同理
        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/order//abc", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.data.matched").isEqualTo(true);
    }

    @Test
    void explain_encodedTraversal_doesNotLeapAcrossBoundary() {
        // 安全红线：/order%2F..%2Fadmin 归一成 /admin，不被 /order/ 收下
        GatewayRoute order = route("order", 1, List.of(path("/order/", 1)));
        stubSnapshot(List.of(order), List.of(order));

        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/order%2F..%2Fadmin", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.data.matched").isEqualTo(false)
                .jsonPath("$.data.request.path").isEqualTo("/admin");
    }

    @Test
    void explain_trailingSlashBoundaryDistinctionHolds() {
        // /order/ 规则：/order 这一层不命中，/order/、/order/abc 命中
        GatewayRoute subtree = route("order", 1, List.of(path("/order/", 1)));
        stubSnapshot(List.of(subtree), List.of(subtree));

        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/order", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.data.matched").isEqualTo(false);
        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/order/", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.data.matched").isEqualTo(true);

        // /ordering、/order-x 这种字符串像的，任何写法都不进
        for (String lookalike : List.of("/ordering", "/order-x", "/order%2Dx", "/order%69ng")) {
            web.post().uri("/api/gateway/routes/_explain")
                    .bodyValue(explainBody(lookalike, "GET", null, null))
                    .exchange().expectBody()
                    .jsonPath("$.data.matched").isEqualTo(false);
        }
    }

    @Test
    void explain_badDescription_rejected() {
        stubSnapshot(List.of(), List.of());

        // 路径为空
        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody(null, "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString()).contains("路径不能为空"));

        // 路径不带 /
        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("order/abc", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString()).contains("必须以 / 开头"));

        // 方法为空
        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/order/abc", " ", null, null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString()).contains("方法不能为空"));

        // 查询串混在路径里：拦下并指到 query 字段
        web.post().uri("/api/gateway/routes/_explain")
                .bodyValue(explainBody("/order/abc?from=cart", "GET", null, null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString()).contains("query"));
    }
}
