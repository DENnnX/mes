package com.edu.scnu.interfaces.rpc.chain;

import com.edu.scnu.application.IamAppService;
import com.edu.scnu.domain.iam.RoleDef;
import com.edu.scnu.domain.metamodel.model.ModelSnapshot;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.MetaContext;
import com.edu.scnu.infrastructure.container.MetaContainerMemoryRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 责任链第 3 环：RBAC 服务级权限验证 + 多租户应用授权守卫。
 */
@Component
@Order(30)
public class PermissionRpcHandler implements RpcHandler {
    private static final Set<String> AUTH_SELF_METHODS = Set.of(
            "rbac_user.profile", "rbac_user.changePassword", "rbac_user.logout",
            "auth.profile", "auth.changePassword", "auth.logout");
    private static final Set<String> ADMIN_ONLY = Set.of(
            "confirmPurge", "rejectPurge");
    private static final Set<String> TENANT_ADMIN_OR_ROOT = Set.of(
            "saveUser", "deleteUser", "resetPassword", "saveTenant", "authorizeApps", "authorizeMenus",
            "saveOrg", "deleteOrg", "saveRole", "deleteRole");
    private static final Set<String> PUBLISHER_REQ = Set.of("publish", "rollback", "offline", "online", "applyPurge");
    private static final Set<String> DEV_REQ = Set.of(
            "saveDraft", "checkImpact", "buildDefaultViews", "importPackage", "saveView", "saveMenu",
            "saveType", "deleteType", "saveItem", "deleteItem", "saveRule", "deleteRule");

    private final MetaContainerMemoryRegistry registry;
    private final IamAppService iamAppService;

    public PermissionRpcHandler(MetaContainerMemoryRegistry registry, IamAppService iamAppService) {
        this.registry = registry;
        this.iamAppService = iamAppService;
    }

    @Override
    public Object handle(RpcRequestContext ctx, Chain next) {
        String fullKey = ctx.getModel() + "." + ctx.getService();
        if (ctx.isAnonymousService()) {
            return next.proceed(ctx);
        }
        if (ctx.getUserAccount() == null) {
            throw DomainException.permissionDenied("请先登录");
        }
        if (AUTH_SELF_METHODS.contains(fullKey)) {
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

        if (isRootAdmin || MetaContext.SUPERUSER.equals(meta.getUserId())) {
            return next.proceed(ctx);
        }

        String svc = ctx.getService();
        if ("meta_app.save".equals(fullKey) && !meta.hasRole("ROLE_DEVELOPER") && !isRootAdmin) {
            throw DomainException.permissionDenied("操作拒绝：应用创建/编辑仅限平台根管理员或开发者执行");
        }
        if (ADMIN_ONLY.contains(svc) && !isRootAdmin) {
            throw DomainException.permissionDenied("操作拒绝：服务 [" + svc + "] 仅限平台根系统管理员 (ROLE_ADMIN) 执行");
        }
        if (TENANT_ADMIN_OR_ROOT.contains(svc)
                && !isRootAdmin
                && !meta.hasRole("ROLE_TENANT_ADMIN")
                && !(("saveOrg".equals(svc) || "deleteOrg".equals(svc)) && meta.hasRole("ROLE_DEVELOPER"))) {
            throw DomainException.permissionDenied("操作拒绝：服务 [" + svc + "] 仅限系统管理员或企业租户主管执行");
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
