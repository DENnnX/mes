package com.sie.lowcode.infrastructure.persistence.sqlite;

import com.sie.lowcode.domain.metamodel.model.*;
import com.sie.lowcode.domain.metamodel.repository.GovernanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class SqliteGovernanceRepository implements GovernanceRepository {
    private final JdbcTemplate jdbcTemplate;

    public SqliteGovernanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void savePublishRecord(PublishRecord r) {
        jdbcTemplate.update(
                "INSERT INTO meta_publish_record (id, model_name, from_version_id, to_version_id, to_version_no, action_type, impact_json, operator_id, operated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.getId(), r.getModelName(), r.getFromVersionId(), r.getToVersionId(), r.getToVersionNo(),
                r.getActionType(), r.getImpactJson(), r.getOperatorId(), r.getOperatedAt());
    }

    @Override
    public List<PublishRecord> findPublishRecords(String modelName) {
        String sql = (modelName != null && !modelName.isBlank())
                ? "SELECT * FROM meta_publish_record WHERE model_name = ? ORDER BY operated_at DESC"
                : "SELECT * FROM meta_publish_record ORDER BY operated_at DESC";
        List<Map<String, Object>> rows = (modelName != null && !modelName.isBlank())
                ? jdbcTemplate.queryForList(sql, modelName) : jdbcTemplate.queryForList(sql);
        List<PublishRecord> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            PublishRecord pr = new PublishRecord();
            pr.setId((String) r.get("id"));
            pr.setModelName((String) r.get("model_name"));
            pr.setFromVersionId((String) r.get("from_version_id"));
            pr.setToVersionId((String) r.get("to_version_id"));
            pr.setToVersionNo((String) r.get("to_version_no"));
            pr.setActionType((String) r.get("action_type"));
            pr.setImpactJson((String) r.get("impact_json"));
            pr.setOperatorId((String) r.get("operator_id"));
            pr.setOperatedAt((String) r.get("operated_at"));
            list.add(pr);
        }
        return list;
    }

    @Override
    public void saveExportTicket(String ticketId, String modelName, long recordCount, String snapshotJson, String operatorId, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO meta_export_ticket (ticket_id, model_name, record_count, snapshot_json, operator_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                ticketId, modelName, recordCount, snapshotJson, operatorId, createdAt);
    }

    @Override
    public Optional<String> findValidExportTicketModel(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) return Optional.empty();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT model_name FROM meta_export_ticket WHERE ticket_id = ?", ticketId);
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.get(0).get("model_name"));
    }

    @Override
    public void savePurgeApply(PurgeApply a) {
        jdbcTemplate.update(
                "INSERT INTO meta_purge_apply (id, model_name, export_ticket, apply_by, apply_time, confirm_by, confirm_time, status, reject_reason) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(id) DO UPDATE SET confirm_by=excluded.confirm_by, confirm_time=excluded.confirm_time, " +
                        "status=excluded.status, reject_reason=excluded.reject_reason",
                a.getId(), a.getModelName(), a.getExportTicket(), a.getApplyBy(), a.getApplyTime(),
                a.getConfirmBy(), a.getConfirmTime(), a.getStatus().name(), a.getRejectReason());
    }

    @Override
    public Optional<PurgeApply> findPurgeApplyById(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_purge_apply WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapPurgeApply(rows.get(0)));
    }

    @Override
    public List<PurgeApply> findAllPurgeApplies() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_purge_apply ORDER BY apply_time DESC");
        List<PurgeApply> list = new ArrayList<>();
        for (Map<String, Object> r : rows) list.add(mapPurgeApply(r));
        return list;
    }

    @Override
    public void savePurgeAudit(PurgeAudit audit) {
        jdbcTemplate.update(
                "INSERT INTO meta_purge_audit (audit_id, model_name, table_name, export_ticket, apply_by, confirm_by, purged_record_count, purged_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                audit.getAuditId(), audit.getModelName(), audit.getTableName(), audit.getExportTicket(),
                audit.getApplyBy(), audit.getConfirmBy(), audit.getPurgedRecordCount(), audit.getPurgedAt());
    }

    @Override
    public List<PurgeAudit> findAllPurgeAudits() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_purge_audit ORDER BY purged_at DESC");
        List<PurgeAudit> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            PurgeAudit a = new PurgeAudit();
            a.setAuditId((String) r.get("audit_id"));
            a.setModelName((String) r.get("model_name"));
            a.setTableName((String) r.get("table_name"));
            a.setExportTicket((String) r.get("export_ticket"));
            a.setApplyBy((String) r.get("apply_by"));
            a.setConfirmBy((String) r.get("confirm_by"));
            a.setPurgedRecordCount(r.get("purged_record_count") instanceof Number ? ((Number) r.get("purged_record_count")).longValue() : 0L);
            a.setPurgedAt((String) r.get("purged_at"));
            list.add(a);
        }
        return list;
    }

    private PurgeApply mapPurgeApply(Map<String, Object> r) {
        PurgeApply a = new PurgeApply();
        a.setId((String) r.get("id"));
        a.setModelName((String) r.get("model_name"));
        a.setExportTicket((String) r.get("export_ticket"));
        a.setApplyBy((String) r.get("apply_by"));
        a.setApplyTime((String) r.get("apply_time"));
        a.setConfirmBy((String) r.get("confirm_by"));
        a.setConfirmTime((String) r.get("confirm_time"));
        a.setStatus(PurgeApply.Status.valueOf((String) r.get("status")));
        a.setRejectReason((String) r.get("reject_reason"));
        return a;
    }
}
