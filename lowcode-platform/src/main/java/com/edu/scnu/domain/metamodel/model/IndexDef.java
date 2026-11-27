package com.edu.scnu.domain.metamodel.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

/**
 * 复合索引 / 联合唯一索引定义（对标 @Model indexes = { @Index(...) }）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexDef {
    private String name;
    private List<String> columnList = new ArrayList<>();
    private boolean unique = false;

    public IndexDef() {}

    public IndexDef(String name, List<String> columnList, boolean unique) {
        this.name = name;
        this.columnList = columnList != null ? columnList : new ArrayList<>();
        this.unique = unique;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getColumnList() { return columnList; }
    public void setColumnList(List<String> columnList) { this.columnList = columnList != null ? columnList : new ArrayList<>(); }
    public boolean isUnique() { return unique; }
    public void setUnique(boolean unique) { this.unique = unique; }
}
