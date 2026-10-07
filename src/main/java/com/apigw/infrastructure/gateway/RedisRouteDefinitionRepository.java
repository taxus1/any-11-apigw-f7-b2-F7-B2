package com.apigw.infrastructure.gateway;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import com.apigw.infrastructure.store.RouteStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把存在 Redis 里的路由配置翻译成 Spring Cloud Gateway 的 {@link RouteDefinition}。
 *
 * 这层是「我们的业务模型」与「SCG 的运行时模型」之间的唯一适配点：
 * - 业务侧只认 GatewayRoute / GatewayRule（条件 PATH_PREFIX、METHOD…；动作 REQ_ADD_HEADER…）；
 * - SCG 侧认 Predicate + Filter 的名字与参数（Path、Method、AddRequestHeader…）。
 *
 * SCG 会在启动时用这个仓库加载路由；因为配置在 Redis，后面的题只要实现
 * 「改完就刷新」，就能做到不重启生效。
 */
@Slf4j
@Component
public class RedisRouteDefinitionRepository implements RouteDefinitionRepository {

    /** 把业务动作类型映射到 SCG 内置 GatewayFilterFactory 的名字。 */
    private static final Map<String, String> ACTION_TO_FILTER = Map.of(
            RuleTypes.TYPE_REQ_ADD_HEADER, "AddRequestHeader",
            RuleTypes.TYPE_REQ_REMOVE_HEADER, "RemoveRequestHeader",
            RuleTypes.TYPE_RESP_ADD_HEADER, "AddResponseHeader",
            RuleTypes.TYPE_RESP_REMOVE_HEADER, "RemoveResponseHeader");

    private final RouteStore routeStore;

    public RedisRouteDefinitionRepository(RouteStore routeStore) {
        this.routeStore = routeStore;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return routeStore.findAll()
                .filter(r -> r.getEnabled() != null && r.getEnabled() == 1)
                .filter(r -> !r.getConditions().isEmpty())
                .sort(Comparator.comparing(GatewayRoute::getRouteNo))
                .map(this::toRouteDefinition)
                .doOnNext(d -> log.debug("装载路由定义 id={} uri={} predicates={} filters={}",
                        d.getId(), d.getUri(), d.getPredicates().size(), d.getFilters().size()))
                // Redis 一时不可用不能拖垮整个应用启动：转发链路（GatewayProxyWebFilter）有自己的
                // 路由快照与容错，这里作为只读适配退化为「暂无可装载定义」，等下一次刷新再补
                .onErrorResume(err -> {
                    log.warn("从 Redis 装载 SCG 路由定义失败，本次按空路由处理（转发链路仍按其快照服务）：{}",
                            err.toString());
                    return Flux.empty();
                });
    }

    @Override
    public Mono<Void> save(Mono<RouteDefinition> route) {
        // 管理接口不走这里落库，统一走 RouteStore；这里只做只读仓库，
        // 避免出现「SCG 写一份、管理接口写一份」两条真源。
        return Mono.error(new UnsupportedOperationException(
                "路由请走管理接口 /api/gateway/routes 维护，不要直接写 RouteDefinitionRepository"));
    }

    @Override
    public Mono<Void> delete(Mono<String> routeId) {
        return Mono.error(new UnsupportedOperationException(
                "路由请走管理接口 /api/gateway/routes 维护，不要直接写 RouteDefinitionRepository"));
    }

    private RouteDefinition toRouteDefinition(GatewayRoute route) {
        RouteDefinition def = new RouteDefinition();
        // SCG 的路由 id 用业务编号，便于日志与排障对齐
        def.setId(route.getRouteNo());
        def.setUri(URI.create(route.getUpstream()));
        def.setOrder(0);

        List<PredicateDefinition> predicates = new ArrayList<>();
        for (GatewayRule c : RouteStore.sorted(route.getConditions())) {
            predicates.add(toPredicate(c));
        }
        def.setPredicates(predicates);

        List<FilterDefinition> filters = new ArrayList<>();
        for (GatewayRule a : RouteStore.sorted(route.getActions())) {
            filters.add(toFilter(a));
        }
        def.setFilters(filters);
        return def;
    }

    private PredicateDefinition toPredicate(GatewayRule rule) {
        switch (rule.getType()) {
            case RuleTypes.TYPE_PATH_PREFIX ->
                    // 「前缀」语义翻译成 PathPattern 的 ** 通配。尾斜杠区分必须与
                    // PathPrefixMatcher 完全一致（这里不允许出现第二份语义）：
                    // /order/（带尾斜杠）= 仅子树 → /order/**；
                    // /order（不带尾斜杠）= 精确 + 子树 → /order,/order/**。
                    { return new PredicateDefinition("Path=" + toPrefixPatterns(rule.getValue())); }
            case RuleTypes.TYPE_METHOD ->
                    { return new PredicateDefinition("Method=" + rule.getValue().toUpperCase(Locale.ROOT)); }
            case RuleTypes.TYPE_HEADER -> {
                return new PredicateDefinition("Header=" + rule.getName() + "," + rule.getValue());
            }
            case RuleTypes.TYPE_QUERY -> {
                return new PredicateDefinition("Query=" + rule.getName() + "," + rule.getValue());
            }
            default -> throw new IllegalStateException("未知条件类型：" + rule.getType());
        }
    }

    /**
     * 把「路径前缀」翻译成 SCG Path 断言用的 pattern 列表（逗号分隔的多值），语义与
     * {@link com.apigw.proxy.match.PathPrefixMatcher} 一一对应：
     * <ul>
     *   <li>结尾带斜杠（仅子树）：{@code /order/} → {@code /order/**}（不含 {@code /order} 自身）；</li>
     *   <li>结尾不带斜杠（精确 + 子树）：{@code /order} → {@code /order,/order/**}
     *       （{@code /ordering}、{@code /order-x} 这种段边界不符的，两个 pattern 都不匹配）；</li>
     *   <li>根 {@code /} → {@code /**}；已含通配符的原样保留。</li>
     * </ul>
     * 正常转发流量由 GatewayProxyWebFilter 按同一份口径短路处理，这里只是 SCG 装载侧的翻译。
     */
    static String toPrefixPatterns(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("路径前缀不能为空");
        }
        String p = prefix.trim();
        if (p.contains("*")) {
            return p;
        }
        if ("/".equals(p)) {
            return "/**";
        }
        if (p.endsWith("/")) {
            // 结尾斜杠承载「仅子树」语义，保留到前缀串上，绝不截掉
            return p + "**";
        }
        return p + "," + p + "/**";
    }

    private FilterDefinition toFilter(GatewayRule rule) {
        String filterName = ACTION_TO_FILTER.get(rule.getType());
        if (filterName == null) {
            throw new IllegalStateException("未知动作类型：" + rule.getType());
        }
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new IllegalStateException("动作缺头名：" + rule.getType());
        }
        String spec = rule.getValue() == null
                ? filterName + "=" + rule.getName()
                : filterName + "=" + rule.getName() + "," + rule.getValue();
        return new FilterDefinition(spec);
    }
}
