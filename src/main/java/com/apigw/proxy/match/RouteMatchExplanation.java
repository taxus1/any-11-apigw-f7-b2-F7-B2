package com.apigw.proxy.match;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 路由匹配解释器：给一条请求描述（路径/方法/头/查询参数），算出它最终落到哪条路由，
 * 并把「为什么这么落」拆成能逐条核对的人话。
 *
 * 判定逻辑全部复用 {@link RouteMatcher}（同一份 {@link MatchInput} 语义），
 * 这里只做「把判定过程摆出来」，不另立第二套匹配规则——
 * 排查接口的结论与线上转发行为因此永远一致。
 *
 * 输出结构（{@link RouteMatchExplanation}）：
 * - 每条参与匹配的路由一段：逐条件判定（期望值/实际值/是否满足/原因）；
 * - 全部条件命中的路由按 {@link RouteMatcher#PRECEDENCE} 排出名次，
 *   第 1 名是最终落点（WINNER），其余全中者标注被谁、因为什么抢走（LOST_PRECEDENCE）；
 * - 没全中的标注第一条不满足的条件（CONDITION_FAILED）；
 * - 没进快照的路由（停用/无条件/还没发布进快照）单列在 excluded，不混在候选里。
 */
public final class RouteMatchExplanation {

    private RouteMatchExplanation() {
    }

    /** 请求描述的回显（排查时确认「我算的确实是这条请求」）。 */
    public record ExplainedRequest(String path,
                                   String method,
                                   Map<String, String> headers,
                                   Map<String, String> query) {
    }

    /** 一条条件的判定结果。 */
    public record ConditionVerdict(int sortNo,
                                   String type,
                                   String name,
                                   String expected,
                                   String actual,
                                   boolean matched,
                                   String reason) {
    }

    /** 一条路由的整体判定：全中与否、名次、去向、逐条件明细。 */
    public record RouteVerdict(String routeNo,
                               boolean matched,
                               Integer rank,
                               String outcome,
                               String note,
                               List<ConditionVerdict> conditions) {
    }

    /** 没参与匹配的路由及原因（停用/无条件/尚未进入生效快照）。 */
    public record ExcludedRoute(String routeNo, String reason) {
    }

    /** 排查接口的完整答复。matched=false 时 routeNo/upstream 为 null（线上对应 404 NO_ROUTE）。 */
    public record Explanation(ExplainedRequest request,
                              long snapshotRevision,
                              boolean matched,
                              String routeNo,
                              String upstream,
                              List<RouteVerdict> routes,
                              List<ExcludedRoute> excluded) {
    }

    /**
     * 对「当前生效快照里的路由集合」逐条判定并定序。
     * routes 必须与转发链路同源（RouteCatalog 快照），结论才代表线上真实行为。
     */
    public static List<RouteVerdict> evaluate(List<GatewayRoute> routes, MatchInput input,
                                              RouteMatcher matcher) {
        record Judged(GatewayRoute route, List<ConditionVerdict> conditions, boolean matched) {
        }
        List<Judged> judged = new ArrayList<>();
        for (GatewayRoute route : routes) {
            List<ConditionVerdict> conditions = new ArrayList<>();
            boolean all = true;
            for (GatewayRule c : sortedConditions(route)) {
                ConditionVerdict v = judgeCondition(c, input, matcher);
                conditions.add(v);
                if (!v.matched()) {
                    all = false;
                }
            }
            judged.add(new Judged(route, conditions, all));
        }

        // 全中者按定序规则排名：这就是线上「撞配时谁赢」的同一个比较器
        List<Judged> hits = new ArrayList<>(judged.stream().filter(Judged::matched).toList());
        hits.sort(Comparator.comparing(Judged::route, RouteMatcher.PRECEDENCE));
        Map<GatewayRoute, Integer> rankOf = new java.util.IdentityHashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            rankOf.put(hits.get(i).route(), i + 1);
        }
        GatewayRoute winner = hits.isEmpty() ? null : hits.get(0).route();

        List<RouteVerdict> verdicts = new ArrayList<>();
        // 先排全中者（按名次），再排未中者（按编号），输出顺序本身就可读
        for (Judged j : hits) {
            int rank = rankOf.get(j.route());
            verdicts.add(new RouteVerdict(j.route().getRouteNo(), true, rank,
                    rank == 1 ? "WINNER" : "LOST_PRECEDENCE",
                    hitNote(j.route(), rank, hits.size(), winner),
                    j.conditions()));
        }
        judged.stream().filter(j -> !j.matched())
                .sorted(Comparator.comparing(j -> j.route().getRouteNo()))
                .forEach(j -> verdicts.add(new RouteVerdict(j.route().getRouteNo(), false, null,
                        "CONDITION_FAILED", missNote(j.conditions()), j.conditions())));
        return verdicts;
    }

    /** 请求描述回显：头与查询参数按名字排序，方便肉眼核对。 */
    public static ExplainedRequest explainRequest(MatchInput input) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (input.headers() != null) {
            input.headers().forEach((k, v) -> headers.put(k, v == null || v.isEmpty() ? "" : v.get(0)));
        }
        Map<String, String> query = new TreeMap<>();
        if (input.queryParams() != null) {
            input.queryParams().forEach((k, v) -> query.put(k, v == null ? "" : String.join(",", v)));
        }
        return new ExplainedRequest(input.path(), input.method(), headers, query);
    }

    // ---- 以下为内部实现 ----

    private static List<GatewayRule> sortedConditions(GatewayRoute route) {
        List<GatewayRule> copy = new ArrayList<>(route.getConditions());
        copy.sort(Comparator.comparing(GatewayRule::getSortNo, Comparator.nullsLast(Integer::compareTo)));
        return copy;
    }

    private static ConditionVerdict judgeCondition(GatewayRule c, MatchInput input, RouteMatcher matcher) {
        boolean matched = matcher.conditionMatches(c, input);
        String actual = actualOf(c, input);
        // 期望值展示的必须是「实际参与比较的那个值」：路径前缀取规范形式，
        // 避免库里存的旧写法（如 /order%2F）显示成一套、实际按另一套判。
        String expected = RuleTypes.TYPE_PATH_PREFIX.equals(c.getType())
                ? RouteMatcher.prefixOf(c) : c.getValue();
        return new ConditionVerdict(c.getSortNo() == null ? 0 : c.getSortNo(),
                c.getType(), c.getName(), expected, actual, matched,
                reasonOf(c, input, actual, matched));
    }

    /** 这条条件「实际值」一栏：从请求特征里取出它真正看到的东西。 */
    private static String actualOf(GatewayRule c, MatchInput input) {
        switch (c.getType()) {
            case RuleTypes.TYPE_PATH_PREFIX -> {
                return input.path();
            }
            case RuleTypes.TYPE_METHOD -> {
                return input.method().isEmpty() ? "（无方法）" : input.method();
            }
            case RuleTypes.TYPE_HEADER -> {
                String v = input.firstHeader(c.getName());
                return v == null ? "（无此头）" : v;
            }
            case RuleTypes.TYPE_QUERY -> {
                List<String> vs = input.queryValues(c.getName());
                return vs == null ? "（无此参数）" : String.join(",", vs);
            }
            default -> {
                return "-";
            }
        }
    }

    private static String reasonOf(GatewayRule c, MatchInput input, String actual, boolean matched) {
        switch (c.getType()) {
            case RuleTypes.TYPE_PATH_PREFIX -> {
                String prefix = RouteMatcher.prefixOf(c);
                return matched
                        ? "路径 " + actual + " 落在前缀 " + prefix + " 的边界内"
                        : "路径 " + actual + " 不在前缀 " + prefix + " 的边界内（注意段边界与尾斜杠语义）";
            }
            case RuleTypes.TYPE_METHOD -> {
                return matched
                        ? "方法一致（" + actual + "，方法名大小写不敏感）"
                        : "方法期望 " + c.getValue() + "，实际 " + actual;
            }
            case RuleTypes.TYPE_HEADER -> {
                if (input.firstHeader(c.getName()) == null) {
                    return "请求没有 " + c.getName() + " 头（头名大小写不敏感）";
                }
                return matched
                        ? "头 " + c.getName() + "=" + actual + " 与配置值精确相等"
                        : "头 " + c.getName() + " 期望 " + c.getValue() + "，实际 " + actual + "（头值大小写敏感）";
            }
            case RuleTypes.TYPE_QUERY -> {
                if (input.queryValues(c.getName()) == null) {
                    return "URL 查询串里没有 " + c.getName() + " 参数（只看 URL，不看请求体）";
                }
                return matched
                        ? "参数 " + c.getName() + " 带着值 " + c.getValue()
                        : "参数 " + c.getName() + " 期望 " + c.getValue() + "，实际 " + actual;
            }
            default -> {
                return "未知条件类型 " + c.getType() + "，按不命中处理";
            }
        }
    }

    private static String hitNote(GatewayRoute route, int rank, int hitCount, GatewayRoute winner) {
        int conditions = route.getConditions().size();
        if (rank == 1) {
            return "全部 " + conditions + " 条条件命中；在 " + hitCount
                    + " 条全中路由里定序第 1，请求落在这条";
        }
        return "全部 " + conditions + " 条条件命中，但定序第 " + rank
                + "，被 " + winner.getRouteNo() + " 抢走：" + precedenceReason(route, winner);
    }

    /** 输家与赢家之间第一个分出胜负的比较维度（与 PRECEDENCE 的三级次序一一对应）。 */
    private static String precedenceReason(GatewayRoute loser, GatewayRoute winner) {
        int lp = RouteMatcher.pathPrefixLength(loser);
        int wp = RouteMatcher.pathPrefixLength(winner);
        if (lp != wp) {
            return "路径前缀更短（" + lp + " < " + wp + "），更具体的路径优先";
        }
        int lc = loser.getConditions().size();
        int wc = winner.getConditions().size();
        if (lc != wc) {
            return "条件数更少（" + lc + " < " + wc + "），约束更具体的优先";
        }
        return "前缀长度与条件数都并列，编号字典序靠后（" + loser.getRouteNo()
                + " 在 " + winner.getRouteNo() + " 之后）";
    }

    private static String missNote(List<ConditionVerdict> conditions) {
        for (int i = 0; i < conditions.size(); i++) {
            ConditionVerdict v = conditions.get(i);
            if (!v.matched()) {
                return "第 " + (i + 1) + " 条条件不满足（" + v.type()
                        + (v.name() == null ? "" : " " + v.name()) + "）：" + v.reason()
                        + "；同一路由的条件是 AND，一条不过即整条不命中";
            }
        }
        return "全部条件命中";
    }
}
