package com.sie.lowcode.domain.iam;

import java.util.*;

public class UserAccount {
    private String id;
    private String username;
    private String displayName;
    private String password;
    private String tenantId = "rbac_tenant_root";
    private String orgId = "org_root";
    private String phone;
    private String email;
    private String status = "ACTIVE"; // ACTIVE | DISABLED
    private Set<String> roleCodes = new LinkedHashSet<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Set<String> getRoleCodes() { return roleCodes; }
    public void setRoleCodes(Set<String> roleCodes) { this.roleCodes = roleCodes != null ? roleCodes : new LinkedHashSet<>(); }
}
