package com.edu.scnu.domain.metamodel.model;

import com.edu.scnu.domain.shared.DomainException;

/**
 * 永久清除申请聚合（meta_purge_apply），记录申请状态、审核时间与最终确认人。
 */
public class PurgeApply {
    public enum Status {
        PENDING, CONFIRMED, REJECTED, EXECUTED
    }

    private String id;
    private String modelName;
    private String exportTicket;
    private String applyBy;
    private String applyTime;
    private String confirmBy;
    private String confirmTime;
    private Status status = Status.PENDING;
    private String rejectReason;

    public void confirmBy(String adminUserId, String nowIso) {
        if (this.status != Status.PENDING) {
            throw DomainException.stateGuardFailed("仅待审核申请可执行确认清除，当前状态: " + status);
        }
        if (adminUserId == null || adminUserId.isBlank()) {
            throw DomainException.permissionDenied("缺少执行清除确认的管理员身份");
        }
        this.confirmBy = adminUserId;
        this.confirmTime = nowIso;
        this.status = Status.CONFIRMED;
    }

    public void rejectBy(String adminUserId, String reason, String nowIso) {
        if (this.status != Status.PENDING) {
            throw DomainException.stateGuardFailed("仅待审核申请可执行驳回");
        }
        this.confirmBy = adminUserId;
        this.confirmTime = nowIso;
        this.rejectReason = reason;
        this.status = Status.REJECTED;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getExportTicket() { return exportTicket; }
    public void setExportTicket(String exportTicket) { this.exportTicket = exportTicket; }
    public String getApplyBy() { return applyBy; }
    public void setApplyBy(String applyBy) { this.applyBy = applyBy; }
    public String getApplyTime() { return applyTime; }
    public void setApplyTime(String applyTime) { this.applyTime = applyTime; }
    public String getConfirmBy() { return confirmBy; }
    public void setConfirmBy(String confirmBy) { this.confirmBy = confirmBy; }
    public String getConfirmTime() { return confirmTime; }
    public void setConfirmTime(String confirmTime) { this.confirmTime = confirmTime; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
}
