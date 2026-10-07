package com.apigw.proxy.match;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;

/**
 * 一次路由匹配所需的全部请求特征（路径、方法、头、查询参数）。
 *
 * 存在意义：匹配语义只有一份——转发链路从 {@link ServerHttpRequest} 提取，
 * 排查接口（/api/gateway/routes/_explain）从运营手工填的描述构造，
 * 两边最终都归一成这个结构再进 {@link RouteMatcher}，
 * 保证「排查接口算出来的结果」与「线上真实转发」逐字节一致，不存在两套判定。
 *
 * headers 用 {@link HttpHeaders} 装：它本身头名大小写不敏感，与线上取头口径相同；
 * queryParams 认的是 URL 查询串，与请求体无关。
 */
public record MatchInput(String path,
                         String method,
                         HttpHeaders headers,
                         MultiValueMap<String, String> queryParams) {

    /** 转发链路入口：从真实请求提取（method 归一成大写名，空方法按空串）。 */
    public static MatchInput from(ServerHttpRequest request) {
        return new MatchInput(
                // 路径口径与排查口子完全一致：原始（含百分号编码）路径先统一归一，
                // 编码斜杠/连续斜杠/点段在这里收敛，不能让线上用原始路径、排查用归一路径
                PathNormalizer.normalize(request.getPath().pathWithinApplication().value()),
                request.getMethod() == null ? "" : request.getMethod().name(),
                request.getHeaders(),
                request.getQueryParams());
    }

    /** 排查接口入口：从手工填的描述构造（头名大小写不敏感由 HttpHeaders 保证）。 */
    public static MatchInput of(String path, String method,
                                Map<String, String> headers, Map<String, String> query) {
        // 与转发链路同一个入口归一：排查写 /order%2Fabc 与线上真实收到该写法，结论逐字节一致
        String normalizedPath = PathNormalizer.normalize(path);
        HttpHeaders hh = new HttpHeaders();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    hh.add(k, v);
                }
            });
        }
        MultiValueMap<String, String> qp = new LinkedMultiValueMap<>();
        if (query != null) {
            query.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    qp.add(k, v);
                }
            });
        }
        return new MatchInput(normalizedPath, method == null ? "" : method,
                HttpHeaders.readOnlyHttpHeaders(hh), qp);
    }

    /** 取某个头的第一个值（头名大小写不敏感），没有返回 null。 */
    public String firstHeader(String name) {
        return headers == null ? null : headers.getFirst(name);
    }

    /** 取某个查询参数的全部值（参数名大小写敏感），没有返回 null。 */
    public List<String> queryValues(String name) {
        return queryParams == null ? null : queryParams.get(name);
    }
}
