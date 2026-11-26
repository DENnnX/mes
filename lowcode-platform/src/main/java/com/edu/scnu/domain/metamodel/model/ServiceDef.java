package com.edu.scnu.domain.metamodel.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

/**
 * 模型服务定义（对标 engine ServiceMeta / MetaService），支持：
 * - BUILTIN_CRUD: 引擎内置标准 CRUD + lookup
 * - GROOVY_SCRIPT: 动态 Groovy 脚本服务（绑定 env MetaContext + rs RecordSet）
 * - STATE_TRANSITION: 零代码声明式状态转移动作
 * - JAVA_EXTENSION: Java @MethodService / ModelServiceHandler 扩展链
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceDef {
    public static final String TYPE_BUILTIN_CRUD = "BUILTIN_CRUD";
    public static final String TYPE_GROOVY_SCRIPT = "GROOVY_SCRIPT";
    public static final String TYPE_STATE_TRANSITION = "STATE_TRANSITION";
    public static final String TYPE_JAVA_EXTENSION = "JAVA_EXTENSION";

    private String name;
    private String displayName;
    private String type = TYPE_GROOVY_SCRIPT;
    private String auth;
    private String description;
    private String scriptContent;
    private TransitionRuleDef transitionRule;
    private List<PropertyDef> paramsSchema = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransitionRuleDef {
        private String statusField = "status";
        private List<String> fromStates = new ArrayList<>();
        private String toState;
        private String guardScript;
        private Map<String, Object> updateFields = new LinkedHashMap<>();
        private String errorMessage;

        public String getStatusField() { return statusField; }
        public void setStatusField(String statusField) { this.statusField = statusField; }
        public List<String> getFromStates() { return fromStates; }
        public void setFromStates(List<String> fromStates) { this.fromStates = fromStates != null ? fromStates : new ArrayList<>(); }
        public String getToState() { return toState; }
        public void setToState(String toState) { this.toState = toState; }
        public String getGuardScript() { return guardScript; }
        public void setGuardScript(String guardScript) { this.guardScript = guardScript; }
        public Map<String, Object> getUpdateFields() { return updateFields; }
        public void setUpdateFields(Map<String, Object> updateFields) { this.updateFields = updateFields != null ? updateFields : new LinkedHashMap<>(); }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName != null ? displayName : name; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getAuth() { return auth != null ? auth : name; }
    public void setAuth(String auth) { this.auth = auth; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getScriptContent() { return scriptContent; }
    public void setScriptContent(String scriptContent) { this.scriptContent = scriptContent; }
    public TransitionRuleDef getTransitionRule() { return transitionRule; }
    public void setTransitionRule(TransitionRuleDef transitionRule) { this.transitionRule = transitionRule; }
    public List<PropertyDef> getParamsSchema() { return paramsSchema; }
    public void setParamsSchema(List<PropertyDef> paramsSchema) { this.paramsSchema = paramsSchema != null ? paramsSchema : new ArrayList<>(); }
}
