package com.edu.scnu.interfaces.rpc.chain;

import com.edu.scnu.application.IamAppService;
import com.edu.scnu.domain.iam.UserAccount;
import com.edu.scnu.domain.shared.DomainException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 责任链第 1 环：统一 Token 身份鉴权（对标 AppAuthRpcHandler / AuthCodeRpcHandler）。
 */
@Component
@Order(10)
public class AuthRpcHandler implements RpcHandler {
    private final IamAppService iamAppService;

    public AuthRpcHandler(IamAppService iamAppService) {
        this.iamAppService = iamAppService;
    }

    @Override
    public Object handle(RpcRequestContext ctx, Chain next) {
        if (ctx.isAnonymousService()) {
            return next.proceed(ctx);
        }
        if (ctx.isOpenEndpoint()) {
            throw DomainException.permissionDenied("此服务需要通过认证接口调用");
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
