package com.sie.lowcode.domain.uischema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

/**
 * 动态菜单元数据实体（对标 iidp MenuMeta / menus.json）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MenuNode {
    private String id;
    private String name;
    private String displayName;
    private String appName;
    private String parentId;
    private int sequence = 10;
    private String icon = "Menu";
    private String model;
    private String view;        // e.g. "my_model_grid,my_model_search,my_model_form"
    private String filter;      // 默认域过滤 DSL JSON
    private boolean active = true;
    private List<MenuNode> children = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName != null ? displayName : name; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getView() { return view; }
    public void setView(String view) { this.view = view; }
    public String getFilter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public List<MenuNode> getChildren() { return children; }
    public void setChildren(List<MenuNode> children) { this.children = children != null ? children : new ArrayList<>(); }
}
