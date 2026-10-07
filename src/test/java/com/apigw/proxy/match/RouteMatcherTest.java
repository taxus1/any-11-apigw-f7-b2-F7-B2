package com.apigw.proxy.match;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 路由匹配器测试：
 * - 同一条路由的条件全 AND；
 * - METHOD 大小写不敏感；HEADER 头名不敏感、头值敏感；QUERY 名值都敏感；
 * - 多条命中时按「最长路径前缀 → 条件数多 → routeNo 字典序」稳定定序，
 *   同样的请求反复匹配永远是同一条。
 */
class RouteMatcherTest {

    private final RouteMatcher matcher = new RouteMatcher();

    private MockServerHttpRequest request(String method, String uri) {
        // 从原始 URI 构造：与生产 Netty 的行为一致——%2F 等百分号序列原样保留在
        // pathWithinApplication() 里（String 模板构造会把 % 再编码成 %25，不能用来模拟线上）。
        return MockServerHttpRequest.method(
                org.springframework.http.HttpMethod.valueOf(method), java.net.URI.create(uri)).build();
    }

    private GatewayRoute route(String no, String upstream, List<GatewayRule> conditions) {
        GatewayRoute r = GatewayRoute.create(no, no, upstream, 1, null);
        r.replaceRules(conditions, List.of());
        return r;
    }

    private GatewayRule path(String v, int sort) {
        return GatewayRule.create(null, "PATH_PREFIX", null, v, sort);
    }

    private GatewayRule method(String v, int sort) {
        return GatewayRule.create(null, "METHOD", null, v, sort);
    }

    private GatewayRule header(String name, String v, int sort) {
        return GatewayRule.create(null, "HEADER", name, v, sort);
    }

    private GatewayRule query(String name, String v, int sort) {
        return GatewayRule.create(null, "QUERY", name, v, sort);
    }

    @Test
    void allConditionsAreAnded() {
        GatewayRoute r = route("r1", "http://h:1", List.of(
                path("/order/", 1), method("GET", 2)));

        assertEquals("r1", matcher.match(List.of(r), request("GET", "/order/abc")).getRouteNo());
        // 方法不符 → 整条不命中
        assertNull(matcher.match(List.of(r), request("POST", "/order/abc")));
        // 路径不符 → 整条不命中
        assertNull(matcher.match(List.of(r), request("GET", "/other")));
    }

    @Test
    void methodIsCaseInsensitive() {
        GatewayRule m = method("get", 1);
        GatewayRoute r = route("r", "http://h:1", List.of(m));
        assertEquals("r", matcher.match(List.of(r), request("GET", "/x")).getRouteNo());
        assertEquals("r", matcher.match(List.of(r), request("get", "/x")).getRouteNo());
    }

    @Test
    void headerNameCaseInsensitive_valueCaseSensitive() {
        GatewayRoute r = route("r", "http://h:1", List.of(header("X-Caller", "web", 1)));

        var ok = MockServerHttpRequest.get("/x").header("x-caller", "web").build();
        assertEquals("r", matcher.match(List.of(r), ok).getRouteNo());

        var wrongValue = MockServerHttpRequest.get("/x").header("X-Caller", "WEB").build();
        assertNull(matcher.match(List.of(r), wrongValue));

        var missing = MockServerHttpRequest.get("/x").build();
        assertNull(matcher.match(List.of(r), missing));
    }

    @Test
    void queryRequiresNameAndExactValue() {
        GatewayRoute r = route("r", "http://h:1", List.of(query("from", "cart", 1)));

        assertEquals("r", matcher.match(List.of(r), request("GET", "/x?from=cart")).getRouteNo());
        // 值不对 / 没有该参数 → 不命中
        assertNull(matcher.match(List.of(r), request("GET", "/x?from=buy")));
        assertNull(matcher.match(List.of(r), request("GET", "/x")));
        assertNull(matcher.match(List.of(r), request("GET", "/x?FROM=cart")));
    }

    @Test
    void longerPathPrefixWins() {
        GatewayRoute broad = route("broad", "http://h:1", List.of(path("/order/", 1)));
        GatewayRoute specific = route("specific", "http://h:2", List.of(path("/order/abc/", 1)));

        GatewayRoute hit = matcher.match(List.of(broad, specific), request("GET", "/order/abc/x"));
        assertEquals("specific", hit.getRouteNo());

        // 交换列表顺序，结果必须一样（不能受配置遍历顺序影响）
        GatewayRoute hitReversed = matcher.match(List.of(specific, broad), request("GET", "/order/abc/x"));
        assertEquals("specific", hitReversed.getRouteNo());

        // 只落在宽前缀范围 → 宽的
        assertEquals("broad",
                matcher.match(List.of(broad, specific), request("GET", "/order/zzz")).getRouteNo());
    }

