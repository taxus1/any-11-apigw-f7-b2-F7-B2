package com.apigw.infrastructure.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 「路径前缀」到 PathPattern 的翻译测试——翻译结果必须与 PathPrefixMatcher 的
 * 尾斜杠边界语义一致，不能在这里出现第二份判定口径：
 * - /order/（带尾斜杠，仅子树）只翻成 /order/**，不包含 /order 自身；
 * - /order（不带尾斜杠，精确 + 子树）翻成 /order,/order/** 两个 pattern；
 * - /ordering、/order-x 这两种段边界不符的，两个 pattern 都不匹配。
 */
class RedisRouteDefinitionRepositoryTest {

    @Test
    void trailingSlash_becomesSubtreeOnly() {
        assertEquals("/order/**", RedisRouteDefinitionRepository.toPrefixPatterns("/order/"));
    }

    @Test
    void noTrailingSlash_becomesExactAndSubtree() {
        assertEquals("/order,/order/**", RedisRouteDefinitionRepository.toPrefixPatterns("/order"));
    }

    @Test
    void root_becomesAll() {
        assertEquals("/**", RedisRouteDefinitionRepository.toPrefixPatterns("/"));
    }

    @Test
    void existingWildcard_keptAsIs() {
        assertEquals("/order/**", RedisRouteDefinitionRepository.toPrefixPatterns("/order/**"));
        assertEquals("/order/*", RedisRouteDefinitionRepository.toPrefixPatterns("/order/*"));
    }

    @Test
    void blank_isRejected() {
        assertThrows(IllegalStateException.class,
                () -> RedisRouteDefinitionRepository.toPrefixPatterns("  "));
    }
}
