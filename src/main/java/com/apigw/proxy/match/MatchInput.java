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
 * 路径在两个构造入口都过同一遍 {@link PathNormalizer}（编码斜杠/点还原、重复斜杠合并、
 * 穿越解析、尾斜杠保留）：这是「路径口径只有一份」的落点——真实请求与手工描述无论怎么写，
 * 进到这里之后都是同一种规范形式，任何调用方都没法靠绕过工厂方法拿到第二套判定。
 *
 * headers 用 {@link HttpHeaders} 装：它本身头名大小写不敏感，与线上取头口径相同；
 * queryParams 认的是 URL 查询串，与请求体无关。
 */
public record MatchInput(String path,
                         String method,
                         HttpHeaders headers,
                         MultiValueMap<String, String> queryParams) {

    /** 转发链路入口：从真实请求提取（method 归一成大写名，空方法按空串；路径走统一归一）。 */
    public static MatchInput from(ServerHttpRequest request) {
        return new MatchInput(
                PathNormalizer.normalize(request.getPath().pathWithinApplication().value()),
                request.getMethod() == null ? "" : request.getMethod().name(),
                request.getHeaders(),
                request.getQueryParams());
    }

    /** 排查接口入口：从手工填的描述构造（路径走与真实转发同一遍归一；头名大小写不敏感由 HttpHeaders 保证）。 */
    public static MatchInput of(String path, String method,
                                Map<String, String> headers, Map<String, String> query) {
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
        return new MatchInput(PathNormalizer.normalize(path),
                method == null ? "" : method, HttpHeaders.readOnlyHttpHeaders(hh), qp);
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
