package com.apigw.proxy.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 路径归一的唯一口径测试——排查口子与真实转发对同一写法必须拿到同一个结果，
 * 所以这里把题目点名的各类异常写法逐个钉死：
 * 编码斜杠、双重编码、点段穿越、重复斜杠、结尾斜杠、根路径、查询串不属于路径。
 */
class PathNormalizerTest {

    // ---- 编码斜杠：必须还原，编码写法不能变成另一条路径 ----

    @Test
    void encodedSlash_isRestored() {
        assertEquals("/order/abc", PathNormalizer.normalize("/order%2Fabc"));
        assertEquals("/order/abc", PathNormalizer.normalize("/order%2fabc"));
        // 与未编码写法结论一致：这是「同一条路径的不同写法」
        assertEquals(PathNormalizer.normalize("/order/abc"),
                PathNormalizer.normalize("/order%2Fabc"));
    }

    @Test
    void doubleEncodedSlash_decodedOnlyOnce_staysLiteral() {
        // %252F 只解一遍 → 字面 %2F（双重编码的真实含义），不能递归还原成 /
        assertEquals("/order%2Fabc", PathNormalizer.normalize("/order%252Fabc"));
    }

    @Test
    void malformedPercentEscape_isKeptLiteral_notThrowing() {
        // 攻击者的怪写法不能让网关抛异常：畸形序列按字面留下
        assertEquals("/order%2", PathNormalizer.normalize("/order%2"));
        assertEquals("/a%zz/b", PathNormalizer.normalize("/a%zz/b"));
    }

    @Test
    void encodedDotSegments_areResolved_beforeBoundaryMatching() {
        // 安全红线：编码写法不能绕开路由边界。
        // /order%2F..%2Fadmin 先解码成 /order/../admin，再点段消解 → /admin
        assertEquals("/admin", PathNormalizer.normalize("/order%2F..%2Fadmin"));
        assertEquals("/admin", PathNormalizer.normalize("/order/../admin"));
        // 越出根的 .. 钉在根上，不产生负数段、不把 .. 当普通段放行
        assertEquals("/etc", PathNormalizer.normalize("/../etc"));
        assertEquals("/etc", PathNormalizer.normalize("/../../etc"));
        // . 段丢弃
        assertEquals("/order/abc", PathNormalizer.normalize("/order/./abc"));
    }

    // ---- 重复斜杠：合并 ----

    @Test
    void duplicateSlashes_areCollapsed() {
        assertEquals("/order/abc", PathNormalizer.normalize("/order//abc"));
        assertEquals("/order/abc", PathNormalizer.normalize("//order///abc"));
        assertEquals("/", PathNormalizer.normalize("///"));
    }

    // ---- 结尾斜杠：必须保留（承载前缀语义），根路径除外 ----

    @Test
    void trailingSlash_isPreserved() {
        assertEquals("/order/", PathNormalizer.normalize("/order/"));
        assertEquals("/order/abc/", PathNormalizer.normalize("/order/abc/"));
    }

    @Test
    void root_isAlwaysSingleSlash() {
        assertEquals("/", PathNormalizer.normalize("/"));
    }

    // ---- 一致性：同一路径的不同写法归一后完全相等（排查 vs 转发） ----

    @Test
    void equivalentWritings_converge() {
        String canonical = "/order/abc";
        assertEquals(canonical, PathNormalizer.normalize("/order/abc"));
        assertEquals(canonical, PathNormalizer.normalize("/order%2Fabc"));
        assertEquals(canonical, PathNormalizer.normalize("/order//abc"));
        assertEquals(canonical, PathNormalizer.normalize("/order/./abc"));
        assertEquals(canonical, PathNormalizer.normalize("/order/x/../abc"));

        // 结尾斜杠是不同的路径形式，不能被抹平
        assertEquals("/order/abc/", PathNormalizer.normalize("/order%2Fabc%2F"));
    }

    @Test
    void nullAndEmpty_passThrough() {
        assertEquals(null, PathNormalizer.normalize(null));
        assertEquals("", PathNormalizer.normalize(""));
    }

    @Test
    void nonAbsolutePath_isLeftUntouched_entryValidationOwnsThat() {
        // 归一只覆盖应用内绝对路径；不以 / 开头的由入口校验拦，这里不擅自改写
        assertEquals("order/abc", PathNormalizer.normalize("order/abc"));
    }

    // ---- 配置前缀的归一：同一套段口径 + 写死的契约校验，尾斜杠保留 ----

    @Test
    void canonicalizePrefix_preservesTrailingSlash_andCanonicalizesWriting() {
        assertEquals("/order/", PathNormalizer.canonicalizePrefix("/order/"));
        assertEquals("/order", PathNormalizer.canonicalizePrefix("/order"));
        assertEquals("/order/", PathNormalizer.canonicalizePrefix("/order//"));
        assertEquals("/order/abc/", PathNormalizer.canonicalizePrefix("/order%2Fabc%2F"));
        assertEquals("/", PathNormalizer.canonicalizePrefix("/"));
    }

    @Test
    void canonicalizePrefix_rejectsIllegalWriters() {
        assertThrows(IllegalArgumentException.class,
                () -> PathNormalizer.canonicalizePrefix("   "));
        assertThrows(IllegalArgumentException.class,
                () -> PathNormalizer.canonicalizePrefix("order/"));
        assertThrows(IllegalArgumentException.class,
                () -> PathNormalizer.canonicalizePrefix("/order?x=1"));
        assertThrows(IllegalArgumentException.class,
                () -> PathNormalizer.canonicalizePrefix("/order/../admin"));
        // 编码后的 .. 一样拒绝（不能靠编码绕开校验）
        assertThrows(IllegalArgumentException.class,
                () -> PathNormalizer.canonicalizePrefix("/order/%2e%2e/admin"));
    }
}
