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
                    // 「前缀」翻译成 PathPattern 的 ** 通配：/order 配成 /order/**。
                    // 注意：PathPattern 表达不出「仅子树」语义（/order/** 同时也覆盖 /order 本身），
                    // 真正的转发匹配以 GatewayProxyWebFilter + PathPrefixMatcher 为唯一权威，
                    // 尾斜杠边界（/order/ 不收 /order）在那里守住；这里只给 SCG 装载用，取规范前缀。
                    { return new PredicateDefinition("Path="
                            + toPrefixPattern(com.apigw.proxy.match.PathNormalizer.normalize(rule.getValue()))); }
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
     * 把规范后的「路径前缀」归一成 PathPattern：去尾斜杠后统一追加 /**；已经是通配的保持原样。
     * 入参已过 PathNormalizer（编码斜杠/穿越段已摊平），这里只做通配翻译。
     * 尾斜杠区分语义的权威在转发匹配链路，本翻译表达不出「仅子树」，见 toPredicate 说明。
     */
    static String toPrefixPattern(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("路径前缀不能为空");
        }
        String p = prefix.trim();
        if (p.contains("*")) {
            return p;
        }
        // 仅做通配翻译需要的尾斜杠去除；不改变存储值，也不参与转发边界判定
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.equals("/")) {
            return "/**";
        }
        return p + "/**";
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
