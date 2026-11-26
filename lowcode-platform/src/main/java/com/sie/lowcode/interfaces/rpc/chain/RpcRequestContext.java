package com.sie.lowcode.interfaces.rpc.chain;

import com.sie.lowcode.domain.iam.RoleDef;
import com.sie.lowcode.domain.iam.UserAccount;
import com.sie.lowcode.domain.shared.MetaContext;
import com.sie.lowcode.interfaces.rpc.dto.JsonRpcRequest;

import java.util.*;

public class RpcRequestContext {
    private static final Set<String> ANONYMOUS_SERVICES = Set.of(
            "rbac_user.login", "auth.login", "sys_portal.publicApps");

    private final String tenantId;
    private final String authorizationHeader;
    private final boolean openEndpoint;
    private final JsonRpcRequest request;

    private String appName;
    private String tag = "master";
    private String model;
    private String service;
    private Map<String, Object> args = new LinkedHashMap<>();
    private Map<String, Object> clientContext = new LinkedHashMap<>();

    private UserAccount userAccount;
    private List<RoleDef> roles = Collections.emptyList();
    private MetaContext metaContext;

    public RpcRequestContext(String tenantId, String authorizationHeader, boolean openEndpoint, JsonRpcRequest request) {
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? MetaContext.DEFAULT_TENANT : tenantId;
        this.authorizationHeader = authorizationHeader;
        this.openEndpoint = openEndpoint;
        this.request = request;
    }

    public String getTenantId() { return tenantId; }
    public String getAuthorizationHeader() { return authorizationHeader; }
    public boolean isOpenEndpoint() { return openEndpoint; }
    public boolean isAnonymousService() { return ANONYMOUS_SERVICES.contains(model + "." + service); }
    public JsonRpcRequest getRequest() { return request; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public Map<String, Object> getArgs() { return args; }
    public void setArgs(Map<String, Object> args) { this.args = args != null ? args : new LinkedHashMap<>(); }
    public Map<String, Object> getClientContext() { return clientContext; }
    public void setClientContext(Map<String, Object> clientContext) {
        this.clientContext = clientContext != null ? clientContext : new LinkedHashMap<>();
    }
    public UserAccount getUserAccount() { return userAccount; }
    public void setUserAccount(UserAccount userAccount) { this.userAccount = userAccount; }
    public List<RoleDef> getRoles() { return roles; }
    public void setRoles(List<RoleDef> roles) { this.roles = roles != null ? roles : Collections.emptyList(); }
    public MetaContext getMetaContext() { return metaContext; }
    public void setMetaContext(MetaContext metaContext) { this.metaContext = metaContext; }
}
