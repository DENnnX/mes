package com.edu.scnu.domain.metamodel.repository;

import com.edu.scnu.domain.metamodel.model.*;
import java.util.List;
import java.util.Optional;

public interface GovernanceRepository {
    void savePublishRecord(PublishRecord record);
    List<PublishRecord> findPublishRecords(String modelName);

    void saveExportTicket(String ticketId, String modelName, long recordCount, String snapshotJson, String operatorId, String createdAt);
    Optional<String> findValidExportTicketModel(String ticketId);

    void savePurgeApply(PurgeApply apply);
    Optional<PurgeApply> findPurgeApplyById(String id);
    List<PurgeApply> findAllPurgeApplies();

    void savePurgeAudit(PurgeAudit audit);
    List<PurgeAudit> findAllPurgeAudits();
}
