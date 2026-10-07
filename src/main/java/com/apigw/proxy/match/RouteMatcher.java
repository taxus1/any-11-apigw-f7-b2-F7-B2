package com.apigw.proxy.match;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 路由匹配：拿一份「当前启用、可匹配」的路由快照，对一个请求找出唯一命中的路由。
 *
 * 命中规则：同一条路由上的全部匹配条件是 AND，任何一条不满足就不命中；
 * 条件之间没有顺序语义——同一批条件怎么排列，命中与否都一样（AND 天然与顺序无关）。
 * 多条路由同时命中时，按下列确定次序选出唯一一条（同样的请求永远走同一条，不会漂移）：
 *   1. PATH_PREFIX 前缀更长的优先（更具体的路径赢；没写路径条件的按 0 长度排最后）；
 *   2. 仍并列（如路径条件相同、或都没路径条件）时，条件总数更多的赢（约束更具体）；
 *   3. 还并列就按路由编号字典序（routeNo 只含字母数字 . _ -，字典序确定且稳定）。
 *
 * 各条件类型的语义：
 * - PATH_PREFIX：见 {@link PathPrefixMatcher}，按常规 URL 语义路径大小写敏感；
 * - METHOD：HTTP 方法名大小写不敏感（GET 与 get 等价），比较时统一大写；
 * - HEADER：头名大小写不敏感（HTTP 头本来就不区分大小写），头值大小写敏感、精确相等；
 * - QUERY：参数名大小写敏感，参数值大小写敏感、精确相等（只判断该参数是否带着这个值），
 *   认的是 URL 查询串，请求体里的同名字段不算。
 *
 * 判定只依赖 {@link MatchInput}：转发链路从真实请求提取，排查接口从手工描述构造，
 * 两处共用这一份逻辑，保证排查结果与线上行为一致。
 * 这类不持有状态、不碰 Redis，快照由上层 RouteCatalog 提供，方便直接单测。
 */
@Component
public class RouteMatcher {

    /**
     * 多路由撞配时的稳定次序：前缀长度降序 → 条件数降序 → 编号升序。
     */
    static final Comparator<GatewayRoute> PRECEDENCE = Comparator
            .comparingInt((GatewayRoute r) -> pathPrefixLength(r))
            .reversed()
            .thenComparing(Comparator.comparingInt((GatewayRoute r) -> r.getConditions().size()).reversed())
            .thenComparing(GatewayRoute::getRouteNo);

    /**
     * 返回唯一命中的路由；一条都不命中返回 null（上层据此回 404 NO_ROUTE）。
     */
    public GatewayRoute match(List<GatewayRoute> routes, ServerHttpRequest request) {
        return match(routes, MatchInput.from(request));
    }

    /** 同 {@link #match(List, ServerHttpRequest)}，输入是已归一化的请求特征。 */
    public GatewayRoute match(List<GatewayRoute> routes, MatchInput input) {
        GatewayRoute best = null;
        for (GatewayRoute route : routes) {
            if (!allConditionsMatch(route, input)) {
                continue;
            }
            if (best == null || PRECEDENCE.compare(route, best) < 0) {
                best = route;
            }
        }
        return best;
    }

    /** 一条路由的全部条件 AND。 */
    boolean allConditionsMatch(GatewayRoute route, MatchInput input) {
        for (GatewayRule c : route.getConditions()) {
            if (!conditionMatches(c, input)) {
                return false;
            }
        }
        return true;
    }

    /** 单条条件判定（包内可见：排查解释器复用同一份语义，不另起炉灶）。 */
    boolean conditionMatches(GatewayRule c, MatchInput input) {
        switch (c.getType()) {
            case RuleTypes.TYPE_PATH_PREFIX -> {
                // 规则前缀也走唯一口径：库里存的旧写法（编码斜杠/重复斜杠/穿越段）与请求路径
                // 用同一个 PathNormalizer 收敛后再比；尾斜杠作为语义边界被保留。
                return PathPrefixMatcher.matches(prefixOf(c), input.path());
            }
            case RuleTypes.TYPE_METHOD -> {
                // 方法名是大小写不敏感的 token
                return !input.method().isEmpty()
                        && c.getValue().equalsIgnoreCase(input.method());
            }
            case RuleTypes.TYPE_HEADER -> {
                String actual = input.firstHeader(c.getName());
                return actual != null && actual.equals(c.getValue());
            }
            case RuleTypes.TYPE_QUERY -> {
                List<String> values = input.queryValues(c.getName());
                return values != null && values.contains(c.getValue());
            }
            default -> {
                // 条件白名单在配置保存时已守住，这里属于数据异常，保守按不命中处理
                return false;
            }
        }
    }

    /**
     * 前缀的规范形式：路径条件判定/展示/定序都以它为准，不直接读 {@code getValue()}。
     * 包内可见，排查解释器展示「期望值」时也取这里，保证解释里看到的前缀就是实际参与比较的前缀。
     */
    static String prefixOf(GatewayRule c) {
        return PathNormalizer.normalize(c.getValue());
    }

    /** 取路由上路径前缀条件的前缀长度（多条时取最长）；没有路径条件返回 0。 */
    static int pathPrefixLength(GatewayRoute route) {
        int len = 0;
        for (GatewayRule c : route.getConditions()) {
            if (RuleTypes.TYPE_PATH_PREFIX.equals(c.getType()) && c.getValue() != null) {
                // 用规范形式量长度：库里旧写法多长不该影响「谁更具体」的定序
                len = Math.max(len, prefixOf(c).length());
            }
        }
        return len;
    }

    /** 排序/日志里用得上：把方法名归一化成大写。 */
    public static String normalizeMethod(String method) {
        return method == null ? "" : method.toUpperCase(Locale.ROOT);
    }
}
