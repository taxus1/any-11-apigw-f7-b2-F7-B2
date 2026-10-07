package com.apigw.proxy.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径前缀匹配的边界测试——题目里点名最容易翻车的地方：
 * - /order/ 必须命中 /order/abc（前缀语义）；
 * - /order 绝不能因为「字符串前缀像」命中 /other、/ordering、/order-x；
 * - 尾斜杠区分「仅子树」和「精确路径 + 子树」；
 * - 路径大小写敏感（常规 URL 语义）。
 */
class PathPrefixMatcherTest {

    // ---- 规则以斜杠结尾：只认子树，不认精确路径本身 ----

    @Test
    void trailingSlash_matchesChildPath() {
        assertTrue(PathPrefixMatcher.matches("/order/", "/order/abc"));
        assertTrue(PathPrefixMatcher.matches("/order/", "/order/"));
        assertTrue(PathPrefixMatcher.matches("/order/", "/order/abc/def"));
    }

    @Test
    void trailingSlash_doesNotMatchExactSegment() {
        // /order 不是 /order/ 的「下面」，不能命中
        assertFalse(PathPrefixMatcher.matches("/order/", "/order"));
    }

    // ---- 规则不以斜杠结尾：精确路径 + 段边界子树 ----

    @Test
    void noTrailingSlash_matchesExactAndChild() {
        assertTrue(PathPrefixMatcher.matches("/order", "/order"));
        assertTrue(PathPrefixMatcher.matches("/order", "/order/"));
        assertTrue(PathPrefixMatcher.matches("/order", "/order/abc"));
    }

    @Test
    void noTrailingSlash_rejectsStringPrefixLookalikes() {
        // 题目红线：规则 /order、请求 /other 绝不命中；/ordering、/order-x 同理
        assertFalse(PathPrefixMatcher.matches("/order", "/other"));
        assertFalse(PathPrefixMatcher.matches("/order", "/ordering"));
        assertFalse(PathPrefixMatcher.matches("/order", "/order-x"));
        assertFalse(PathPrefixMatcher.matches("/order", "/orders/123"));
    }

    @Test
    void rootPrefix_matchesEverything() {
        // / 以斜杠结尾，startsWith("/") 对所有绝对路径成立
        assertTrue(PathPrefixMatcher.matches("/", "/anything"));
        assertTrue(PathPrefixMatcher.matches("/", "/"));
    }

    @Test
    void nestedPrefix_requiresFullPrefix() {
        assertTrue(PathPrefixMatcher.matches("/api/v1/", "/api/v1/users"));
        assertFalse(PathPrefixMatcher.matches("/api/v1/", "/api/v2/users"));
        assertFalse(PathPrefixMatcher.matches("/api/v1/", "/api/v10/users"));
    }

    @Test
    void pathIsCaseSensitive() {
        // 常规 URL 语义：路径大小写敏感
        assertFalse(PathPrefixMatcher.matches("/order", "/ORDER/abc"));
        assertFalse(PathPrefixMatcher.matches("/order/", "/Order/abc"));
    }

    @Test
    void blankOrNullInputs_neverMatch() {
        assertFalse(PathPrefixMatcher.matches(null, "/order"));
        assertFalse(PathPrefixMatcher.matches("", "/order"));
        assertFalse(PathPrefixMatcher.matches("/order", null));
    }

    // ---- 尾斜杠边界在「规则」侧也不能被写法抹平（保存口径回归） ----

    @Test
    void trailingSlashRule_neverEquivalentToNonTrailingSlashRule() {
        // 同一条请求 /order：无尾斜杠规则收（精确路径），有尾斜杠规则不收（仅子树）
        assertTrue(PathPrefixMatcher.matches("/order", "/order"));
        assertFalse(PathPrefixMatcher.matches("/order/", "/order"));
        // /order/ 反过来：两条都收子树请求，但有尾斜杠规则不额外收精确路径
        assertTrue(PathPrefixMatcher.matches("/order", "/order/"));
        assertTrue(PathPrefixMatcher.matches("/order/", "/order/"));
    }

    @Test
    void slashRule_boundaryAgainstSiblingSegments() {
        // 段边界红线：任何「只是字符串前缀像」的兄弟段都不能进来，两种规则写法都一样
        for (String rule : new String[]{"/order", "/order/"}) {
            assertFalse(PathPrefixMatcher.matches(rule, "/ordering"));
            assertFalse(PathPrefixMatcher.matches(rule, "/order-x"));
            assertFalse(PathPrefixMatcher.matches(rule, "/orders/1"));
        }
    }
}