    @Test
    void moreConditionsWinWhenPrefixTies() {
        GatewayRoute simple = route("simple", "http://h:1", List.of(path("/order/", 1)));
        GatewayRoute constrained = route("constrained", "http://h:2",
                List.of(path("/order/", 1), method("POST", 2)));

        GatewayRoute hit = matcher.match(List.of(simple, constrained), request("POST", "/order/x"));
        assertEquals("constrained", hit.getRouteNo());

        // POST 条件不满足时只剩 simple
        assertEquals("simple",
                matcher.match(List.of(simple, constrained), request("GET", "/order/x")).getRouteNo());
    }

    @Test
    void routeNoLexicographicIsFinalTieBreaker() {
        GatewayRoute a = route("route-a", "http://h:1", List.of(path("/x/", 1)));
        GatewayRoute b = route("route-b", "http://h:2", List.of(path("/x/", 1)));

        // 条件完全等价，按编号字典序，且与列表顺序无关
        assertEquals("route-a", matcher.match(List.of(a, b), request("GET", "/x/y")).getRouteNo());
        assertEquals("route-a", matcher.match(List.of(b, a), request("GET", "/x/y")).getRouteNo());
    }

    @Test
    void conditionOrderWithinRoute_doesNotChangeOutcome() {
        // 同一条路由的条件是 AND：同一批条件换个顺序，命中与否必须一样
        GatewayRoute ordered = route("r", "http://h:1", List.of(
                path("/order/", 1), method("GET", 2), header("X-Caller", "web", 3), query("from", "cart", 4)));
        GatewayRoute shuffled = route("r", "http://h:1", List.of(
                query("from", "cart", 4), header("X-Caller", "web", 3), method("GET", 2), path("/order/", 1)));

        var hit = MockServerHttpRequest.get("/order/abc?from=cart").header("X-Caller", "web").build();
        var miss = MockServerHttpRequest.get("/order/abc?from=buy").header("X-Caller", "web").build();
        for (var req : List.of(hit, miss)) {
            GatewayRoute a = matcher.match(List.of(ordered), req);
            GatewayRoute b = matcher.match(List.of(shuffled), req);
            assertEquals(a == null ? null : a.getRouteNo(), b == null ? null : b.getRouteNo(),
                    "同批条件换顺序，命中结果不能变");
        }
    }

    @Test
    void noMatchReturnsNull() {
        GatewayRoute r = route("r", "http://h:1", List.of(path("/order/", 1)));
        assertNull(matcher.match(List.of(r), request("GET", "/other")));
        assertNull(matcher.match(List.of(), request("GET", "/order/x")));
    }

    @Test
    void disabledAndConditionlessRoutesAreFilteredByCatalog_notByMatcher() {
        // 匹配器只负责对给它的快照做判断；停用/无条件路由是否进入快照是 RouteCatalog 的口径。
        // 这里确认：给它就匹（match 本身不做启用过滤），口径在 catalog 测试里验证。
        GatewayRoute r = route("r", "http://h:1", List.of(path("/x/", 1)));
        assertEquals("r", matcher.match(List.of(r),
                MockServerWebExchange.from(request("GET", "/x/y")).getRequest()).getRouteNo());
    }

    // ---- 同一份异常写法，无论来自真实请求还是排查描述，结论必须一致 ----
    // 匹配只从 MatchInput 的两个入口进；下面每种写法分别走 from（真实请求）与 of（排查描述），
    // 两边结果必须相同——这是「排查说命中、实际 404」事故的回归。

    @Test
    void encodedSlash_matchesSameAsPlainSlash_fromRealRequest() {
        GatewayRoute r = route("r", "http://h:1", List.of(path("/order/", 1)));
        // 真实转发：%2F 被前端编码过的请求，必须与明文 /order/abc 同结论（命中）
        assertEquals("r", matcher.match(List.of(r), request("GET", "/order%2Fabc")).getRouteNo());
        assertEquals("r", matcher.match(List.of(r), request("GET", "/order%2fabc")).getRouteNo());
        // 排查口子：同一个输入形态，结论一致
        assertEquals("r", matcher.match(List.of(r),
                MatchInput.of("/order%2Fabc", "GET", null, null)).getRouteNo());
    }

