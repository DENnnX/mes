package com.sie.lowcode.application;

import com.sie.lowcode.domain.iam.*;
import com.sie.lowcode.domain.metamodel.model.AppAggregate;
import com.sie.lowcode.domain.metamodel.repository.AppRepository;
import com.sie.lowcode.domain.shared.DomainException;
import com.sie.lowcode.domain.shared.MetaContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class IamAppService {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final IamRepository iamRepository;
    private final AppRepository appRepository;

    public IamAppService(IamRepository iamRepository, AppRepository appRepository) {
        this.iamRepository = iamRepository;
        this.appRepository = appRepository;
    }

    public Map<String, Object> login(String username, String password) {
        return login(username, password, null);
    }

    public Map<String, Object> login(String username, String password, String targetApp) {
        if (username == null || username.isBlank() || password == null) {
            throw DomainException.invalidParams("账号和密码不能为空");
        }
        UserAccount user = iamRepository.findUserByUsername(username.trim())
                .orElseThrow(() -> DomainException.permissionDenied("账号或密码错误"));
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw DomainException.permissionDenied("账号已停用，请联系系统管理员");
        }
        if (!Objects.equals(password, user.getPassword())) {
            throw DomainException.permissionDenied("账号或密码错误");
        }

        String tenantId = (user.getTenantId() == null || user.getTenantId().isBlank())
                ? MetaContext.DEFAULT_TENANT : user.getTenantId();
        TenantDef tenant = findTenantById(tenantId).orElse(null);
        if (tenant != null && !"ACTIVE".equalsIgnoreCase(tenant.getStatus())) {
            throw DomainException.permissionDenied("所属租户 [" + tenant.getName() + "] 已停用，禁止登录");
        }

        boolean isRootAdmin = MetaContext.DEFAULT_TENANT.equals(tenantId) && user.getRoleCodes().contains("ROLE_ADMIN");
        boolean canAccessStudio = MetaContext.DEFAULT_TENANT.equals(tenantId)
                && (user.getRoleCodes().contains("ROLE_ADMIN")
                || user.getRoleCodes().contains("ROLE_DEVELOPER")
                || user.getRoleCodes().contains("ROLE_PUBLISHER"));

        Set<String> allowedAppNames = isRootAdmin ? null : resolveAllowedAppNames(tenantId);
        List<Map<String, Object>> authorizedAppsDetail = buildAuthorizedAppList(allowedAppNames);

        if (targetApp != null && !targetApp.isBlank()) {
            String trimmedTarget = targetApp.trim();
            if ("__studio__".equals(trimmedTarget) || "platform_kernel".equals(trimmedTarget)) {
                if (!canAccessStudio) {
                    throw DomainException.permissionDenied("登录拒绝：当前租户账号无权进入管理后台，请选择已授权的业务应用");
                }
            } else {
                if (appRepository.findByName(trimmedTarget).isEmpty()) {
                    throw DomainException.invalidParams("指定登录的应用不存在: " + trimmedTarget);
                }
                if (allowedAppNames != null && !allowedAppNames.contains(trimmedTarget)) {
                    String tName = tenant != null ? tenant.getName() : tenantId;
                    throw DomainException.permissionDenied(
                            "登录拒绝：租户 [" + tName + "] 未被授权登录应用 [" + trimmedTarget + "]，请联系系统管理员授权！");
                }
            }
        } else if (!canAccessStudio && allowedAppNames != null && allowedAppNames.isEmpty()) {
            String tName = tenant != null ? tenant.getName() : tenantId;
            throw DomainException.permissionDenied(
                    "登录拒绝：租户 [" + tName + "] 尚未获得任何应用授权，请联系系统管理员开通应用授权！");
        }

        String token = "lcp-" + UUID.randomUUID().toString().replace("-", "");
        String expiresAt = TS_FMT.format(LocalDateTime.now().plusHours(24));
        iamRepository.saveSession(token, user.getId(), expiresAt);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("expiresAt", expiresAt);
        result.put("user", buildProfileData(user));
        result.put("tenant", tenant);
        result.put("authorizedApps", authorizedAppsDetail);
        result.put("canAccessStudio", canAccessStudio);
        result.put("isSystemAdmin", isRootAdmin);
        if (targetApp != null && !targetApp.isBlank()) {
            result.put("targetApp", targetApp.trim());
        }
        return result;
    }

    public Map<String, Object> buildProfileData(UserAccount user) {
        if (user == null) return Collections.emptyMap();
        String tenantId = (user.getTenantId() == null || user.getTenantId().isBlank())
                ? MetaContext.DEFAULT_TENANT : user.getTenantId();
        TenantDef tenant = findTenantById(tenantId).orElse(null);
        boolean isRootAdmin = MetaContext.DEFAULT_TENANT.equals(tenantId) && user.getRoleCodes().contains("ROLE_ADMIN");
        boolean canAccessStudio = MetaContext.DEFAULT_TENANT.equals(tenantId)
                && (user.getRoleCodes().contains("ROLE_ADMIN")
                || user.getRoleCodes().contains("ROLE_DEVELOPER")
                || user.getRoleCodes().contains("ROLE_PUBLISHER"));
        Set<String> allowed = isRootAdmin ? null : resolveAllowedAppNames(tenantId);

        Map<String, Object> profile = sanitizeUser(user);
        profile.put("tenant", tenant);
        profile.put("tenantName", tenant != null ? tenant.getName() : tenantId);
        profile.put("authorizedApps", buildAuthorizedAppList(allowed));
        profile.put("canAccessStudio", canAccessStudio);
        profile.put("isSystemAdmin", isRootAdmin);
        return profile;
    }

    public List<Map<String, Object>> listPublicApps() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AppAggregate app : appRepository.findAll()) {
            if ("platform_kernel".equals(app.getName())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", app.getName());
            m.put("displayName", app.getDisplayName());
            m.put("category", app.getCategory());
            m.put("version", app.getVersion());
            m.put("description", app.getDescription());
            list.add(m);
        }
        return list;
    }

    /**
     * 解析租户被授权访问的应用名称集合：
     * - 返回 null 表示不受限制（默认根租户或授权清单包含 "*"）；
     * - 其他租户严格返回显式授权清单（空列表代表 0 个应用可用，不可绕过）。
     */
    public Set<String> resolveAllowedAppNames(String tenantId) {
        String tid = (tenantId == null || tenantId.isBlank()) ? MetaContext.DEFAULT_TENANT : tenantId;
        Optional<TenantDef> tOpt = findTenantById(tid);
        if (MetaContext.DEFAULT_TENANT.equals(tid)) {
            if (tOpt.isEmpty() || tOpt.get().getAuthorizedApps().isEmpty() || tOpt.get().getAuthorizedApps().contains("*")) {
                return null;
            }
            return new LinkedHashSet<>(tOpt.get().getAuthorizedApps());
        }
        if (tOpt.isEmpty()) return Collections.emptySet();
        List<String> apps = tOpt.get().getAuthorizedApps();
        if (apps != null && apps.contains("*")) return null;
        return apps != null ? new LinkedHashSet<>(apps) : Collections.emptySet();
    }

    public boolean isAppAuthorizedForTenant(String tenantId, String appName) {
        if (appName == null || appName.isBlank() || "platform_kernel".equals(appName)) {
            return true;
        }
        Set<String> allowed = resolveAllowedAppNames(tenantId);
        return allowed == null || allowed.contains(appName);
    }

    private List<Map<String, Object>> buildAuthorizedAppList(Set<String> allowedAppNames) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AppAggregate app : appRepository.findAll()) {
            if ("platform_kernel".equals(app.getName())) continue;
            if (allowedAppNames == null || allowedAppNames.contains(app.getName())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", app.getName());
                m.put("displayName", app.getDisplayName());
                m.put("category", app.getCategory());
                m.put("version", app.getVersion());
                m.put("description", app.getDescription());
                list.add(m);
            }
        }
        return list;
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            iamRepository.deleteSession(token);
        }
    }

    public Optional<UserAccount> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String raw = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
        Optional<String> uidOpt = iamRepository.findUserIdByToken(raw);
        if (uidOpt.isEmpty()) return Optional.empty();
        return iamRepository.findUserById(uidOpt.get())
                .filter(u -> "ACTIVE".equalsIgnoreCase(u.getStatus()));
    }

    public Optional<UserAccount> findUserByUsername(String username) {
        return iamRepository.findUserByUsername(username);
    }

    public List<RoleDef> resolveRoles(Set<String> roleCodes) {
        return iamRepository.findRolesByCodes(roleCodes);
    }

    public List<Map<String, Object>> listUsers() {
        return listUsers(null);
    }

    public List<Map<String, Object>> listUsers(String tenantId) {
        List<Map<String, Object>> res = new ArrayList<>();
        List<UserAccount> users = (tenantId != null && !tenantId.isBlank())
                ? iamRepository.findUsersByTenant(tenantId)
                : iamRepository.findAllUsers();
        for (UserAccount u : users) {
            res.add(sanitizeUser(u));
        }
        return res;
    }

    public Map<String, Object> saveUser(UserAccount account) {
        if (account.getUsername() == null || account.getUsername().isBlank()) {
            throw DomainException.invalidParams("用户名不能为空");
        }
        Optional<UserAccount> existing = Optional.empty();
        if (account.getId() != null && !account.getId().isBlank()) {
            existing = iamRepository.findUserById(account.getId());
        }
        if (existing.isEmpty()) {
            existing = iamRepository.findUserByUsername(account.getUsername().trim());
        }
        if (account.getId() == null || account.getId().isBlank()) {
            account.setId(existing.map(UserAccount::getId)
                    .orElse("usr_" + UUID.randomUUID().toString().substring(0, 8)));
        }
        if (account.getPassword() == null || account.getPassword().isBlank()) {
            account.setPassword(existing.map(UserAccount::getPassword).orElse("123456"));
        }
        if (account.getDisplayName() == null || account.getDisplayName().isBlank()) {
            account.setDisplayName(account.getUsername());
        }
        if (account.getTenantId() == null || account.getTenantId().isBlank()) {
            account.setTenantId("rbac_tenant_root");
        }
        if (account.getOrgId() == null || account.getOrgId().isBlank()) {
            account.setOrgId("org_root");
        }
        iamRepository.saveUser(account);
        return sanitizeUser(account);
    }

    public Map<String, Object> resetPassword(String userId, String newPassword) {
        if (userId == null || userId.isBlank() || newPassword == null || newPassword.isBlank()) {
            throw DomainException.invalidParams("重置密码必须传入 userId 和 newPassword");
        }
        UserAccount u = iamRepository.findUserById(userId)
                .orElseThrow(() -> DomainException.invalidParams("用户不存在: " + userId));
        u.setPassword(newPassword);
        iamRepository.saveUser(u);
        return Map.of("reset", true, "userId", userId, "username", u.getUsername());
    }

    public Map<String, Object> changePassword(UserAccount current, String oldPassword, String newPassword) {
        if (current == null) {
            throw DomainException.permissionDenied("请先登录后再修改密码");
        }
        if (oldPassword == null || newPassword == null || newPassword.isBlank()) {
            throw DomainException.invalidParams("原密码和新密码不能为空");
        }
        UserAccount latest = iamRepository.findUserById(current.getId())
                .orElseThrow(() -> DomainException.invalidParams("用户不存在"));
        if (!Objects.equals(oldPassword, latest.getPassword())) {
            throw DomainException.invalidParams("原密码输入不正确");
        }
        latest.setPassword(newPassword);
        iamRepository.saveUser(latest);
        return Map.of("changed", true, "username", latest.getUsername());
    }

    public Map<String, Object> deleteUser(String userId) {
        if ("usr_admin".equals(userId)) {
            throw DomainException.stateGuardFailed("系统默认主管理员账号禁止删除");
        }
        iamRepository.deleteUser(userId);
        return Map.of("deleted", true, "userId", userId);
    }

    public List<RoleDef> listRoles() {
        return iamRepository.findAllRoles();
    }

    public RoleDef saveRole(RoleDef role) {
        if (role.getCode() == null || role.getCode().isBlank()) {
            throw DomainException.invalidParams("角色编码不能为空");
        }
        if (role.getId() == null || role.getId().isBlank()) {
            role.setId("role_" + UUID.randomUUID().toString().substring(0, 8));
        }
        iamRepository.saveRole(role);
        return role;
    }

    public Map<String, Object> deleteRole(String roleCode) {
        if ("ROLE_ADMIN".equals(roleCode) || "ROLE_APP_USER".equals(roleCode)) {
            throw DomainException.stateGuardFailed("平台基础内置角色 [" + roleCode + "] 禁止删除");
        }
        iamRepository.deleteRole(roleCode);
        return Map.of("deleted", true, "roleCode", roleCode);
    }

    // --- 多租户管理 ---
    public List<TenantDef> listTenants() {
        return iamRepository.findAllTenants();
    }

    public Optional<TenantDef> findTenantById(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        Optional<TenantDef> byId = iamRepository.findTenantById(tenantId);
        return byId.isPresent() ? byId : iamRepository.findTenantByCode(tenantId);
    }

    public TenantDef saveTenant(TenantDef t) {
        if (t.getCode() == null || t.getCode().isBlank() || t.getName() == null || t.getName().isBlank()) {
            throw DomainException.invalidParams("租户编码 (code) 与名称 (name) 不能为空");
        }
        if (t.getId() == null || t.getId().isBlank()) {
            t.setId(t.getCode().trim());
        }
        if (t.getCreateDate() == null || t.getCreateDate().isBlank()) {
            t.setCreateDate(TS_FMT.format(LocalDateTime.now()));
        }
        iamRepository.saveTenant(t);
        // 若该租户下尚未建立根组织，自动初始化一个组织根节点
        if (iamRepository.findOrgsByTenant(t.getId()).isEmpty()) {
            OrgNode root = new OrgNode();
            root.setId("org_" + t.getId());
            root.setTenantId(t.getId());
            root.setName(t.getName() + "总部");
            root.setOrgType("ROOT");
            root.setSequence(1);
            iamRepository.saveOrg(root);
        }
        return t;
    }

    public TenantDef authorizeApps(String tenantId, List<String> authorizedApps) {
        TenantDef tenant = findTenantById(tenantId)
                .orElseThrow(() -> DomainException.invalidParams("租户不存在: " + tenantId));
        tenant.setAuthorizedApps(authorizedApps != null ? authorizedApps : new ArrayList<>());
        iamRepository.saveTenant(tenant);
        return tenant;
    }

    // --- 组织架构树管理 ---
    public List<OrgNode> listOrgs(String tenantId) {
        return iamRepository.findOrgsByTenant(tenantId);
    }

    public OrgNode saveOrg(OrgNode org) {
        if (org.getName() == null || org.getName().isBlank()) {
            throw DomainException.invalidParams("组织节点名称不能为空");
        }
        if (org.getId() == null || org.getId().isBlank()) {
            org.setId("org_" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (org.getTenantId() == null || org.getTenantId().isBlank()) {
            org.setTenantId("rbac_tenant_root");
        }
        iamRepository.saveOrg(org);
        return org;
    }

    public Map<String, Object> deleteOrg(String orgId) {
        if ("org_root".equals(orgId)) {
            throw DomainException.stateGuardFailed("平台默认根组织禁止删除");
        }
        for (OrgNode child : iamRepository.findOrgsByTenant(null)) {
            if (Objects.equals(orgId, child.getParentId())) {
                throw DomainException.stateGuardFailed("组织节点下仍存在子部门 [" + child.getName() + "]，禁止直接删除");
            }
        }
        iamRepository.deleteOrg(orgId);
        return Map.of("deleted", true, "orgId", orgId);
    }

    private Map<String, Object> sanitizeUser(UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("tenantId", u.getTenantId());
        m.put("orgId", u.getOrgId());
        m.put("phone", u.getPhone());
        m.put("email", u.getEmail());
        m.put("status", u.getStatus());
        m.put("roleCodes", u.getRoleCodes());
        return m;
    }
}
