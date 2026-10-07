package com.apigw.proxy.match;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 路径取值的<b>唯一口径</b>：把请求路径 / 配置前缀收敛成同一个可比对的形式。
 *
 * <p>转发链路（{@link MatchInput#from}）与排查口子（{@link MatchInput#of}）拿到的路径
 * 都必须先过这里，任何路径在任何入口得到的结论都一致——不存在第二套判定。
 *
 * <p>归一规则（只动与「路径段边界」有关的写法，不触碰查询串）：
 * <ol>
 *   <li><b>百分号编码一次性全量解码</b>（{@code %2F}、{@code %2f}、{@code %2e%2e} 都还原）。
 *       必须还原：斜杠一旦允许以编码形式留在路径里，{@code /order%2F..%2Fadmin} 这种写法
 *       就会被当成 {@code /order} 前缀下的一个「怪文件名」绕过路由边界，而上游按 RFC 3986
 *       解析时看到的却是 {@code /admin}——网关与上游各看各的，边界形同虚设。
 *       只解一遍：{@code %252F} 解码出字面 {@code %2F}，不会被递归还原成 {@code /}，
 *       双重编码的真实含义就是字面 %2F，上游看到什么这里就看到什么；</li>
 *   <li><b>消解点段</b>（RFC 3986 remove_dot_segments）：{@code .} 段丢弃、{@code ..} 段回退一层，
 *       越出根的 {@code ..} 钉在根上（{@code /../etc} → {@code /etc}）。这与上游解析后的真实
 *       资源路径对齐，保证路由边界不能靠点段穿越；该步骤同时把连续斜杠合并成一个
 *       （{@code /order//abc} → {@code /order/abc}）；</li>
 *   <li><b>保留结尾斜杠</b>（根路径 {@code /} 除外）：{@code /order/} 与 {@code /order} 是两种
 *       不同的前缀语义（见 {@link PathPrefixMatcher}），归一不能把它抹平；</li>
 *   <li>路径大小写敏感（RFC 3986 路径部分不做大小写归一），{@code /Order} 与 {@code /order}
 *       是两条路径。</li>
 * </ol>
 *
 * <p>归一结果<b>只用于路由判定</b>：发给上游的仍是请求行里的原始路径与原始查询串
 * （见 {@code UpstreamForwarder.resolveTargetUri}），网关不改写资源路径。
 *
 * <p>使用约束：每个路径只在「进入匹配口径」的入口归一一次；匹配器内部拿到的已是归一结果，
 * 不要二次归一（全量解码后字面 {@code %2F} 再次过解码会产生不同结果，与语义无关，只是没必要）。
 */
public final class PathNormalizer {

    private PathNormalizer() {
    }

    /**
     * 归一<b>请求路径</b>（应用内绝对路径，不含 host 与查询串）。
     * 容错取向：畸形百分号序列按字面保留，绝不因攻击者的怪写法抛异常影响转发；
     * 越界点段钉在根上而不是报错（与上游 RFC 解析结果一致）。
     * {@code null}/空串原样透传（由入口校验负责拦截）。
     */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (!path.startsWith("/")) {
            // 口径只覆盖应用内绝对路径；非绝对路径原样返回（正常入口到不了这里）
            return path;
        }
        return resolveSegments(percentDecode(path));
    }

    /**
     * 归一<b>配置的路径前缀</b>，并在归一前守住前缀的写法底线。与 {@link #normalize} 同一套
     * 段处理逻辑，但配置是人手写的契约，写错要直接拒绝，不能悄悄改成另一条前缀。
     *
     * @throws IllegalArgumentException 前缀为空、不以 {@code /} 开头、混进查询串、或含 {@code ..} 段
     */
    public static String canonicalizePrefix(String rawPrefix) {
        String p = rawPrefix == null ? null : rawPrefix.trim();
        if (p == null || p.isEmpty()) {
            throw new IllegalArgumentException("路径前缀不能为空");
        }
        if (!p.startsWith("/")) {
            throw new IllegalArgumentException(
                    "路径前缀必须以 / 开头（应用内路径，不带 host），收到的是：" + p);
        }
        if (p.indexOf('?') >= 0) {
            throw new IllegalArgumentException(
                    "路径前缀里不能带查询串（? 及之后的部分），前缀只描述路径段，收到的是：" + p);
        }
        String decoded = percentDecode(p);
        for (String seg : decoded.split("/", -1)) {
            if ("..".equals(seg)) {
                throw new IllegalArgumentException(
                        "路径前缀不能含 .. 段（路径穿越不是合法的前缀写法），请直接写最终路径，收到的是：" + p);
            }
        }
        return resolveSegments(decoded);
    }

    /**
     * 段消解：空段（连续斜杠）与 {@code .} 丢弃，{@code ..} 回退一层（空栈时钉在根），
     * 保留结尾斜杠标记。入参保证以 {@code /} 开头。
     */
    private static String resolveSegments(String decoded) {
        boolean trailingSlash = decoded.length() > 1 && decoded.endsWith("/");
        List<String> stack = new ArrayList<>();
        for (String seg : decoded.split("/", -1)) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                // 请求路径允许出现点段：按 RFC 3986 回退，越界即钉根，绝不把它当普通段放行
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(seg);
        }
        if (stack.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : stack) {
            sb.append('/').append(seg);
        }
        if (trailingSlash) {
            sb.append('/');
        }
        return sb.toString();
    }

    /**
     * 百分号解码（只扫一遍，不递归）：合法 {@code %XX}（大小写 hex 均可）按字节还原，
     * 其余字节原样保留；输出按 UTF-8 重组，畸形序列按替换字符处理而不是抛异常。
     * 逐字节处理保证多字节 UTF-8 字符与解码出的字节能正确拼在一起。
     */
    static String percentDecode(String s) {
        byte[] src = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length);
        for (int i = 0; i < src.length; i++) {
            int hi;
            int lo;
            if (src[i] == '%' && i + 2 < src.length
                    && (hi = hex(src[i + 1])) >= 0 && (lo = hex(src[i + 2])) >= 0) {
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                out.write(src[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }
}
