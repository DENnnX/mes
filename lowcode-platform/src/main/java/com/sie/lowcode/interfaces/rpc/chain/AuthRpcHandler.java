package com.sie.lowcode.interfaces.rpc.chain;

import com.sie.lowcode.application.IamAppService;
import com.sie.lowcode.domain.iam.UserAccount;
import com.sie.lowcode.domain.shared.DomainException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 责任链第 1 环：统一 Token 身份鉴权（对标 AppAuthRpcHandler / AuthCodeRpcHandler）。
 */
@Component
@Order(10)
public class AuthRpcHandler implements RpcHandler {
    private static final Set<String> WHITELIST = Set.of(
            "rbac_user.login",
            "rbac_user.logout",
            "auth.login",
            "sys_portal.publicApps"
    );

    private final IamAppService iamAppService;

    public AuthRpcHandler(IamAppService iamAppService) {
        this.iamAppService = iamAppService;
    }

    @Override
    public Object handle(RpcRequestContext ctx, Chain next) {
        String key = ctx.getModel() + "." + ctx.getService();
        if (ctx.isOpenEndpoint() || WHITELIST.contains(key)) {
            return next.proceed(ctx);
        }

        String token = ctx.getAuthorizationHeader();
        if ((token == null || token.isBlank()) && ctx.getClientContext().get("token") != null) {
            token = ctx.getClientContext().get("token").toString();
        }

        if (token != null && !token.isBlank()) {
            UserAccount user = iamAppService.authenticate(token)
                    .orElseThrow(() -> DomainException.permissionDenied("登录凭据无效、已过期或账号已停用"));
            ctx.setUserAccount(user);
            ctx.setRoles(iamAppService.resolveRoles(user.getRoleCodes()));
            return next.proceed(ctx);
        }

        throw DomainException.permissionDenied("未授权访问：请先登录获取 Bearer Token (调用 rbac_user.login)");
    }
}
