package com.edu.scnu.infrastructure.persistence.sqlite;

import com.edu.scnu.domain.metamodel.model.*;
import com.edu.scnu.domain.metamodel.repository.FoundationRepository;
import com.edu.scnu.domain.shared.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Repository
public class SqliteFoundationRepository implements FoundationRepository {
    private final JdbcTemplate jdbcTemplate;

    public SqliteFoundationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 数据字典 ====================

    @Override
    public List<DictTypeDef> findAllDictTypes(String tenantId) {
        String sql = (tenantId != null && !tenantId.isBlank())
                ? "SELECT * FROM meta_dict_type WHERE tenant_id = ? OR tenant_id = 'rbac_tenant_root' ORDER BY type_code"
                : "SELECT * FROM meta_dict_type ORDER BY type_code";
        List<Map<String, Object>> rows = (tenantId != null && !tenantId.isBlank())
                ? jdbcTemplate.queryForList(sql, tenantId) : jdbcTemplate.queryForList(sql);
        List<DictTypeDef> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapDictType(r));
        return res;
    }

    @Override
    public Optional<DictTypeDef> findDictTypeByCode(String typeCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_dict_type WHERE type_code = ?", typeCode);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapDictType(rows.get(0)));
    }

    @Override
    public void saveDictType(DictTypeDef d) {
        String id = d.getId() != null ? d.getId() : "dt_" + d.getTypeCode();
        jdbcTemplate.update(
                "INSERT INTO meta_dict_type (id, type_code, name, status, remark, tenant_id) VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(type_code) DO UPDATE SET name=excluded.name, status=excluded.status, remark=excluded.remark",
                id, d.getTypeCode(), d.getName(), d.getStatus(), d.getRemark(), d.getTenantId());
    }

    @Override
    public void deleteDictType(String typeCode) {
        jdbcTemplate.update("DELETE FROM meta_dict_item WHERE type_code = ?", typeCode);
        jdbcTemplate.update("DELETE FROM meta_dict_type WHERE type_code = ?", typeCode);
    }

    @Override
    public List<DictItemDef> findDictItemsByType(String typeCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM meta_dict_item WHERE type_code = ? AND status = 'ACTIVE' ORDER BY sequence ASC, item_value ASC",
                typeCode);
        List<DictItemDef> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapDictItem(r));
        return res;
    }

    @Override
    public List<DictItemDef> findAllDictItems() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM meta_dict_item ORDER BY type_code ASC, sequence ASC");
        List<DictItemDef> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapDictItem(r));
        return res;
    }

    @Override
    public void saveDictItem(DictItemDef item) {
        String id = item.getId() != null ? item.getId() : "di_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO meta_dict_item (id, type_code, item_value, item_label, sequence, color, status, tenant_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(type_code, item_value) DO UPDATE SET item_label=excluded.item_label, sequence=excluded.sequence, " +
                        "color=excluded.color, status=excluded.status",
                id, item.getTypeCode(), item.getItemValue(), item.getItemLabel(), item.getSequence(), item.getColor(), item.getStatus(), item.getTenantId());
    }

    @Override
    public void deleteDictItem(String id) {
        jdbcTemplate.update("DELETE FROM meta_dict_item WHERE id = ?", id);
    }

    // ==================== 通用编码规则 ====================

    @Override
    public List<CodeRuleDef> findAllCodeRules(String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_code_rule ORDER BY code");
        List<CodeRuleDef> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapCodeRule(r));
        return res;
    }

    @Override
    public Optional<CodeRuleDef> findCodeRuleByCode(String code) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_code_rule WHERE code = ?", code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapCodeRule(rows.get(0)));
    }

    @Override
    public void saveCodeRule(CodeRuleDef rule) {
        String id = rule.getId() != null ? rule.getId() : "cr_" + rule.getCode();
        jdbcTemplate.update(
                "INSERT INTO meta_code_rule (id, code, name, prefix, date_format, seq_length, status, tenant_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(code) DO UPDATE SET name=excluded.name, prefix=excluded.prefix, " +
                        "date_format=excluded.date_format, seq_length=excluded.seq_length, status=excluded.status",
                id, rule.getCode(), rule.getName(), rule.getPrefix(), rule.getDateFormat(), rule.getSeqLength(), rule.getStatus(), rule.getTenantId());
    }

    @Override
    public void deleteCodeRule(String code) {
        jdbcTemplate.update("DELETE FROM meta_code_seq WHERE rule_code = ?", code);
        jdbcTemplate.update("DELETE FROM meta_code_rule WHERE code = ?", code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public synchronized String nextSequenceCode(String code, String tenantId) {
        CodeRuleDef rule = findCodeRuleByCode(code)
                .orElseThrow(() -> DomainException.invalidParams("找不到编码规则: " + code));
        if (!"ACTIVE".equalsIgnoreCase(rule.getStatus())) {
            throw DomainException.stateGuardFailed("编码规则 [" + code + "] 已停用");
        }
        String effectiveTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : "rbac_tenant_root";
        String datePart = "";
        if (rule.getDateFormat() != null && !rule.getDateFormat().isBlank() && !"NONE".equalsIgnoreCase(rule.getDateFormat())) {
            datePart = LocalDate.now().format(DateTimeFormatter.ofPattern(rule.getDateFormat()));
        }
        String dateKey = datePart.isEmpty() ? "ALL" : datePart;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, cur_seq FROM meta_code_seq WHERE rule_code = ? AND date_key = ? AND tenant_id = ?",
                code, dateKey, effectiveTenant);
        int nextSeq;
        if (rows.isEmpty()) {
            nextSeq = 1;
            jdbcTemplate.update(
                    "INSERT INTO meta_code_seq (id, rule_code, date_key, cur_seq, tenant_id) VALUES (?, ?, ?, ?, ?)",
                    "seq_" + UUID.randomUUID().toString().substring(0, 10), code, dateKey, nextSeq, effectiveTenant);
        } else {
            nextSeq = ((Number) rows.get(0).get("cur_seq")).intValue() + 1;
            jdbcTemplate.update("UPDATE meta_code_seq SET cur_seq = ? WHERE id = ?", nextSeq, rows.get(0).get("id"));
        }

        int len = Math.max(1, Math.min(rule.getSeqLength(), 12));
        String prefix = rule.getPrefix() != null ? rule.getPrefix() : "";
        return prefix + datePart + String.format("%0" + len + "d", nextSeq);
    }

    // ==================== 文件附件 ====================

    @Override
    public void saveFileMeta(String id, String fileName, String contentType, long fileSize,
                             String storagePath, String tenantId, String userId, String now) {
        jdbcTemplate.update(
                "INSERT INTO meta_file (id, file_name, content_type, file_size, storage_path, tenant_id, create_user, create_date) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, fileName, contentType, fileSize, storagePath, tenantId, userId, now);
    }

    @Override
    public Optional<Map<String, Object>> findFileMetaById(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_file WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<Map<String, Object>> findAllFileMeta(String tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT id, file_name, content_type, file_size, tenant_id, create_user, create_date FROM meta_file WHERE tenant_id = ? ORDER BY create_date DESC",
                tenantId != null ? tenantId : "rbac_tenant_root");
    }

    private DictTypeDef mapDictType(Map<String, Object> r) {
        DictTypeDef d = new DictTypeDef();
        d.setId((String) r.get("id"));
        d.setTypeCode((String) r.get("type_code"));
        d.setName((String) r.get("name"));
        d.setStatus((String) r.get("status"));
        d.setRemark((String) r.get("remark"));
        d.setTenantId((String) r.get("tenant_id"));
        return d;
    }

    private DictItemDef mapDictItem(Map<String, Object> r) {
        DictItemDef i = new DictItemDef();
        i.setId((String) r.get("id"));
        i.setTypeCode((String) r.get("type_code"));
        i.setItemValue((String) r.get("item_value"));
        i.setItemLabel((String) r.get("item_label"));
        i.setSequence(r.get("sequence") instanceof Number ? ((Number) r.get("sequence")).intValue() : 10);
        i.setColor((String) r.get("color"));
        i.setStatus((String) r.get("status"));
        i.setTenantId((String) r.get("tenant_id"));
        return i;
    }

    private CodeRuleDef mapCodeRule(Map<String, Object> r) {
        CodeRuleDef c = new CodeRuleDef();
        c.setId((String) r.get("id"));
        c.setCode((String) r.get("code"));
        c.setName((String) r.get("name"));
        c.setPrefix((String) r.get("prefix"));
        c.setDateFormat((String) r.get("date_format"));
        c.setSeqLength(r.get("seq_length") instanceof Number ? ((Number) r.get("seq_length")).intValue() : 4);
        c.setStatus((String) r.get("status"));
        c.setTenantId((String) r.get("tenant_id"));
        return c;
    }
}