    @Test
    void allAbnormalWritings_agreeAcrossRealAndExplain() {
        GatewayRoute subtree = route("subtree", "http://h:1", List.of(path("/order/", 1)));

        // 等价写法矩阵（规则是「仅子树」/order/）：每种写法在真实请求(from)与排查描述(of)上
        // 的命中结论必须逐行一致——重复斜杠、结尾斜杠、编码斜杠、根路径都在这张表里对账。
        record Case(String raw, boolean hit) {
        }
        java.util.List<Case> cases = java.util.List.of(
                new Case("/order/abc", true),
                new Case("/order%2Fabc", true),    // 编码斜杠 → /order/abc
                new Case("/order%2fabc", true),    // 小写 hex
                new Case("/order//abc", true),     // 重复斜杠
                new Case("/order/abc/", true),     // 结尾斜杠（子树内）
                new Case("/order/", true),         // 规则的目录自身
                new Case("/order%2F", true),       // 编码出来的尾斜杠 = /order/，在子树内
                new Case("/order", false),         // /order/ 规则不收精确层
                new Case("/ordering", false),      // 字符串像，段边界挡住
                new Case("/order-x", false),
                new Case("/orders/1", false),
                new Case("/", false),
                new Case("/other", false));

        for (Case c : cases) {
            var real = matcher.match(List.of(subtree), request("GET", c.raw));
            var explained = matcher.match(List.of(subtree),
                    MatchInput.of(c.raw, "GET", null, null));
            assertEquals(c.hit, real != null,
                    "真实转发对 " + c.raw + " 的结论不对");
            assertEquals(real == null ? null : real.getRouteNo(),
                    explained == null ? null : explained.getRouteNo(),
                    "排查与真实转发对 " + c.raw + " 的结论不一致");
        }
    }

    @Test
    void encodedTraversal_cannotBypassPrefixBoundary() {
        // 安全红线：规则只管 /order/ 子树，编码穿越不能把 /admin 送进这条路由
        GatewayRoute r = route("r", "http://h:1", List.of(path("/order/", 1)));
        for (String attack : List.of(
                "/order%2F..%2Fadmin",
                "/order/../admin",
                "/order/%2e%2e/admin",
                "/order//..//admin",
                "/%2forder/%2e%2e/admin")) {
            assertNull(matcher.match(List.of(r), request("GET", attack)),
                    "编码穿越 " + attack + " 不得命中 /order/ 子树规则");
            assertNull(matcher.match(List.of(r), MatchInput.of(attack, "GET", null, null)),
                    "排查口子对 " + attack + " 必须与真实转发一致（不命中）");
        }

        // 穿越后仍落在子树内的，正常命中（归一只摊平写法，不改变合法路径的归属）
        assertEquals("r", matcher.match(List.of(r),
                request("GET", "/order/abc/../abc/x")).getRouteNo());
        assertEquals("r", matcher.match(List.of(r),
                MatchInput.of("/order/abc/../abc/x", "GET", null, null)).getRouteNo());
    }

    @Test
    void rootPrefix_matchesRootAndEverything_inBothWritings() {
        GatewayRoute r = route("r", "http://h:1", List.of(path("/", 1)));
        for (String p : List.of("/", "/x", "/order%2Fabc")) {
            assertEquals("r", matcher.match(List.of(r), request("GET", p)).getRouteNo(),
                    "真实请求 " + p);
            assertEquals("r", matcher.match(List.of(r), MatchInput.of(p, "GET", null, null)).getRouteNo(),
                    "排查描述 " + p);
        }
    }

    @Test
    void queryString_doesNotEnterPathMatch_realAndExplain() {
        // 查询串不属于路径：带 ? 的同一资源与不带查询串同结论（真实请求的 query 由 QUERY 条件管）
        GatewayRoute r = route("r", "http://h:1", List.of(path("/order/", 1)));
        assertEquals("r", matcher.match(List.of(r), request("GET", "/order/abc?from=cart")).getRouteNo());
        assertEquals("r", matcher.match(List.of(r),
                MatchInput.of("/order/abc", "GET", null,
                        java.util.Map.of("from", "cart"))).getRouteNo());
    }
}
