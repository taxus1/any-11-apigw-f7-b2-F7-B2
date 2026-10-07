package com.apigw.application.route;

import com.apigw.common.exception.BizException;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.infrastructure.store.RouteStore;
import com.apigw.proxy.match.MatchInput;
import com.apigw.proxy.match.RouteMatchExplanation;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「这条请求会落到哪条路由」的排查服务。
 *
 * 关键口径：评估用的路由集合来自 {@link RouteCatalog#snapshot()}——
 * 就是转发链路此刻正在用的那份快照（含 revision），不是另查一份新数据。
 * 所以这里算出的落点，就是同一个请求现在打进来会走的落点；
 * 配置刚改、快照还没切换时，看到的也是「线上实际生效的旧配置」而非「库里的新配置」。
 *
 * 没进快照的路由（停用/没条件/还没发布进去）不单独立一套规则猜，
 * 按 {@link RouteCatalog} 的过滤口径原样标注在 excluded 里。
 */
@Service
public class RouteExplainService {

    private final RouteCatalog routeCatalog;
    private final RouteStore routeStore;
    private final RouteMatcher routeMatcher;

    public RouteExplainService(RouteCatalog routeCatalog, RouteStore routeStore, RouteMatcher routeMatcher) {
        this.routeCatalog = routeCatalog;
        this.routeStore = routeStore;
        this.routeMatcher = routeMatcher;
    }

    public Mono<RouteMatchExplanation.Explanation> explain(String path, String method,
                                                           Map<String, String> headers,
                                                           Map<String, String> query) {
        // 路径口径在 MatchInput.of 内部与真实转发走同一遍 PathNormalizer，这里不另做归一，
        // 免得排查口子和转发链路各处理一遍、处理方式哪天改岔了又出现两套结论。
        MatchInput input = MatchInput.of(requirePath(path), requireMethod(method), headers, query);
        return routeCatalog.snapshot()
                .flatMap(snapshot -> routeStore.findAll().collectList()
                        // 全量只用来标注「谁没参与、为什么」；它一时读不到不影响主结论
                        .onErrorReturn(List.of())
                        .map(all -> assemble(snapshot, all, input)));
    }

    private RouteMatchExplanation.Explanation assemble(com.apigw.proxy.route.RouteSnapshot snapshot,
                                                       List<GatewayRoute> all,
                                                       MatchInput input) {
        List<RouteMatchExplanation.RouteVerdict> verdicts =
                RouteMatchExplanation.evaluate(snapshot.routes(), input, routeMatcher);

        RouteMatchExplanation.RouteVerdict winner = verdicts.stream()
                .filter(v -> v.rank() != null && v.rank() == 1)
                .findFirst().orElse(null);
        GatewayRoute winnerRoute = winner == null ? null : snapshot.routes().stream()
                .filter(r -> r.getRouteNo().equals(winner.routeNo()))
                .findFirst().orElse(null);

        return new RouteMatchExplanation.Explanation(
                RouteMatchExplanation.explainRequest(input),
                snapshot.revision(),
                winnerRoute != null,
                winnerRoute == null ? null : winnerRoute.getRouteNo(),
                winnerRoute == null ? null : winnerRoute.getUpstream(),
                verdicts,
                excluded(snapshot.routes(), all));
    }

    /** 全量配置里没进当前生效快照的路由，逐条说清为什么没参与。 */
    private List<RouteMatchExplanation.ExcludedRoute> excluded(List<GatewayRoute> participating,
                                                               List<GatewayRoute> all) {
        Set<String> active = participating.stream().map(GatewayRoute::getRouteNo).collect(Collectors.toSet());
        List<RouteMatchExplanation.ExcludedRoute> out = new ArrayList<>();
        for (GatewayRoute r : all) {
            if (active.contains(r.getRouteNo())) {
                continue;
            }
            String reason;
            if (!Integer.valueOf(1).equals(r.getEnabled())) {
                reason = "已停用，不参与匹配";
            } else if (r.getConditions() == null || r.getConditions().isEmpty()) {
                reason = "没有配置匹配条件，不参与匹配";
            } else {
                reason = "已启用且有条件，但不在当前生效快照里（配置可能正在集群内发布，稍等再核对）";
            }
            out.add(new RouteMatchExplanation.ExcludedRoute(r.getRouteNo(), reason));
        }
        out.sort(java.util.Comparator.comparing(RouteMatchExplanation.ExcludedRoute::routeNo));
        return out;
    }

    private static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException("排查用的请求路径不能为空");
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            throw new BizException("请求路径必须以 / 开头（应用内路径，不带 host 与查询串），收到的是：" + p);
        }
        if (p.contains("?")) {
            // 查询串混进路径里只会算出一个误导结论，直接拦下说清往哪填
            throw new BizException("请求路径里不要带查询串（? 及之后的部分）；"
                    + "查询参数请填在 query 字段里，例如 \"query\": {\"from\": \"cart\"}");
        }
        return p;
    }

    private static String requireMethod(String method) {
        if (method == null || method.isBlank()) {
            throw new BizException("排查用的请求方法不能为空（GET/POST/...，大小写都行）");
        }
        return method.trim();
    }
}
