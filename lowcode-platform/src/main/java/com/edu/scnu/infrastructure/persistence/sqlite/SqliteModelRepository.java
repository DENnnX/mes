package com.edu.scnu.infrastructure.persistence.sqlite;

import tools.jackson.databind.ObjectMapper;
import com.edu.scnu.domain.metamodel.model.*;
import com.edu.scnu.domain.metamodel.repository.ModelRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class SqliteModelRepository implements ModelRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SqliteModelRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveModel(ModelAggregate m) {
        jdbcTemplate.update(
                "INSERT INTO meta_model (id, name, display_name, app_name, table_name, kernel_model, active_version_id, lifecycle_state, offline_time, retention_days, create_user, create_date, update_date, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(name) DO UPDATE SET display_name=excluded.display_name, app_name=excluded.app_name, " +
                        "table_name=excluded.table_name, kernel_model=excluded.kernel_model, active_version_id=excluded.active_version_id, " +
                        "lifecycle_state=excluded.lifecycle_state, offline_time=excluded.offline_time, retention_days=excluded.retention_days, " +
                        "update_date=excluded.update_date, version=meta_model.version+1",
                m.getId(), m.getName(), m.getDisplayName(), m.getAppName(), m.getTableName(),
                m.isKernelModel() ? 1 : 0, m.getActiveVersionId(), m.getLifecycleState().name(),
                m.getOfflineTime(), m.getRetentionDays(), m.getCreateUser(), m.getCreateDate(), m.getUpdateDate(), m.getVersion());
    }

    @Override
    public Optional<ModelAggregate> findModelByName(String name) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_model WHERE name = ?", name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapModel(rows.get(0)));
    }

    @Override
    public List<ModelAggregate> findAllModels() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_model ORDER BY create_date ASC");
        List<ModelAggregate> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapModel(r));
        return res;
    }

    @Override
    public List<ModelAggregate> findModelsByApp(String appName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_model WHERE app_name = ? ORDER BY create_date ASC", appName);
        List<ModelAggregate> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapModel(r));
        return res;
    }

    @Override
    public void deleteModelMeta(String modelName) {
        jdbcTemplate.update("DELETE FROM meta_model WHERE name = ?", modelName);
    }

    @Override
    public void saveVersion(ModelVersion v) {
        String snapJson = v.getSnapshotJson();
        if (snapJson == null && v.getSnapshot() != null) {
            try { snapJson = objectMapper.writeValueAsString(v.getSnapshot()); } catch (Exception ignored) {}
        }
        jdbcTemplate.update(
                "INSERT INTO meta_model_version (id, model_name, version_no, status, snapshot_json, pending_ui_json, validation_hash, validated_at, created_by, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(id) DO UPDATE SET status=excluded.status, snapshot_json=excluded.snapshot_json, " +
                        "pending_ui_json=excluded.pending_ui_json, validation_hash=excluded.validation_hash, validated_at=excluded.validated_at",
                v.getId(), v.getModelName(), v.getVersionNo(), v.getStatus().name(),
                snapJson, v.getPendingUiJson(), v.getValidationHash(), v.getValidatedAt(), v.getCreatedBy(), v.getCreatedAt());
    }

    @Override
    public Optional<ModelVersion> findVersionById(String versionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_model_version WHERE id = ?", versionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapVersion(rows.get(0)));
    }

    @Override
    public Optional<ModelVersion> findVersion(String modelName, String versionNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM meta_model_version WHERE model_name = ? AND version_no = ?", modelName, versionNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapVersion(rows.get(0)));
    }

    @Override
    public List<ModelVersion> findVersionsByModel(String modelName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM meta_model_version WHERE model_name = ? ORDER BY created_at DESC", modelName);
        List<ModelVersion> list = new ArrayList<>();
        for (Map<String, Object> r : rows) list.add(mapVersion(r));
        return list;
    }

    @Override
    public void deleteVersionsByModel(String modelName) {
        jdbcTemplate.update("DELETE FROM meta_model_version WHERE model_name = ?", modelName);
    }

    @Override
    public void replaceReferences(String versionId, List<ReferenceRecord> references) {
        jdbcTemplate.update("DELETE FROM meta_model_ref WHERE version_id = ?", versionId);
        if (references == null) return;
        for (ReferenceRecord r : references) {
            jdbcTemplate.update(
                    "INSERT INTO meta_model_ref (id, model_name, version_id, source_type, source_location, target_field, description) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    r.getId(), r.getModelName(), versionId, r.getSourceType(), r.getSourceLocation(), r.getTargetField(), r.getDescription());
        }
    }

    @Override
    public List<ReferenceRecord> findReferencesByVersion(String versionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_model_ref WHERE version_id = ?", versionId);
        List<ReferenceRecord> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            list.add(new ReferenceRecord(
                    (String) r.get("id"), (String) r.get("model_name"), (String) r.get("version_id"),
                    (String) r.get("source_type"), (String) r.get("source_location"),
                    (String) r.get("target_field"), (String) r.get("description")));
        }
        return list;
    }

    @Override
    public void deleteReferencesByModel(String modelName) {
        jdbcTemplate.update("DELETE FROM meta_model_ref WHERE model_name = ?", modelName);
    }

    private ModelAggregate mapModel(Map<String, Object> r) {
        ModelAggregate m = new ModelAggregate();
        m.setId((String) r.get("id"));
        m.setName((String) r.get("name"));
        m.setDisplayName((String) r.get("display_name"));
        m.setAppName((String) r.get("app_name"));
        m.setTableName((String) r.get("table_name"));
        m.setKernelModel(r.get("kernel_model") instanceof Number && ((Number) r.get("kernel_model")).intValue() == 1);
        m.setActiveVersionId((String) r.get("active_version_id"));
        String ls = (String) r.get("lifecycle_state");
        if (ls != null) m.setLifecycleState(ModelAggregate.LifecycleState.valueOf(ls));
        m.setOfflineTime((String) r.get("offline_time"));
        m.setRetentionDays(r.get("retention_days") instanceof Number ? ((Number) r.get("retention_days")).intValue() : 0);
        m.setCreateUser((String) r.get("create_user"));
        m.setCreateDate((String) r.get("create_date"));
        m.setUpdateDate((String) r.get("update_date"));
        m.setVersion(r.get("version") instanceof Number ? ((Number) r.get("version")).intValue() : 1);
        return m;
    }

    private ModelVersion mapVersion(Map<String, Object> r) {
        ModelVersion v = new ModelVersion();
        v.setId((String) r.get("id"));
        v.setModelName((String) r.get("model_name"));
        v.setVersionNo((String) r.get("version_no"));
        v.setStatus(ModelVersion.Status.valueOf((String) r.get("status")));
        String json = (String) r.get("snapshot_json");
        v.setSnapshotJson(json);
        v.setPendingUiJson((String) r.get("pending_ui_json"));
        if (json != null) {
            try { v.setSnapshot(objectMapper.readValue(json, ModelSnapshot.class)); } catch (Exception ignored) {}
        }
        v.setValidationHash((String) r.get("validation_hash"));
        v.setValidatedAt((String) r.get("validated_at"));
        v.setCreatedBy((String) r.get("created_by"));
        v.setCreatedAt((String) r.get("created_at"));
        return v;
    }
}
