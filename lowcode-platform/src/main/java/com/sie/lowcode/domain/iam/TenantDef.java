package com.sie.lowcode.domain.iam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TenantDef {
    private String id;
    private String code;
    private String name;
    private String contactName;
    private String contactPhone;
    private String status = "ACTIVE";
    private String parentTenantId;
    private List<String> authorizedApps = new ArrayList<>(); // 空表示全部已安装应用可见（兼容默认根租户）
    private List<String> authorizedMenus = new ArrayList<>(); // 空或含 "*" 表示已授权应用下全部激活菜单可见
    private int maxUsers = 100;
    private String createDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getParentTenantId() { return parentTenantId; }
    public void setParentTenantId(String parentTenantId) { this.parentTenantId = parentTenantId; }
    public List<String> getAuthorizedApps() { return authorizedApps; }
    public void setAuthorizedApps(List<String> authorizedApps) {
        this.authorizedApps = authorizedApps != null ? authorizedApps : new ArrayList<>();
    }
    public List<String> getAuthorizedMenus() { return authorizedMenus; }
    public void setAuthorizedMenus(List<String> authorizedMenus) {
        this.authorizedMenus = authorizedMenus != null ? authorizedMenus : new ArrayList<>();
    }
    public int getMaxUsers() { return maxUsers; }
    public void setMaxUsers(int maxUsers) { this.maxUsers = maxUsers; }
    public String getCreateDate() { return createDate; }
    public void setCreateDate(String createDate) { this.createDate = createDate; }
}
