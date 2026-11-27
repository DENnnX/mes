package com.edu.scnu.domain.shared;

import com.edu.scnu.domain.runtime.RecordSet;
import java.io.Closeable;
import java.util.*;

/**
 * 对标 sie-snest-engine 的 Meta (Odoo env)：
 * 绑定当前请求线程的执行环境，携带身份、租户、角色、权限范围及模型 RecordSet 工厂入口。
 */
public class MetaContext implements Closeable {
    public static final String SUPERUSER = "rbac_user_superuser";
    public static final String DEFAULT_TENANT = "rbac_tenant_root";

    private static final ThreadLocal<MetaContext> THREAD_CONTEXT = new ThreadLocal<>();

    private String tenantId;
    private String userId;
    private String username;
    private String displayName;
    private Set<String> roleCodes = new LinkedHashSet<>();
    private Set<String> visibleTenantIds = new LinkedHashSet<>();
    private String dataScope = "ALL";
    private String locale = "zh-CN";
    private String timeZone = "UTC+8";
    private final Map<String, Object> context = new LinkedHashMap<>();

    private final RecordSetFactory recordSetFactory;

    @FunctionalInterface
    public interface RecordSetFactory {
        RecordSet create(MetaContext meta, String modelName, String... ids);
    }

    public MetaContext(String tenantId, String userId, RecordSetFactory factory) {
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT : tenantId;
        this.userId = (userId == null || userId.isBlank()) ? SUPERUSER : userId;
        this.recordSetFactory = factory;
        THREAD_CONTEXT.set(this);
    }

    public static MetaContext current() {
        return THREAD_CONTEXT.get();
    }

    public static void bind(MetaContext ctx) {
        THREAD_CONTEXT.set(ctx);
    }

    public static void clear() {
        THREAD_CONTEXT.remove();
    }

    /**
     * 获取目标模型的 RecordSet 句柄（等价于 Odoo self.env["model"] / Meta.get(model)）
     */
    public RecordSet get(String modelName, String... ids) {
        if (recordSetFactory == null) {
            throw new IllegalStateException("RecordSetFactory not initialized in MetaContext");
        }
        return recordSetFactory.create(this, modelName, ids);
    }

    public boolean isSuperUser() {
        return SUPERUSER.equals(userId) || roleCodes.contains("ROLE_ADMIN");
    }

    public boolean hasRole(String roleCode) {
        return isSuperUser() || roleCodes.contains(roleCode);
    }

    public void executeAsSuperUser(Runnable action) {
        String prevUser = this.userId;
        Set<String> prevRoles = new LinkedHashSet<>(this.roleCodes);
        try {
            this.userId = SUPERUSER;
            this.roleCodes.add("ROLE_ADMIN");
            action.run();
        } finally {
            this.userId = prevUser;
            this.roleCodes = prevRoles;
        }
    }

    @Override
    public void close() {
        THREAD_CONTEXT.remove();
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Set<String> getRoleCodes() { return roleCodes; }
    public void setRoleCodes(Set<String> roleCodes) {
        this.roleCodes = roleCodes != null ? new LinkedHashSet<>(roleCodes) : new LinkedHashSet<>();
    }
    public Set<String> getVisibleTenantIds() { return visibleTenantIds; }
    public void setVisibleTenantIds(Set<String> visibleTenantIds) {
        this.visibleTenantIds = visibleTenantIds != null ? new LinkedHashSet<>(visibleTenantIds) : new LinkedHashSet<>();
    }
    public String getDataScope() { return dataScope; }
    public void setDataScope(String dataScope) { this.dataScope = dataScope; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public Map<String, Object> getContext() { return context; }
}
