package com.edu.scnu.domain.iam;

import java.util.*;

public class RoleDef {
    private String id;
    private String code;
    private String name;
    private String dataScope = "ALL"; // ALL | SELF
    private String tenantId;
    private Set<String> allowedServices = new LinkedHashSet<>(); // "*" 或 "model:service"
    private List<String> allowedMenus = new ArrayList<>(); // 空或含 "*" 表示继承租户菜单授权范围

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDataScope() { return dataScope; }
    public void setDataScope(String dataScope) { this.dataScope = dataScope; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Set<String> getAllowedServices() { return allowedServices; }
    public void setAllowedServices(Set<String> allowedServices) {
        this.allowedServices = allowedServices != null ? allowedServices : new LinkedHashSet<>();
    }
    public List<String> getAllowedMenus() { return allowedMenus; }
    public void setAllowedMenus(List<String> allowedMenus) {
        this.allowedMenus = allowedMenus != null ? allowedMenus : new ArrayList<>();
    }
}
