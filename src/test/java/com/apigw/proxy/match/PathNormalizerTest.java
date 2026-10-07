package com.apigw.proxy.match;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 路径唯一口径（{@link PathNormalizer}）的回归测试。
 *
 * 覆盖题目点名的全部异常写法，且这些写法在排查口子与真实转发上结论必须一致——
 * 因为两边都只通过 {@link MatchInput} 的两个工厂入口拿到这里归一后的路径：
 * - 编码斜杠还原（%2F/%2f），且不能借编码绕开路由边界（/order%2F..%2Fadmin）；
 * - 编码的点（%2E）同样还原，穿越段解析后被夹回真实子树；
 * - 重复斜杠合并；结尾斜杠保留（边界不能丢）；根路径恒为 /；
 * - 业务字符的百分号编码（%20 等）不解码，不动业务语义；
 * - 归一是幂等的：多过一遍结果不变。
 */
class PathNormalizerTest {

    // ---- 编码斜杠：必须还原，这是路由边界本身 ----

    @ParameterizedTest
    @CsvSource({
            "/order%2Fabc,/order/abc",
            "/order%2fabc,/order/abc",
            "/order%2F%2Fabc,/order/abc",   // 还原后重复斜杠也要合并
            "/order%2F,/order/",             // 结尾的编码斜杠还原后，尾斜杠保留
            "/%2Forder,/order",
    })
    void encodedSlash_isRestored(String raw, String expected) {
        assertEquals(expected, PathNormalizer.normalize(raw));
    }

    @Test
    void encodedSlash_isIdempotent() {
        // 规范形式再过一遍必须不变（转发与排查都只归一一次，多调一次也不能出第二种结果）
        String once = PathNormalizer.normalize("/order%2Fabc//x%2F");
        assertEquals(once, PathNormalizer.normalize(once));
    }

    // ---- 安全红线：编码写法不能绕开路由边界 ----

    @ParameterizedTest
    @CsvSource({
            // 线上事故写法：看起来在 /order 下，解码+穿越解析后真实去向是 /admin
            "/order%2F..%2Fadmin,/admin",
            "/order/../admin,/admin",
            "/order/%2e%2e/admin,/admin",
            "/order/%2E%2E/admin,/admin",
            // 越出根的穿越被夹断在根，逃不出根
            "/../admin,/admin",
            "/order/../../admin,/admin",
            "/..,/",
            "/../,/",
            // 点段是当前目录，不改变层级
            "/order/./abc,/order/abc",
            "/order/%2e/abc,/order/abc",
            // 组合拳：重复斜杠 + 编码斜杠 + 穿越
            "//order//%2F..%2Fadmin,/admin",
    })
    void traversalAndEncodedTraversal_cannotEscapeBoundary(String raw, String expected) {
        assertEquals(expected, PathNormalizer.normalize(raw));
    }

    // ---- 重复斜杠：写法归一，不制造新层级 ----

    @Test
    void repeatedSlashes_areCollapsed_simple() {
        assertEquals("/order/abc", PathNormalizer.normalize("/order//abc"));
        assertEquals("/order/abc", PathNormalizer.normalize("///order///abc"));
        assertEquals("/", PathNormalizer.normalize("//"));
    }

    // ---- 结尾斜杠：边界的一部分，任何时候都不能抹掉 ----

    @ParameterizedTest
    @CsvSource({
            "/order/,/order/",
            "/order/abc/,/order/abc/",
            "/,/",
    })
    void trailingSlash_isPreserved(String raw, String expected) {
        assertEquals(expected, PathNormalizer.normalize(raw));
    }

    @Test
    void nonTrailingSlashPath_isUnchanged() {
        assertEquals("/order", PathNormalizer.normalize("/order"));
        assertEquals("/order/abc", PathNormalizer.normalize("/order/abc"));
    }

    // ---- 业务字符的编码不解码：不越界替业务改语义 ----

    @Test
    void nonStructuralPercentEncoding_isLeftUntouched() {
        // 空格、字母 A 的编码都与路径结构无关，原样留给上游/业务解释
        assertEquals("/a%20b", PathNormalizer.normalize("/a%20b"));
        assertEquals("/%41bc", PathNormalizer.normalize("/%41bc"));
        // 半截/畸形 % 序列原样保留，绝不抛异常拖垮转发
        assertEquals("/a%2", PathNormalizer.normalize("/a%2"));
        assertEquals("/a%zz", PathNormalizer.normalize("/a%zz"));
        assertEquals("/a%", PathNormalizer.normalize("/a%"));
    }

    // ---- 根路径与空值 ----

    @Test
    void rootAndEmpty() {
        assertEquals("/", PathNormalizer.normalize("/"));
        assertEquals("", PathNormalizer.normalize(""));
        assertEquals(null, PathNormalizer.normalize(null));
    }
}
