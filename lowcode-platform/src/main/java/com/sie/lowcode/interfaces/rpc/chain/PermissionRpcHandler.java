package com.sie.lowcode.interfaces.rpc.chain;

import com.sie.lowcode.application.IamAppService;
import com.sie.lowcode.domain.iam.RoleDef;
import com.sie.lowcode.domain.metamodel.model.ModelSnapshot;
import com.sie.lowcode.domain.shared.DomainException;
import com.sie.lowcode.domain.shared.MetaContext;
import com.sie.lowcode.infrastructure.container.MetaContainerMemoryRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 责任链第 3 环：RBAC 服务级权限验证 + 多租户应用授权守卫。
 */
@Component
@Order(30)
public class PermissionRpcHandler implements RpcHandler {
    private static final Set<String> OPEN_METHODS = Set.of(
            "rbac_user.login", "rbac_user.logout", "auth.login", "sys_portal.publicApps");
    private static final Set<String> ADMIN_ONLY = Set.of(
            "confirmPurge", "rejectPurge", "saveUser", "deleteUser", "resetPassword",
            "saveRole", "deleteRole", "saveTenant", "authorizeApps");
    private static final Set<String> PUBLISHER_REQ = Set.of("publish", "rollback", "offline", "online", "applyPurge");
    private static final Set<String> DEV_REQ = Set.of(
            "saveDraft", "checkImpact", "buildDefaultViews", "importPackage", "saveView", "saveMenu",
            "saveType", "deleteType", "saveItem", "deleteItem", "saveRule", "deleteRule", "saveOrg", "deleteOrg");

    private final MetaContainerMemoryRegistry registry;
    private final IamAppService iamAppService;

    public PermissionRpcHandler(MetaContainerMemoryRegistry registry, IamAppService iamAppService) {
        this.registry = registry;
        this.iamAppService = iamAppService;
    }

    @Override
    public Object handle(RpcRequestContext ctx, Chain next) {
        String fullKey = ctx.getModel() + "." + ctx.getService();
        if (ctx.isOpenEndpoint() || OPEN_METHODS.contains(fullKey)) {
            return next.proceed(ctx);
        }

        MetaContext meta = ctx.getMetaContext();
        boolean isRootAdmin = MetaContext.DEFAULT_TENANT.equals(meta.getTenantId()) && meta.isSuperUser();

        // 多租户应用授权守卫：非平台根管理员访问业务模型时，强制校验所属租户是否已获该应用授权
        ModelSnapshot snap = registry.getActiveSnapshotOrNull(ctx.getModel());
        if (!isRootAdmin && snap != null && snap.getAppName() != null
                && !"platform_kernel".equals(snap.getAppName())
                && !iamAppService.isAppAuthorizedForTenant(meta.getTenantId(), snap.getAppName())) {
            throw DomainException.permissionDenied(
                    "访问拒绝：当前租户未被授权使用应用 [" + snap.getAppName() + "]，请联系系统管理员授权");
        }

        if (isRootAdmin || meta.isSuperUser()) {
            return next.proceed(ctx);
        }

        String svc = ctx.getService();
        if (ADMIN_ONLY.contains(svc) && !meta.hasRole("ROLE_ADMIN")) {
            throw DomainException.permissionDenied("操作拒绝：服务 [" + svc + "] 仅限平台管理员 (ROLE_ADMIN) 执行");
        }
        if (PUBLISHER_REQ.contains(svc) && !meta.hasRole("ROLE_PUBLISHER") && !meta.hasRole("ROLE_ADMIN")) {
            throw DomainException.permissionDenied("操作拒绝：服务 [" + svc + "] 需要发布管理员 (ROLE_PUBLISHER) 权限");
        }
        if (DEV_REQ.contains(svc) && !meta.hasRole("ROLE_DEVELOPER") && !meta.hasRole("ROLE_PUBLISHER") && !meta.hasRole("ROLE_ADMIN")) {
            throw DomainException.permissionDenied("操作拒绝：服务 [" + svc + "] 需要开发者 (ROLE_DEVELOPER) 权限");
        }

        // 检查模型自定义服务 auth 标识是否在角色允许集合内
        String requiredAuth = svc;
        if (snap != null && snap.getService(svc) != null) {
            requiredAuth = snap.getService(svc).getAuth();
        }
        boolean allowed = false;
        for (RoleDef r : ctx.getRoles()) {
            if (matchesAny(r.getAllowedServices(), ctx.getModel(), requiredAuth)
                    || matchesAny(r.getAllowedServices(), ctx.getModel(), svc)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw DomainException.permissionDenied("无权调用服务: " + fullKey + " (auth=" + requiredAuth + ")");
        }
        return next.proceed(ctx);
    }

    private boolean matchesAny(Set<String> perms, String model, String service) {
        for (String p : perms) {
            if ("*".equals(p) || "*:*".equals(p)) return true;
            if ((model + ":" + service).equals(p) || (model + ":*").equals(p) || ("*:" + service).equals(p)) return true;
            if (p.endsWith("*:*") && model.startsWith(p.substring(0, p.length() - 3))) return true;
        }
        return false;
    }
}
