package com.sie.lowcode.domain.metamodel.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

/**
 * 应用聚合根（对标 engine AppMeta / app.json）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppAggregate {
    private String id;
    private String name;
    private String displayName;
    private String category = "custom";
    private String version = "1.0.0";
    private String tag = "master";
    private String description;
    private List<String> dependencies = new ArrayList<>();
    private String state = "INSTALLED";
    private String createUser;
    private String createDate;
    private String updateDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName != null ? displayName : name; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies != null ? dependencies : new ArrayList<>(); }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getCreateUser() { return createUser; }
    public void setCreateUser(String createUser) { this.createUser = createUser; }
    public String getCreateDate() { return createDate; }
    public void setCreateDate(String createDate) { this.createDate = createDate; }
    public String getUpdateDate() { return updateDate; }
    public void setUpdateDate(String updateDate) { this.updateDate = updateDate; }
}
