package com.edu.scnu.infrastructure.persistence.sqlite;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.edu.scnu.domain.iam.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class SqliteIamRepository implements IamRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SqliteIamRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<UserAccount> findUserByUsername(String username) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM rbac_user WHERE username = ?", username);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapUser(rows.get(0)));
    }

    @Override
    public Optional<UserAccount> findUserById(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM rbac_user WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapUser(rows.get(0)));
    }

    @Override
    public List<UserAccount> findAllUsers() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM rbac_user ORDER BY id");
        List<UserAccount> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapUser(r));
        return res;
    }

    @Override
    public List<UserAccount> findUsersByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return findAllUsers();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM rbac_user WHERE tenant_id = ? ORDER BY id", tenantId);
        List<UserAccount> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapUser(r));
        return res;
    }

    @Override
    public void saveUser(UserAccount u) {
        String rolesJson = writeJson(u.getRoleCodes());
        String tenantId = (u.getTenantId() == null || u.getTenantId().isBlank()) ? "rbac_tenant_root" : u.getTenantId();
        String orgId = (u.getOrgId() == null || u.getOrgId().isBlank()) ? "org_root" : u.getOrgId();
        jdbcTemplate.update(
                "INSERT INTO rbac_user (id, username, display_name, password, tenant_id, org_id, phone, email, status, role_codes_json) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(username) DO UPDATE SET display_name=excluded.display_name, password=excluded.password, " +
                        "tenant_id=excluded.tenant_id, org_id=excluded.org_id, phone=excluded.phone, email=excluded.email, " +
                        "status=excluded.status, role_codes_json=excluded.role_codes_json",
                u.getId(), u.getUsername(), u.getDisplayName(), u.getPassword(), tenantId, orgId, u.getPhone(), u.getEmail(), u.getStatus(), rolesJson);
    }

    @Override
    public void deleteUser(String userId) {
        jdbcTemplate.update("DELETE FROM rbac_session WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM rbac_user WHERE id = ?", userId);
    }

    @Override
    public List<RoleDef> findRolesByCodes(Iterable<String> codes) {
        List<String> list = new ArrayList<>();
        codes.forEach(list::add);
        if (list.isEmpty()) return Collections.emptyList();
        String ph = String.join(",", Collections.nCopies(list.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM rbac_role WHERE code IN (" + ph + ")", list.toArray());
        List<RoleDef> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapRole(r));
        return res;
    }

    @Override
    public List<RoleDef> findAllRoles() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM rbac_role ORDER BY id");
        List<RoleDef> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapRole(r));
        return res;
    }

    @Override
    public void saveRole(RoleDef role) {
        String permsJson = writeJson(role.getAllowedServices());
        String menusJson = writeJson(role.getAllowedMenus());
        String tenantId = (role.getTenantId() == null || role.getTenantId().isBlank())
                ? "rbac_tenant_root" : role.getTenantId();
        jdbcTemplate.update(
                "INSERT INTO rbac_role (id, code, name, data_scope, allowed_services_json, tenant_id, allowed_menus_json) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(code) DO UPDATE SET name=excluded.name, data_scope=excluded.data_scope, allowed_services_json=excluded.allowed_services_json, tenant_id=excluded.tenant_id, allowed_menus_json=excluded.allowed_menus_json",
                role.getId(), role.getCode(), role.getName(), role.getDataScope(), permsJson, tenantId, menusJson);
    }

    @Override
    public void deleteRole(String roleCode) {
        jdbcTemplate.update("DELETE FROM rbac_role WHERE code = ?", roleCode);
    }

    @Override
    public List<TenantDef> findAllTenants() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM rbac_tenant ORDER BY id");
        List<TenantDef> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapTenant(r));
        return res;
    }

    @Override
    public Optional<TenantDef> findTenantById(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM rbac_tenant WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapTenant(rows.get(0)));
    }

    @Override
    public Optional<TenantDef> findTenantByCode(String code) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM rbac_tenant WHERE code = ?", code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapTenant(rows.get(0)));
    }

    @Override
    public void saveTenant(TenantDef t) {
        String appsJson = writeJson(t.getAuthorizedApps());
        String menusJson = writeJson(t.getAuthorizedMenus());
        String parentId = "rbac_tenant_root".equals(t.getId()) ? null
                : ((t.getParentTenantId() == null || t.getParentTenantId().isBlank()) ? "rbac_tenant_root" : t.getParentTenantId());
        jdbcTemplate.update(
                "INSERT INTO rbac_tenant (id, code, name, contact_name, contact_phone, status, parent_tenant_id, authorized_apps_json, authorized_menus_json, max_users, create_date) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(code) DO UPDATE SET name=excluded.name, contact_name=excluded.contact_name, " +
                        "contact_phone=excluded.contact_phone, status=excluded.status, parent_tenant_id=excluded.parent_tenant_id, authorized_apps_json=excluded.authorized_apps_json, authorized_menus_json=excluded.authorized_menus_json, max_users=excluded.max_users",
                t.getId(), t.getCode(), t.getName(), t.getContactName(), t.getContactPhone(), t.getStatus(), parentId, appsJson, menusJson, t.getMaxUsers(), t.getCreateDate());
    }

    @Override
    public List<OrgNode> findOrgsByTenant(String tenantId) {
        List<Map<String, Object>> rows = (tenantId == null || tenantId.isBlank())
                ? jdbcTemplate.queryForList("SELECT * FROM rbac_org ORDER BY sequence ASC, id ASC")
                : jdbcTemplate.queryForList("SELECT * FROM rbac_org WHERE tenant_id = ? ORDER BY sequence ASC, id ASC", tenantId);
        List<OrgNode> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapOrg(r));
        return res;
    }

    @Override
    public void saveOrg(OrgNode org) {
        jdbcTemplate.update(
                "INSERT INTO rbac_org (id, tenant_id, parent_id, name, org_type, sequence) VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(id) DO UPDATE SET tenant_id=excluded.tenant_id, parent_id=excluded.parent_id, " +
                        "name=excluded.name, org_type=excluded.org_type, sequence=excluded.sequence",
                org.getId(), org.getTenantId(), org.getParentId(), org.getName(), org.getOrgType(), org.getSequence());
    }

    @Override
    public void deleteOrg(String orgId) {
        jdbcTemplate.update("DELETE FROM rbac_org WHERE id = ?", orgId);
    }

    @Override
    public void saveSession(String token, String userId, String expiresAt) {
        jdbcTemplate.update("INSERT INTO rbac_session (token, user_id, expires_at) VALUES (?, ?, ?)", token, userId, expiresAt);
    }

    @Override
    public Optional<String> findUserIdByToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT user_id FROM rbac_session WHERE token = ?", token);
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.get(0).get("user_id"));
    }

    @Override
    public void deleteSession(String token) {
        jdbcTemplate.update("DELETE FROM rbac_session WHERE token = ?", token);
    }

    private UserAccount mapUser(Map<String, Object> r) {
        UserAccount u = new UserAccount();
        u.setId((String) r.get("id"));
        u.setUsername((String) r.get("username"));
        u.setDisplayName((String) r.get("display_name"));
        u.setPassword((String) r.get("password"));
        u.setTenantId((String) r.get("tenant_id"));
        u.setOrgId((String) r.get("org_id"));
        u.setPhone((String) r.get("phone"));
        u.setEmail((String) r.get("email"));
        u.setStatus((String) r.get("status"));
        try {
            String rj = (String) r.get("role_codes_json");
            if (rj != null) u.setRoleCodes(objectMapper.readValue(rj, new TypeReference<>() {}));
        } catch (Exception ignored) {}
        return u;
    }

    private TenantDef mapTenant(Map<String, Object> r) {
        TenantDef t = new TenantDef();
        t.setId((String) r.get("id"));
        t.setCode((String) r.get("code"));
        t.setName((String) r.get("name"));
        t.setContactName((String) r.get("contact_name"));
        t.setContactPhone((String) r.get("contact_phone"));
        t.setStatus((String) r.get("status"));
        t.setParentTenantId((String) r.get("parent_tenant_id"));
        t.setCreateDate((String) r.get("create_date"));
        if (r.get("max_users") instanceof Number n) t.setMaxUsers(n.intValue());
        try {
            String aj = (String) r.get("authorized_apps_json");
            if (aj != null) t.setAuthorizedApps(objectMapper.readValue(aj, new TypeReference<>() {}));
        } catch (Exception ignored) {}
        try {
            String mj = (String) r.get("authorized_menus_json");
            if (mj != null) t.setAuthorizedMenus(objectMapper.readValue(mj, new TypeReference<>() {}));
        } catch (Exception ignored) {}
        return t;
    }

    private OrgNode mapOrg(Map<String, Object> r) {
        OrgNode o = new OrgNode();
        o.setId((String) r.get("id"));
        o.setTenantId((String) r.get("tenant_id"));
        o.setParentId((String) r.get("parent_id"));
        o.setName((String) r.get("name"));
        o.setOrgType((String) r.get("org_type"));
        if (r.get("sequence") instanceof Number n) o.setSequence(n.intValue());
        return o;
    }

    private RoleDef mapRole(Map<String, Object> r) {
        RoleDef role = new RoleDef();
        role.setId((String) r.get("id"));
        role.setCode((String) r.get("code"));
        role.setName((String) r.get("name"));
        role.setDataScope((String) r.get("data_scope"));
        String tid = (String) r.get("tenant_id");
        role.setTenantId((tid == null || tid.isBlank()) ? "rbac_tenant_root" : tid);
        try {
            String pj = (String) r.get("allowed_services_json");
            if (pj != null) role.setAllowedServices(objectMapper.readValue(pj, new TypeReference<>() {}));
        } catch (Exception ignored) {}
        try {
            String mj = (String) r.get("allowed_menus_json");
            if (mj != null) role.setAllowedMenus(objectMapper.readValue(mj, new TypeReference<>() {}));
        } catch (Exception ignored) {}
        return role;
    }

    private String writeJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }
}
