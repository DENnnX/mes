package com.edu.scnu.domain.metamodel.model;

import com.edu.scnu.domain.shared.DomainException;
import java.util.Objects;

/**
 * 模型聚合根：维护活动版本指针（activeVersionId）及 SM-02 生命周期状态不变性。
 */
public class ModelAggregate {
    public enum LifecycleState {
        ONLINE_ACTIVE, OFFLINE, PURGE_PENDING, PURGED
    }

    private String id;
    private String name;
    private String displayName;
    private String appName;
    private String tableName;
    private boolean kernelModel = false; // 内核自举模型不可被下线或清除
    private String activeVersionId;
    private LifecycleState lifecycleState = LifecycleState.ONLINE_ACTIVE;
    private String offlineTime;
    private int retentionDays = 0;
    private String createUser;
    private String createDate;
    private String updateDate;
    private int version = 1;

    public void activateVersion(ModelVersion targetVersion, String expectedHash) {
        if (lifecycleState == LifecycleState.PURGED) {
            throw DomainException.stateGuardFailed("已清除的模型禁止发布版本: " + name);
        }
        if (!Objects.equals(this.name, targetVersion.getModelName())) {
            throw DomainException.stateGuardFailed("活动指针只能指向归属本模型的版本");
        }
        if (targetVersion.getStatus() != ModelVersion.Status.VALIDATED
                && targetVersion.getStatus() != ModelVersion.Status.RETIRED) {
            throw DomainException.stateGuardFailed("仅 VALIDATED（通过影响检查）或 RETIRED（兼容回滚）的版本允许激活");
        }
        if (targetVersion.getStatus() == ModelVersion.Status.VALIDATED
                && expectedHash != null && !expectedHash.isBlank()
                && !Objects.equals(expectedHash, targetVersion.getValidationHash())) {
            throw DomainException.concurrentConflict("影响检查凭据已过期或定义已被并发修改，请重新检查影响后再发布");
        }
        this.activeVersionId = targetVersion.getId();
        targetVersion.setStatus(ModelVersion.Status.ACTIVE);
        if (this.lifecycleState == LifecycleState.OFFLINE) {
            this.lifecycleState = LifecycleState.ONLINE_ACTIVE;
            this.offlineTime = null;
        }
    }

    public void markOffline(String nowIso) {
        if (kernelModel) {
            throw DomainException.stateGuardFailed("平台内核模型禁止下线: " + name);
        }
        if (lifecycleState == LifecycleState.PURGE_PENDING || lifecycleState == LifecycleState.PURGED) {
            throw DomainException.stateGuardFailed("当前状态无法执行下线: " + lifecycleState);
        }
        this.lifecycleState = LifecycleState.OFFLINE;
        this.offlineTime = nowIso;
    }

    public void markOnline() {
        if (lifecycleState == LifecycleState.PURGED) {
            throw DomainException.stateGuardFailed("已永久清除的模型无法重新上线");
        }
        if (activeVersionId == null) {
            throw DomainException.stateGuardFailed("尚未发布有效版本，无法上线");
        }
        this.lifecycleState = LifecycleState.ONLINE_ACTIVE;
        this.offlineTime = null;
    }

    public boolean isAvailableForRuntime() {
        return lifecycleState == LifecycleState.ONLINE_ACTIVE && activeVersionId != null;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public boolean isKernelModel() { return kernelModel; }
    public void setKernelModel(boolean kernelModel) { this.kernelModel = kernelModel; }
    public String getActiveVersionId() { return activeVersionId; }
    public void setActiveVersionId(String activeVersionId) { this.activeVersionId = activeVersionId; }
    public LifecycleState getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(LifecycleState lifecycleState) { this.lifecycleState = lifecycleState; }
    public String getOfflineTime() { return offlineTime; }
    public void setOfflineTime(String offlineTime) { this.offlineTime = offlineTime; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public String getCreateUser() { return createUser; }
    public void setCreateUser(String createUser) { this.createUser = createUser; }
    public String getCreateDate() { return createDate; }
    public void setCreateDate(String createDate) { this.createDate = createDate; }
    public String getUpdateDate() { return updateDate; }
    public void setUpdateDate(String updateDate) { this.updateDate = updateDate; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
