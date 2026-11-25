package com.sie.lowcode.interfaces.rpc.chain;

import com.sie.lowcode.application.IamAppService;
import com.sie.lowcode.domain.iam.RoleDef;
import com.sie.lowcode.domain.iam.UserAccount;
import com.sie.lowcode.domain.shared.MetaContext;
import com.sie.lowcode.infrastructure.container.MetaContainerMemoryRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 责任链第 2 环：构建绑定当前请求线程的 MetaContext 执行环境（对标 BuildMetaRpcHandler）。
 */
@Component
@Order(20)
public class BuildMetaRpcHandler implements RpcHandler {
    private final MetaContainerMemoryRegistry registry;
    private final IamAppService iamAppService;

    public BuildMetaRpcHandler(MetaContainerMemoryRegistry registry, IamAppService iamAppService) {
        this.registry = registry;
        this.iamAppService = iamAppService;
    }

    @Override
    public Object handle(RpcRequestContext ctx, Chain next) {
        UserAccount user = ctx.getUserAccount();
        String uid = user != null ? user.getId() : MetaContext.SUPERUSER;
        String tenant = user != null && user.getTenantId() != null ? user.getTenantId() : ctx.getTenantId();

        try (MetaContext meta = registry.newContext(tenant, uid)) {
            if (user != null) {
                meta.setUsername(user.getUsername());
                meta.setDisplayName(user.getDisplayName());
                meta.setRoleCodes(user.getRoleCodes());
                // 若任一角色具备 ALL 范围则赋予 ALL，否则回落 SELF
                boolean hasAllScope = ctx.getRoles().stream().anyMatch(r -> "ALL".equalsIgnoreCase(r.getDataScope()));
                meta.setDataScope(hasAllScope || ctx.getRoles().isEmpty() ? "ALL" : "SELF");
                // 分级企业管理员 (ROLE_TENANT_ADMIN) 可查看本级及下属分站数据，但严格隔离根系统及其他无关租户
                if (user.getRoleCodes().contains("ROLE_TENANT_ADMIN")
                        && !(MetaContext.DEFAULT_TENANT.equals(tenant) && user.getRoleCodes().contains("ROLE_ADMIN"))) {
                    Set<String> managed = iamAppService.resolveManagedTenantIds(user);
                    if (managed != null && !managed.isEmpty()) {
                        meta.setVisibleTenantIds(managed);
                    }
                }
            }
            if (ctx.getClientContext().get("lang") != null) {
                meta.setLocale(ctx.getClientContext().get("lang").toString());
            }
            if (ctx.getClientContext().get("timeZone") != null) {
                meta.setTimeZone(ctx.getClientContext().get("timeZone").toString());
            }
            meta.getContext().putAll(ctx.getClientContext());
            ctx.setMetaContext(meta);
            return next.proceed(ctx);
        }
    }
}
