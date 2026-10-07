package com.apigw.proxy.match;

/**
 * 路径取值的统一口径：把路径收敛成可比对的形式。
 *
 * <ul>
 *   <li>连续斜杠合并成一个；</li>
 *   <li>结尾斜杠去掉（根路径除外）；</li>
 *   <li>百分号编码的斜杠还原出来（上游有时会把路径编码后再转发）。</li>
 * </ul>
 *
 * 只处理斜杠相关的归一，其余百分号序列原样保留，免得动到业务语义。
 */
public final class PathNormalizer {

    private PathNormalizer() {
    }

    public static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String p = path.replace("%2F", "/").replace("%2f", "/");
        p = p.replaceAll("/{2,}", "/");
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
