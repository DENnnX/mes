package com.sie.lowcode.domain.metamodel.model;

/**
 * 解耦不可篡改清除审计记录（meta_purge_audit）：
 * 不依赖已销毁的业务记录行，不含业务敏感明细。
 */
public class PurgeAudit {
    private String auditId;
    private String modelName;
    private String tableName;
    private String exportTicket;
    private String applyBy;
    private String confirmBy;
    private long purgedRecordCount;
    private String purgedAt;

    public String getAuditId() { return auditId; }
    public void setAuditId(String auditId) { this.auditId = auditId; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getExportTicket() { return exportTicket; }
    public void setExportTicket(String exportTicket) { this.exportTicket = exportTicket; }
    public String getApplyBy() { return applyBy; }
    public void setApplyBy(String applyBy) { this.applyBy = applyBy; }
    public String getConfirmBy() { return confirmBy; }
    public void setConfirmBy(String confirmBy) { this.confirmBy = confirmBy; }
    public long getPurgedRecordCount() { return purgedRecordCount; }
    public void setPurgedRecordCount(long purgedRecordCount) { this.purgedRecordCount = purgedRecordCount; }
    public String getPurgedAt() { return purgedAt; }
    public void setPurgedAt(String purgedAt) { this.purgedAt = purgedAt; }
}
