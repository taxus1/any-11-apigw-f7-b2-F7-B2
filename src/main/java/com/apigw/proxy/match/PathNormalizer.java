package com.apigw.proxy.match;

/**
 * 路径取值的唯一口径：凡是「拿路径做判定」的地方（真实转发匹配、排查接口匹配、
 * passthrough 判定、规则保存时的前缀归一），都先过这里把路径收敛成同一种规范形式，
 * 再交给 {@link PathPrefixMatcher} 比较。判定口径全网关只有这一份，不存在两处说法。
 *
 * <p>规范形式（只动与「路径结构」有关的写法，不碰业务字符）：
 * <ol>
 *   <li><b>编码的斜杠还原</b>：{@code %2F}/{@code %2f} 一律还原成 {@code /}。
 *       斜杠是路径段的分隔符，是路由边界本身；若保留编码，攻击者可用 {@code /order%2F..%2Fadmin}
 *       这种写法让自己看起来在 {@code /order} 下、实际却指向别的段，用编码绕开路由边界。
 *       还原后它与 {@code /order/../admin} 同形，后面的穿越解析会把真实去向摊开，
 *       编码写法不可能得到与明文不同的判定。</li>
 *   <li><b>编码的点也还原</b>：{@code %2E}/{@code %2e} 还原成 {@code .}。
 *       理由同上：点段（{@code .}/{@code ..}）是路径结构字符，若不解码，
 *       {@code /order%2F..%2Fadmin}、{@code /order/%2e%2e/admin} 就会带着 {@code ..}
 *       的真身蒙混过匹配——必须把「结构字符的编码」和「结构字符本身」按同一个东西处理。</li>
 *   <li><b>连续斜杠合并</b>：{@code //} 多个并成一个。
 *       这是「写法」不是新的路径层级，{@code /order//abc} 与 {@code /order/abc} 必须同结论；
 *       否则排查口子和真实转发只要任一处拿到了多斜杠写法，两边就会对不上。</li>
 *   <li><b>点段穿越解析</b>：按 RFC 3986 remove_dot_segments 的语义解析 {@code .}/{@code ..}，
 *       越出根的 {@code ..} 一律夹断在根（{@code /a/../..} → {@code /}），
 *       任何穿越都不可能逃出「解码后真实路径」落在的那棵子树。</li>
 *   <li><b>结尾斜杠原样保留</b>（根路径仍是 {@code /}）。
 *       结尾斜杠是前缀语义的边界（见 {@link PathPrefixMatcher}）：
 *       规则 {@code /order/} 与 {@code /order} 不是一回事，请求 {@code /order/} 与
 *       {@code /order} 也不是一回事。归一化若把尾斜杠抹掉，边界就在半路上丢了。</li>
 * </ol>
 *
 * <p><b>其余百分号序列一律不解码</b>（如 {@code %20}、{@code %41}）：那些编码的是业务字符
 * 而非路径结构，解码会改动业务语义（{@code /a%20b} 与 {@code /a b} 是两个资源名），
 * 网关只对「分隔符/穿越符」这类会改变路径结构、可用于绕过边界的字符负责，不越界替业务解码。
 *
 * <p>这份归一同时用于两处：
 * <ul>
 *   <li><b>匹配/判定</b>：真实转发匹配、排查接口匹配、各过滤器的 passthrough 边界、
 *       规则保存时的前缀归一，全部先收敛到规范形式再比较；</li>
 *   <li><b>转发路径</b>：发给上游的路径就是规范形式（见 {@code UpstreamForwarder.resolveTargetUri}），
 *       保证「网关按哪条路径放行，上游就收到哪条路径」，编码串不会在后端被再解释出第二个去向。</li>
 * </ul>
 * 访问流水仍照实记录原始收到的路径，便于事后审计「调用方原本是怎么写的」。
 */
public final class PathNormalizer {

    private PathNormalizer() {
    }

    public static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        // 1-2. 结构字符的编码必须还原，编码写法不能绕开路由边界
        String p = decodeStructural(path);
        // 3. 连续斜杠合并（写法归一，不制造新的层级）
        p = collapseSlashes(p);
        // 4. 点段穿越解析（RFC 3986 remove_dot_segments 的栈式实现，越根夹断）
        p = removeDotSegments(p);
        // 5. 结尾斜杠保留：它是前缀边界的一部分，不在这里抹掉
        return p;
    }

    /**
     * 只解码路径结构字符的百分号编码：斜杠 {@code %2F} 与点 {@code %2E}，大小写两种 hex 都认。
     * 手工逐字符处理（不走整串 URLDecoder），因为其它百分号序列必须原样保留给业务。
     */
    static String decodeStructural(String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                char h1 = Character.toLowerCase(path.charAt(i + 1));
                char h2 = Character.toLowerCase(path.charAt(i + 2));
                if (h1 == '2' && h2 == 'f') {
                    out.append('/');
                    i += 2;
                    continue;
                }
                if (h1 == '2' && h2 == 'e') {
                    out.append('.');
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 把两个及以上连续的 {@code /} 合并成一个。 */
    static String collapseSlashes(String path) {
        if (path.indexOf("//") < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder(path.length());
        char prev = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' && prev == '/') {
                continue;
            }
            out.append(c);
            prev = c;
        }
        return out.toString();
    }

    /**
     * RFC 3986 remove_dot_segments 的栈式实现：按 {@code /} 切段，
     * {@code .} 丢弃、{@code ..} 弹一段（根之上不保留），其余段压栈；
     * 结尾是否带斜杠按解析后的输入保留，根路径恒为 {@code /}。
     */
    static String removeDotSegments(String path) {
        boolean absolute = path.startsWith("/");
        boolean trailingSlash = path.length() > 1 && path.endsWith("/");

        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String seg : path.split("/", -1)) {
            switch (seg) {
                case "", "." -> {
                    // 空段来自斜杠切分，点段指当前目录：都不入栈
                }
                case ".." -> {
                    if (!stack.isEmpty()) {
                        stack.removeLast();
                    }
                    // 越出根的 .. 直接丢掉：穿越不可能逃出根
                }
                default -> stack.addLast(seg);
            }
        }

        if (stack.isEmpty()) {
            return absolute || trailingSlash ? "/" : "";
        }
        StringBuilder out = new StringBuilder();
        if (absolute) {
            out.append('/');
        }
        out.append(String.join("/", stack));
        if (trailingSlash) {
            out.append('/');
        }
        return out.toString();
    }
}
