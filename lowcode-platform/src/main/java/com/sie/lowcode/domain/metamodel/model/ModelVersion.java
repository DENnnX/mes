package com.sie.lowcode.domain.metamodel.model;

/**
 * 模型版本实体：维护 SM-01 生命周期状态（DRAFT -> VALIDATED -> ACTIVE -> RETIRED）。
 * 一旦变为 ACTIVE 或 RETIRED，snapshotJson 定义即锁定不可就地更改。
 */
public class ModelVersion {
    public enum Status {
        DRAFT, VALIDATED, ACTIVE, RETIRED
    }

    private String id;
    private String modelName;
    private String versionNo;
    private Status status = Status.DRAFT;
    private String snapshotJson;
    private String pendingUiJson;
    private ModelSnapshot snapshot;
    private String validationHash;
    private String validatedAt;
    private String createdBy;
    private String createdAt;

    public boolean isImmutable() {
        return status == Status.ACTIVE || status == Status.RETIRED;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getVersionNo() { return versionNo; }
    public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public String getPendingUiJson() { return pendingUiJson; }
    public void setPendingUiJson(String pendingUiJson) { this.pendingUiJson = pendingUiJson; }
    public ModelSnapshot getSnapshot() { return snapshot; }
    public void setSnapshot(ModelSnapshot snapshot) { this.snapshot = snapshot; }
    public String getValidationHash() { return validationHash; }
    public void setValidationHash(String validationHash) { this.validationHash = validationHash; }
    public String getValidatedAt() { return validatedAt; }
    public void setValidatedAt(String validatedAt) { this.validatedAt = validatedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
