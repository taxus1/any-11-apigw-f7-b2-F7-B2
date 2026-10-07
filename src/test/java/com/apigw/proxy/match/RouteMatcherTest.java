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
        return MockServerHttpRequest.method(method, uri).build();
    }

    /**
     * 构造「线上真实报文」的请求：URI 字符串里带 % 转义时，
     * MockServerHttpRequest.get(String) 会把 % 再编码一遍（%2F→%252F），
     * 与真实 Netty 收到的单重编码不符；传 URI 对象才保留原始字节。
     */
    private MockServerHttpRequest rawGet(String rawPathAndQuery) {
        try {
            return MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET,
                    new java.net.URI("http://localhost" + rawPathAndQuery)).build();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
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
    void encodedSlashInRealRequest_matchesSameAsPlainWriting() {
        // 线上事故点：/order%2Fabc 真实打进来，必须与 /order/abc 命中同一条（不能 404）
        GatewayRoute r = route("r", "http://h:1", List.of(path("/order/", 1)));

        var encoded = rawGet("/order%2Fabc");
        assertEquals("r", matcher.match(List.of(r), encoded).getRouteNo());

        var plain = request("GET", "/order/abc");
        assertEquals("r", matcher.match(List.of(r), plain).getRouteNo());

        // 重复斜杠、点段写法同样按归一口径命中
        assertEquals("r", matcher.match(List.of(r),
                MockServerHttpRequest.get("/order//abc").build()).getRouteNo());
        assertEquals("r", matcher.match(List.of(r),
                MockServerHttpRequest.get("/order/./abc").build()).getRouteNo());
    }

    @Test
    void encodedTraversal_cannotEscapePrefixBoundary() {
        // 安全红线：编码点段不能把请求带出 /order/ 子树
        GatewayRoute order = route("order", "http://h:1", List.of(path("/order/", 1)));
        GatewayRoute admin = route("admin", "http://h:2", List.of(path("/admin", 1)));

        // /order%2F..%2Fadmin 归一后是 /admin：只能落在 admin，order 边界没被穿越
        GatewayRoute hit = matcher.match(List.of(order, admin),
                rawGet("/order%2F..%2Fadmin"));
        assertEquals("admin", hit.getRouteNo());

        // 只有 /order 规则时它不该被收下（归一后是 /admin，不在子树内）
        assertNull(matcher.match(List.of(order),
                rawGet("/order%2F..%2Fadmin")));
        // /order%2F..%2Fadmin/x → /admin/x，同样落到 admin 子树
        assertEquals("admin", matcher.match(List.of(order, admin),
                rawGet("/order%2F..%2Fadmin%2Fx")).getRouteNo());
    }

    @Test
    void trailingSlashBoundary_holdsUnderCanonicalInput() {
        // /order/ 规则：/order 这一层不放行，结尾斜杠写法放行
        GatewayRoute subtree = route("r", "http://h:1", List.of(path("/order/", 1)));
        assertNull(matcher.match(List.of(subtree), MockServerHttpRequest.get("/order").build()));
        assertEquals("r", matcher.match(List.of(subtree),
                MockServerHttpRequest.get("/order/").build()).getRouteNo());

        // /order 规则（精确 + 子树）：两层都放行，但字符串像的 /ordering、/order-x 不放
        GatewayRoute exact = route("e", "http://h:1", List.of(path("/order", 1)));
        assertEquals("e", matcher.match(List.of(exact), MockServerHttpRequest.get("/order").build()).getRouteNo());
        assertEquals("e", matcher.match(List.of(exact), MockServerHttpRequest.get("/order/abc").build()).getRouteNo());
        assertNull(matcher.match(List.of(exact), MockServerHttpRequest.get("/ordering").build()));
        assertNull(matcher.match(List.of(exact), MockServerHttpRequest.get("/order-x").build()));
    }

    @Test
    void forwardingAndExplainInputs_giveIdenticalVerdict() {
        // 同一异常写法，从真实请求构造与从排查描述构造，必须是同一份结论
        GatewayRoute r = route("r", "http://h:1",
                List.of(path("/order/", 1), method("GET", 2)));
        List<GatewayRoute> routes = List.of(r);

        for (String raw : List.of(
                "/order%2Fabc", "/order/abc", "/order//abc", "/order/abc/", "/order",
                "/order%2f..%2fadmin")) {
            // 线上：真实报文（URI 对象保留单重 % 编码）；排查：运营把同样的写法填进 path
            MatchInput viaForwarding = MatchInput.from(rawGet(raw));
            MatchInput viaExplain = MatchInput.of(raw, "GET", null, null);
            assertEquals(viaForwarding.path(), viaExplain.path(),
                    "排查与转发对 " + raw + " 的归一路径不一致");
            GatewayRoute a = matcher.match(routes, viaForwarding);
            GatewayRoute b = matcher.match(routes, viaExplain);
            assertEquals(a == null ? null : a.getRouteNo(), b == null ? null : b.getRouteNo(),
                    "排查与转发对 " + raw + " 的命中路由不一致");
        }
    }

    @Test
    void queryString_isNotPartOfPathMatching() {
        // ? 及之后归查询串，路径判定只看前面那段；编码在查询串里的斜杠不影响路径
        GatewayRoute r = route("r", "http://h:1", List.of(path("/order/", 1)));
        assertEquals("r", matcher.match(List.of(r),
                rawGet("/order/abc?redirect=" + "%2Fadmin")).getRouteNo());
        assertEquals("r", matcher.match(List.of(r),
                rawGet("/order%2Fabc?from=cart")).getRouteNo());
    }

    @Test
    void disabledAndConditionlessRoutesAreFilteredByCatalog_notByMatcher() {
        // 匹配器只负责对给它的快照做判断；停用/无条件路由是否进入快照是 RouteCatalog 的口径。
        // 这里确认：给它就匹（match 本身不做启用过滤），口径在 catalog 测试里验证。
        GatewayRoute r = route("r", "http://h:1", List.of(path("/x/", 1)));
        assertEquals("r", matcher.match(List.of(r),
                MockServerWebExchange.from(request("GET", "/x/y")).getRequest()).getRouteNo());
    }
}
