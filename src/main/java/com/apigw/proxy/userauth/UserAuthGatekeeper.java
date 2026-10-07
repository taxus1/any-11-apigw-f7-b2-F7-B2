package com.apigw.proxy.userauth;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.userauth.GatewayPassSigner;
import com.apigw.domain.userauth.UserIdentity;
import com.apigw.domain.userauth.UserTokenVerifier;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * 用户登录鉴权的守门人：转发过滤器在匹配到路由之后，拿它做两件事——
 *
 * 1. <b>验令牌</b>：从 {@code Authorization: Bearer <token>} 取令牌，交给
 *    {@link UserTokenVerifier} 真验签。受保护路由（authRequired=1）验不过就 401；
 *    开放路由上令牌只是「可选的身份补充」——带对了照常透传身份，没带/带错按匿名放行，
 *    绝不拦（统一口径，理由见 README「开放路由上的坏令牌」）。
 *
 * 2. <b>组装出站身份</b>：验出的身份 + 网关自盖的通行标记，打包成 {@link OutboundAuth}
 *    交给转发器写向上游。身份头只可能来自这里，调用方在入站塞的同名头转发器会先清掉。
 *
 * 两个协作者都可为空（取决于 {@code apigw.user-auth.*} 是否配了密钥）：
 * 没配验签密钥时受保护路由 fail-closed（由过滤器判 503），开放路由不受影响。
 */
public class UserAuthGatekeeper {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserTokenVerifier verifier;
    private final GatewayPassSigner passSigner;

    public UserAuthGatekeeper(UserTokenVerifier verifier, GatewayPassSigner passSigner) {
        this.verifier = verifier;
        this.passSigner = passSigner;
    }

    /** 是否配了验签密钥（没配时受保护路由必须 fail-closed，不能裸放行）。 */
    public boolean tokenVerificationEnabled() {
        return verifier != null;
    }

    /**
     * 从请求里取 Bearer 令牌：scheme 大小写不敏感（RFC 7235），
     * 没带、不是 Bearer、令牌段空白都返回 null（调用方按「没带令牌」处理）。
     */
    public String extractBearerToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(GatewayHeaders.AUTHORIZATION_HEADER);
        if (header == null) {
            return null;
        }
        String v = header.trim();
        if (v.length() <= BEARER_PREFIX.length()
                || !v.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = v.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 真验签：签名、过期、必备声明逐项过（见 {@link UserTokenVerifier}）。 */
    public UserTokenVerifier.Result verify(String token) {
        return verifier.verify(token);
    }

    /**
     * 开放路由专用：带了令牌就试着验，验过给身份；没带或验不过一律当匿名（返回 null），
     * 开放路由绝不因为令牌问题拦人。
     */
    public UserIdentity tryVerifyIdentity(ServerHttpRequest request) {
        if (verifier == null) {
            return null;
        }
        String token = extractBearerToken(request);
        if (token == null) {
            return null;
        }
        UserTokenVerifier.Result result = verifier.verify(token);
        return result.ok() ? result.identity() : null;
    }

    /**
     * 组装发往上游的身份上下文。通行标记把 traceId/方法/路径/身份和时间戳一起签进去，
     * 上游用共享密钥验得出「确实过了网关、且身份头没被换过」。
     *
     * 签进通行标记的路径是转发过滤器传入的<b>规范路径</b>：与实际转发给上游、
     * 以及路由匹配依据的路径是同一个，上游验签时拿它收到的规范路径比对即可，
     * 不会出现「签的是编码串、收到的是摊平串」而验不过。
     */
    public OutboundAuth outbound(String traceId, ServerHttpRequest request, UserIdentity identity,
                                 String canonicalPath) {
        String pass = null;
        if (passSigner != null) {
            String method = request.getMethod() == null ? "" : request.getMethod().name();
            pass = passSigner.sign(System.currentTimeMillis(), traceId, method, canonicalPath,
                    identity == null ? "" : identity.userId(),
                    identity == null ? "" : identity.tenantId());
        }
        // 用户鉴权启用时，原始令牌（Authorization）不递上游
        return new OutboundAuth(identity, pass, verifier != null);
    }
}
