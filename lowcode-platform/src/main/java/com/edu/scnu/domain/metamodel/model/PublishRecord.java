package com.edu.scnu.domain.metamodel.model;

/**
 * 发布与回滚流水记录（meta_publish_record）。
 */
public class PublishRecord {
    private String id;
    private String modelName;
    private String fromVersionId;
    private String toVersionId;
    private String toVersionNo;
    private String actionType; // PUBLISH | ROLLBACK
    private String impactJson;
    private String operatorId;
    private String operatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getFromVersionId() { return fromVersionId; }
    public void setFromVersionId(String fromVersionId) { this.fromVersionId = fromVersionId; }
    public String getToVersionId() { return toVersionId; }
    public void setToVersionId(String toVersionId) { this.toVersionId = toVersionId; }
    public String getToVersionNo() { return toVersionNo; }
    public void setToVersionNo(String toVersionNo) { this.toVersionNo = toVersionNo; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getImpactJson() { return impactJson; }
    public void setImpactJson(String impactJson) { this.impactJson = impactJson; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public String getOperatedAt() { return operatedAt; }
    public void setOperatedAt(String operatedAt) { this.operatedAt = operatedAt; }
}
