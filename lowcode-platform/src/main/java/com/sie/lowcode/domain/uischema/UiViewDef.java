package com.sie.lowcode.domain.uischema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

/**
 * UI 视图定义实体（对标 iidp UiViewMeta & BodyView/ColumnView/ButtonView/TbarView/TabView）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UiViewDef {
    private String id;
    private String key;         // e.g. "product_grid"
    private String name;        // e.g. "产品-表格"
    private String model;
    private String type;        // "grid" | "search" | "form"
    private String mode = "primary";
    private String inheritFrom;
    private int priority = 10;
    private boolean active = true;
    private Map<String, Object> body = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getInheritFrom() { return inheritFrom; }
    public void setInheritFrom(String inheritFrom) { this.inheritFrom = inheritFrom; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Map<String, Object> getBody() { return body; }
    public void setBody(Map<String, Object> body) { this.body = body != null ? body : new LinkedHashMap<>(); }
}
