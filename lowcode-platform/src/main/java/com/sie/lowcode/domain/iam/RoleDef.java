package com.sie.lowcode.domain.iam;

import java.util.*;

public class RoleDef {
    private String id;
    private String code;
    private String name;
    private String dataScope = "ALL"; // ALL | SELF
    private Set<String> allowedServices = new LinkedHashSet<>(); // "*" 或 "model:service"

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDataScope() { return dataScope; }
    public void setDataScope(String dataScope) { this.dataScope = dataScope; }
    public Set<String> getAllowedServices() { return allowedServices; }
    public void setAllowedServices(Set<String> allowedServices) {
        this.allowedServices = allowedServices != null ? allowedServices : new LinkedHashSet<>();
    }
}
