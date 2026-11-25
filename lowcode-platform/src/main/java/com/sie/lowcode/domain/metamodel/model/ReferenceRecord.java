package com.sie.lowcode.domain.metamodel.model;

/**
 * 元模型字段引用依赖实体（meta_model_ref），用于记录与反查字段引用影响。
 */
public class ReferenceRecord {
    private String id;
    private String modelName;
    private String versionId;
    private String sourceType;      // VIEW_GRID_COL / VIEW_SEARCH_COL / VIEW_FORM_COL / VIEW_BUTTON_COND / RELATION_FK / TRANSITION_RULE
    private String sourceLocation;  // 如 views.product_grid.columns[0].name
    private String targetField;
    private String description;

    public ReferenceRecord() {}

    public ReferenceRecord(String id, String modelName, String versionId,
                           String sourceType, String sourceLocation, String targetField, String description) {
        this.id = id;
        this.modelName = modelName;
        this.versionId = versionId;
        this.sourceType = sourceType;
        this.sourceLocation = sourceLocation;
        this.targetField = targetField;
        this.description = description;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceLocation() { return sourceLocation; }
    public void setSourceLocation(String sourceLocation) { this.sourceLocation = sourceLocation; }
    public String getTargetField() { return targetField; }
    public void setTargetField(String targetField) { this.targetField = targetField; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
