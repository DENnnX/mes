package com.sie.lowcode.domain.metamodel.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

/**
 * 某一个模型版本的不可变定义快照（Immutable Model Snapshot）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelSnapshot {
    private String name;
    private String displayName;
    private String appName;
    private String tableName;
    private String description;
    private String versionNo = "1.0.0";
    private List<String> parents = new ArrayList<>();
    private String extendModel; // 若非空，表示该模型非侵入式扩展指定目标模型（对标 @Model(name=X, parent=X)）
    @JsonAlias("isAbstract")
    private boolean abstractModel = false; // 抽象模型不生成物理数据表，专供子模型继承复用
    private boolean autoLog = true;
    private boolean logicDelete = false;
    private String orderBy = "create_date desc";
    private String displayFormat;
    private String dataScope = "ALL"; // ALL | SELF
    private boolean validateWritesWithActiveVersion;
    private boolean useActiveVersionForRecords;
    private List<IndexDef> indexes = new ArrayList<>();
    private List<PropertyDef> properties = new ArrayList<>();
    private List<ServiceDef> services = new ArrayList<>();
    @JsonIgnore
    private Map<String, List<ServiceDef>> parentServiceChains = new LinkedHashMap<>();

    @JsonIgnore
    public PropertyDef getProperty(String fieldName) {
        if (fieldName == null) return null;
        return properties.stream()
                .filter(p -> fieldName.equals(p.getName()))
                .findFirst()
                .orElse(null);
    }

    @JsonIgnore
    public ServiceDef getService(String serviceName) {
        if (serviceName == null) return null;
        return services.stream()
                .filter(s -> serviceName.equals(s.getName()))
                .findFirst()
                .orElse(null);
    }

    @JsonIgnore
    public PropertyDef findDisplayProperty() {
        return properties.stream()
                .filter(PropertyDef::isDisplayForModel)
                .findFirst()
                .orElseGet(() -> {
                    PropertyDef byName = getProperty("name");
                    if (byName != null) return byName;
                    PropertyDef byDisplayName = getProperty("display_name");
                    if (byDisplayName != null) return byDisplayName;
                    return properties.stream()
                            .filter(p -> p.isStoredColumn() && "String".equalsIgnoreCase(p.getDataType()))
                            .findFirst()
                            .orElse(null);
                });
    }

    @JsonIgnore
    public String resolvedTableName() {
        return (tableName != null && !tableName.isBlank()) ? tableName : name;
    }

    @JsonIgnore
    public boolean isExtensionPackage() {
        return (extendModel != null && !extendModel.isBlank()) || parents.contains(name);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getVersionNo() { return versionNo; }
    public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
    public List<String> getParents() { return parents; }
    public void setParents(List<String> parents) { this.parents = parents != null ? parents : new ArrayList<>(); }
    public String getExtendModel() { return extendModel; }
    public void setExtendModel(String extendModel) { this.extendModel = extendModel; }
    public boolean isAbstractModel() { return abstractModel; }
    public void setAbstractModel(boolean abstractModel) { this.abstractModel = abstractModel; }
    public boolean isAutoLog() { return autoLog; }
    public void setAutoLog(boolean autoLog) { this.autoLog = autoLog; }
    public boolean isLogicDelete() { return logicDelete; }
    public void setLogicDelete(boolean logicDelete) { this.logicDelete = logicDelete; }
    public String getOrderBy() { return orderBy; }
    public void setOrderBy(String orderBy) { this.orderBy = orderBy; }
    public String getDisplayFormat() { return displayFormat; }
    public void setDisplayFormat(String displayFormat) { this.displayFormat = displayFormat; }
    public String getDataScope() { return dataScope; }
    public void setDataScope(String dataScope) { this.dataScope = dataScope; }
    public boolean isValidateWritesWithActiveVersion() { return validateWritesWithActiveVersion; }
    public void setValidateWritesWithActiveVersion(boolean enabled) { this.validateWritesWithActiveVersion = enabled; }
    public boolean isUseActiveVersionForRecords() { return useActiveVersionForRecords; }
    public void setUseActiveVersionForRecords(boolean enabled) { this.useActiveVersionForRecords = enabled; }
    public List<IndexDef> getIndexes() { return indexes; }
    public void setIndexes(List<IndexDef> indexes) { this.indexes = indexes != null ? indexes : new ArrayList<>(); }
    public List<PropertyDef> getProperties() { return properties; }
    public void setProperties(List<PropertyDef> properties) { this.properties = properties != null ? properties : new ArrayList<>(); }
    public List<ServiceDef> getServices() { return services; }
    public void setServices(List<ServiceDef> services) { this.services = services != null ? services : new ArrayList<>(); }
    public Map<String, List<ServiceDef>> getParentServiceChains() { return parentServiceChains; }
    public void setParentServiceChains(Map<String, List<ServiceDef>> parentServiceChains) {
        this.parentServiceChains = parentServiceChains != null ? parentServiceChains : new LinkedHashMap<>();
    }
}
