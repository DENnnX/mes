package com.sie.lowcode.infrastructure.persistence.sqlite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sie.lowcode.domain.metamodel.model.*;
import com.sie.lowcode.domain.runtime.*;
import com.sie.lowcode.domain.shared.DomainException;
import com.sie.lowcode.domain.shared.MetaContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 动态数据访问器实现（对标 sie-snest-engine 的 BussModelDataAccess）：
 * 封装统一参数与 @Validate 校验、乐观锁控制、严格多租户与数据域隔离、
 * 自动流水编码 rule 生成、数据字典与 ManyToOne 展开（useDisplayForModel）、
 * related 关联带出、compute 计算字段、OneToMany/ManyToMany 读写处理。
 */
@Component
public class DynamicDataAccessorImpl implements DynamicDataAccessor {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "id", "tenant_id", "bound_version_id", "version",
            "create_user", "create_date", "update_user", "update_date", "delete_flag"
    );
    private static final Set<String> KERNEL_TABLES = Set.of(
            "meta_app", "meta_model", "meta_publish_record", "meta_purge_audit"
    );

    @FunctionalInterface
    public interface InlineExprEvaluator {
        Object evaluate(MetaContext meta, String script, Map<String, Object> vars);
    }

    @FunctionalInterface
    public interface ServiceInvoker {
        Object invoke(MetaContext meta, String modelName, String serviceName, Map<String, Object> args);
    }

    private final JdbcTemplate jdbcTemplate;
    private final DynamicSqlEngine sqlEngine;
    private final ObjectMapper objectMapper;
    private Function<String, ModelSnapshot> modelLookup;
    private Function<String, List<DictItemDef>> dictLookup;
    private BiFunction<String, String, String> codeGenerator;
    private InlineExprEvaluator exprEvaluator;
    private ServiceInvoker serviceInvoker;

    public DynamicDataAccessorImpl(JdbcTemplate jdbcTemplate, DynamicSqlEngine sqlEngine, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlEngine = sqlEngine;
        this.objectMapper = objectMapper;
    }

    public void setModelLookup(Function<String, ModelSnapshot> modelLookup) { this.modelLookup = modelLookup; }
    public void setDictLookup(Function<String, List<DictItemDef>> dictLookup) { this.dictLookup = dictLookup; }
    public void setCodeGenerator(BiFunction<String, String, String> codeGenerator) { this.codeGenerator = codeGenerator; }
    public void setExprEvaluator(InlineExprEvaluator exprEvaluator) { this.exprEvaluator = exprEvaluator; }
    public void setServiceInvoker(ServiceInvoker serviceInvoker) { this.serviceInvoker = serviceInvoker; }

    @Override
    public void syncSchema(ModelSnapshot snapshot) {
        sqlEngine.syncSchema(snapshot);
    }

    @Override
    public void dropTable(String tableName) {
        sqlEngine.dropTable(tableName);
    }

    @Override
    public List<RecordData> search(ModelSnapshot snapshot, FilterDsl filter, List<String> properties,
                                   Integer limit, Integer offset, String order, boolean useDisplayForModel) {
        assertConcrete(snapshot);
        String table = sqlEngine.safeIdent(snapshot.resolvedTableName());
        List<Object> args = new ArrayList<>();
        String where = buildFullWhere(snapshot, filter, args);

        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table).append(where);
        String effectiveOrder = (order != null && !order.isBlank())
                ? sanitizeOrder(order, snapshot) : sanitizeOrder(snapshot.getOrderBy(), snapshot);
        if (!effectiveOrder.isBlank()) {
            sql.append(" ORDER BY ").append(effectiveOrder);
        }
        int safeLimit = (limit == null || limit <= 0) ? 50 : Math.min(limit, 500);
        int safeOffset = (offset == null || offset < 0) ? 0 : offset;
        sql.append(" LIMIT ? OFFSET ?");
        args.add(safeLimit);
        args.add(safeOffset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<RecordData> records = convertRowsToRecordData(snapshot, rows);
        hydrateVirtualAndDisplayFields(snapshot, records, useDisplayForModel);
        return records;
    }

    @Override
    public long count(ModelSnapshot snapshot, FilterDsl filter) {
        assertConcrete(snapshot);
        String table = sqlEngine.safeIdent(snapshot.resolvedTableName());
        List<Object> args = new ArrayList<>();
        String where = buildFullWhere(snapshot, filter, args);
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + where, Long.class, args.toArray());
        return total != null ? total : 0L;
    }

    @Override
    public long countAllActiveRecords(ModelSnapshot snapshot) {
        if (snapshot.isAbstractModel()) return 0L;
        String table = sqlEngine.safeIdent(snapshot.resolvedTableName());
        String sql = snapshot.isLogicDelete()
                ? "SELECT count(*) FROM " + table + " WHERE (delete_flag IS NULL OR delete_flag = 0)"
                : "SELECT count(*) FROM " + table;
        Long c = jdbcTemplate.queryForObject(sql, Long.class);
        return c != null ? c : 0L;
    }

    @Override
    public boolean exists(ModelSnapshot snapshot, FilterDsl filter) {
        return count(snapshot, filter) > 0;
    }

    @Override
    public RecordData findById(ModelSnapshot snapshot, String id, boolean includeChildren) {
        List<RecordData> list = findByIds(snapshot, Collections.singletonList(id), includeChildren);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<RecordData> findByIds(ModelSnapshot snapshot, List<String> ids, boolean includeChildren) {
        assertConcrete(snapshot);
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        FilterDsl filter = new FilterDsl().in("id", ids);
        List<RecordData> records = search(snapshot, filter, Collections.singletonList("*"), ids.size(), 0, null, true);
        if (includeChildren) {
            hydrateOneToManyAndManyToMany(snapshot, records);
        }
        return records;
    }

    @Override
    public RecordData computeDefaultValues(ModelSnapshot snapshot, Map<String, Object> partialContext) {
        RecordData defaults = new RecordData();
        MetaContext ctx = MetaContext.current();
        String now = TS_FMT.format(LocalDateTime.now());
        String uid = ctx != null ? ctx.getUserId() : MetaContext.SUPERUSER;
        String today = now.substring(0, 10);

        for (PropertyDef p : snapshot.getProperties()) {
            if (p.isOneToMany() || p.isManyToMany()) continue;
            Object val = null;
            if (p.getDefaultValue() != null) {
                val = resolveDefaultLiteral(p.getDefaultValue(), uid, now, today);
            } else if (p.getDefaultMethod() != null && !p.getDefaultMethod().isBlank()) {
                val = invokeDefaultMethod(snapshot, p.getDefaultMethod(), partialContext, ctx);
            } else if ("Boolean".equalsIgnoreCase(p.getDataType())) {
                val = false;
            }
            if (val != null) {
                defaults.put(p.getName(), val);
            }
        }
        return defaults;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<RecordData> create(ModelSnapshot snapshot, List<Map<String, Object>> valuesList) {
        assertConcrete(snapshot);
        if (valuesList == null || valuesList.isEmpty()) {
            throw DomainException.invalidParams("create 至少需要一条记录数据");
        }
        String table = sqlEngine.safeIdent(snapshot.resolvedTableName());
        MetaContext ctx = MetaContext.current();
        String now = TS_FMT.format(LocalDateTime.now());
        String uid = ctx != null ? ctx.getUserId() : MetaContext.SUPERUSER;
        String tenantId = ctx != null ? ctx.getTenantId() : MetaContext.DEFAULT_TENANT;

        List<RecordData> createdRecords = new ArrayList<>();
        for (Map<String, Object> rawValues : valuesList) {
            Map<String, Object> vals = new LinkedHashMap<>(rawValues != null ? rawValues : Collections.emptyMap());
            applyDefaultsCodeRulesAndValidate(snapshot, vals, true, tenantId);

            String newId = (vals.get("id") != null && !vals.get("id").toString().isBlank())
                    ? vals.get("id").toString()
                    : UUID.randomUUID().toString();
            String boundVer = snapshot.getName() + "@" + snapshot.getVersionNo();

            Map<String, Object> rowToInsert = new LinkedHashMap<>();
            rowToInsert.put("id", newId);
            rowToInsert.put("tenant_id", tenantId);
            rowToInsert.put("bound_version_id", vals.getOrDefault("bound_version_id", boundVer));
            rowToInsert.put("version", 1);
            rowToInsert.put("create_user", uid);
            rowToInsert.put("create_date", now);
            rowToInsert.put("update_user", uid);
            rowToInsert.put("update_date", now);
            rowToInsert.put("delete_flag", 0);

            for (PropertyDef prop : snapshot.getProperties()) {
                if (!prop.isStoredColumn()) continue;
                String col = sqlEngine.safeIdent(prop.resolvedColumnName());
                if (SYSTEM_FIELDS.contains(col)) continue;
                if (vals.containsKey(prop.getName())) {
                    rowToInsert.put(col, serializeFieldValue(prop, vals.get(prop.getName())));
                }
            }

            StringBuilder cols = new StringBuilder();
            StringBuilder qmarks = new StringBuilder();
            List<Object> params = new ArrayList<>();
            for (Map.Entry<String, Object> e : rowToInsert.entrySet()) {
                if (cols.length() > 0) { cols.append(", "); qmarks.append(", "); }
                cols.append(e.getKey());
                qmarks.append("?");
                params.add(e.getValue());
            }

            try {
                jdbcTemplate.update("INSERT INTO " + table + " (" + cols + ") VALUES (" + qmarks + ")", params.toArray());
            } catch (DataIntegrityViolationException ex) {
                throw DomainException.invalidParams("唯一性约束冲突或数据完整性校验失败: " + ex.getMostSpecificCause().getMessage());
            }

            processRelationWrites(snapshot, newId, vals);
            createdRecords.add(findById(snapshot, newId, true));
        }
        return createdRecords;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int update(ModelSnapshot snapshot, String id, Map<String, Object> values, Integer expectedVersion) {
        assertConcrete(snapshot);
        if (id == null || id.isBlank()) {
            throw DomainException.invalidParams("更新记录必须提供有效 id");
        }
        RecordData existing = findById(snapshot, id, false);
        if (existing == null) {
            return 0;
        }
        MetaContext ctx = MetaContext.current();
        String tenantId = ctx != null ? ctx.getTenantId() : MetaContext.DEFAULT_TENANT;
        Map<String, Object> vals = values != null ? new LinkedHashMap<>(values) : new LinkedHashMap<>();
        applyDefaultsCodeRulesAndValidate(snapshot, vals, false, tenantId);

        String table = sqlEngine.safeIdent(snapshot.resolvedTableName());
        String uid = ctx != null ? ctx.getUserId() : MetaContext.SUPERUSER;
        String now = TS_FMT.format(LocalDateTime.now());

        StringBuilder setClause = new StringBuilder("update_user = ?, update_date = ?, version = version + 1");
        List<Object> params = new ArrayList<>();
        params.add(uid);
        params.add(now);

        for (PropertyDef prop : snapshot.getProperties()) {
            if (!prop.isStoredColumn() || prop.isReadonly()) continue;
            if (!vals.containsKey(prop.getName())) continue;
            String col = sqlEngine.safeIdent(prop.resolvedColumnName());
            if (SYSTEM_FIELDS.contains(col)) continue;
            setClause.append(", ").append(col).append(" = ?");
            params.add(serializeFieldValue(prop, vals.get(prop.getName())));
        }

        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ").append(setClause)
                .append(" WHERE id = ?");
        params.add(id);
        if (expectedVersion != null) {
            sql.append(" AND version = ?");
            params.add(expectedVersion);
        }

        int updated;
        try {
            updated = jdbcTemplate.update(sql.toString(), params.toArray());
        } catch (DataIntegrityViolationException ex) {
            throw DomainException.invalidParams("唯一性约束冲突: " + ex.getMostSpecificCause().getMessage());
        }
        if (updated > 0) {
            processRelationWrites(snapshot, id, vals);
        }
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int delete(ModelSnapshot snapshot, List<String> ids) {
        assertConcrete(snapshot);
        if (ids == null || ids.isEmpty()) return 0;
        String table = sqlEngine.safeIdent(snapshot.resolvedTableName());

        // 先按租户归属确认目标存在权（防止跨租户越权删除）
        FilterDsl scopeCheck = new FilterDsl().in("id", ids);
        List<RecordData> scoped = search(snapshot, scopeCheck, Collections.singletonList("id"), ids.size(), 0, null, false);
        List<String> scopedIds = scoped.stream().map(RecordData::getId).filter(Objects::nonNull).toList();
        if (scopedIds.isEmpty()) return 0;

        checkForeignReferencesBeforeDelete(snapshot, scopedIds);

        // 清理 ManyToMany 中间关联表
        String ph = String.join(",", Collections.nCopies(scopedIds.size(), "?"));
        for (PropertyDef prop : snapshot.getProperties()) {
            if (prop.isManyToMany()) {
                String jt = sqlEngine.safeIdent(prop.resolvedJoinTable(snapshot.getName()));
                String jc = sqlEngine.safeIdent(prop.resolvedJoinColumn(snapshot.getName()));
                jdbcTemplate.update("DELETE FROM " + jt + " WHERE " + jc + " IN (" + ph + ")", scopedIds.toArray());
            }
        }

        if (snapshot.isLogicDelete()) {
            String now = TS_FMT.format(LocalDateTime.now());
            List<Object> params = new ArrayList<>();
            params.add(now);
            params.addAll(scopedIds);
            return jdbcTemplate.update("UPDATE " + table + " SET delete_flag = 1, update_date = ? WHERE id IN (" + ph + ")", params.toArray());
        } else {
            return jdbcTemplate.update("DELETE FROM " + table + " WHERE id IN (" + ph + ")", scopedIds.toArray());
        }
    }

    @Override
    public List<Map<String, Object>> lookup(ModelSnapshot targetSnapshot, String keyword, FilterDsl filter, int limit) {
        assertConcrete(targetSnapshot);
        FilterDsl effectiveFilter = filter != null ? filter.copy() : new FilterDsl();
        PropertyDef displayProp = targetSnapshot.findDisplayProperty();
        if (keyword != null && !keyword.isBlank() && displayProp != null) {
            effectiveFilter.like(displayProp.getName(), keyword.trim());
        }
        List<RecordData> rows = search(targetSnapshot, effectiveFilter, Collections.singletonList("*"), limit, 0, null, false);
        List<Map<String, Object>> options = new ArrayList<>();
        for (RecordData row : rows) {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("id", row.getId());
            opt.put("value", row.getId());
            opt.put("label", formatRecordDisplayName(targetSnapshot, row));
            options.add(opt);
        }
        return options;
    }

    @Override
    public List<RecordData> exportAll(ModelSnapshot snapshot) {
        if (snapshot.isAbstractModel()) return Collections.emptyList();
        String table = sqlEngine.safeIdent(snapshot.resolvedTableName());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + table);
        return convertRowsToRecordData(snapshot, rows);
    }

    // ==================== Private Helpers ====================

    private void assertConcrete(ModelSnapshot snapshot) {
        if (snapshot != null && snapshot.isAbstractModel()) {
            throw DomainException.stateGuardFailed("抽象模型 [" + snapshot.getName() + "] 不含物理表，禁止直接读写");
        }
    }

    private String buildFullWhere(ModelSnapshot snapshot, FilterDsl filter, List<Object> args) {
        List<String> parts = new ArrayList<>();
        if (snapshot.isLogicDelete()) {
            parts.add("(delete_flag IS NULL OR delete_flag = 0)");
        }
        MetaContext ctx = MetaContext.current();
        if (ctx != null && !MetaContext.SUPERUSER.equals(ctx.getUserId())
                && !KERNEL_TABLES.contains(snapshot.resolvedTableName())) {
            if (ctx.getVisibleTenantIds() != null && !ctx.getVisibleTenantIds().isEmpty()) {
                String ph = String.join(",", Collections.nCopies(ctx.getVisibleTenantIds().size(), "?"));
                parts.add("tenant_id IN (" + ph + ")");
                args.addAll(ctx.getVisibleTenantIds());
            } else if (ctx.getTenantId() != null && !ctx.getTenantId().isBlank()) {
                parts.add("tenant_id = ?");
                args.add(ctx.getTenantId());
            }
            if ("SELF".equalsIgnoreCase(ctx.getDataScope()) && snapshot.isAutoLog()) {
                parts.add("create_user = ?");
                args.add(ctx.getUserId());
            }
        }
        String dslWhere = sqlEngine.compileFilter(filter, snapshot, args);
        if (!dslWhere.isEmpty()) {
            parts.add(dslWhere);
        }
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    private String sanitizeOrder(String orderBy, ModelSnapshot snapshot) {
        if (orderBy == null || orderBy.isBlank()) return "";
        String[] parts = orderBy.trim().split("\\s+");
        String field = parts[0];
        String dir = parts.length > 1 && "ASC".equalsIgnoreCase(parts[1]) ? "ASC" : "DESC";
        try {
            return sqlEngine.resolveColumn(field, snapshot) + " " + dir;
        } catch (DomainException e) {
            return "create_date DESC";
        }
    }

    private Object resolveDefaultLiteral(Object raw, String uid, String now, String today) {
        if (!(raw instanceof String)) return raw;
        String s = raw.toString();
        if ("${currentUser}".equals(s)) return uid;
        if ("${now}".equals(s)) return now;
        if ("${today}".equals(s)) return today;
        return raw;
    }

    private Object invokeDefaultMethod(ModelSnapshot snapshot, String methodOrExpr, Map<String, Object> ctxArgs, MetaContext meta) {
        if (snapshot.getService(methodOrExpr) != null && serviceInvoker != null) {
            return serviceInvoker.invoke(meta, snapshot.getName(), methodOrExpr, ctxArgs != null ? ctxArgs : Collections.emptyMap());
        }
        if (exprEvaluator != null) {
            return exprEvaluator.evaluate(meta, methodOrExpr, ctxArgs);
        }
        return null;
    }

    private void applyDefaultsCodeRulesAndValidate(ModelSnapshot snapshot, Map<String, Object> vals, boolean isCreate, String tenantId) {
        List<String> errors = new ArrayList<>();
        MetaContext ctx = MetaContext.current();
        String now = TS_FMT.format(LocalDateTime.now());
        String uid = ctx != null ? ctx.getUserId() : MetaContext.SUPERUSER;
        String today = now.substring(0, 10);

        for (PropertyDef p : snapshot.getProperties()) {
            if (p.isOneToMany() || p.isManyToMany()) continue;
            Object val = vals.get(p.getName());
            if (isCreate && (val == null || "".equals(val.toString().trim()))) {
                if (p.getCodeRule() != null && !p.getCodeRule().isBlank() && codeGenerator != null) {
                    val = codeGenerator.apply(p.getCodeRule(), tenantId);
                    vals.put(p.getName(), val);
                } else if (p.getDefaultValue() != null) {
                    val = resolveDefaultLiteral(p.getDefaultValue(), uid, now, today);
                    vals.put(p.getName(), val);
                } else if (p.getDefaultMethod() != null && !p.getDefaultMethod().isBlank()) {
                    val = invokeDefaultMethod(snapshot, p.getDefaultMethod(), vals, ctx);
                    if (val != null) vals.put(p.getName(), val);
                }
            }
            if (isCreate && p.isRequired() && (val == null || "".equals(val.toString().trim()))) {
                errors.add("字段 [" + p.getDisplayName() + " (" + p.getName() + ")] 为必填项");
                continue;
            }
            if (val != null && !"".equals(val.toString().trim())) {
                validateValueAgainstProperty(p, val, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw DomainException.invalidParams("数据输入校验未通过", errors);
        }
    }

    private void validateValueAgainstProperty(PropertyDef p, Object val, List<String> errors) {
        String dt = p.getDataType() != null ? p.getDataType().toLowerCase() : "string";
        if ("integer".equals(dt) || "long".equals(dt) || "double".equals(dt) || "bigdecimal".equals(dt) || "decimal".equals(dt)) {
            try {
                double num = (val instanceof Number) ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
                if (p.getMinValue() != null && num < p.getMinValue()) {
                    errors.add("字段 [" + p.getDisplayName() + "] 不能小于 " + p.getMinValue());
                }
                if (p.getMaxValue() != null && num > p.getMaxValue()) {
                    errors.add("字段 [" + p.getDisplayName() + "] 不能大于 " + p.getMaxValue());
                }
            } catch (NumberFormatException ex) {
                errors.add("字段 [" + p.getDisplayName() + "] 必须是合法数值");
            }
        }
        if ((p.isSelection() || p.isDict()) && (!p.getValues().isEmpty() || (p.isDict() && dictLookup != null))) {
            List<String> allowed = new ArrayList<>();
            if (!p.getValues().isEmpty()) {
                p.getValues().forEach(o -> allowed.add(o.getValue()));
            } else if (p.getDictType() != null) {
                dictLookup.apply(p.getDictType()).forEach(o -> allowed.add(o.getItemValue()));
            }
            if (!allowed.isEmpty()) {
                if (p.isMultiple() && val instanceof Collection<?>) {
                    for (Object item : (Collection<?>) val) {
                        if (!allowed.contains(String.valueOf(item))) {
                            errors.add("字段 [" + p.getDisplayName() + "] 多选值 '" + item + "' 不在允许范围内");
                        }
                    }
                } else {
                    Object inner = (val instanceof Map && ((Map<?, ?>) val).containsKey("value")) ? ((Map<?, ?>) val).get("value") : val;
                    String strVal = String.valueOf(inner);
                    if (!allowed.contains(strVal)) {
                        errors.add("字段 [" + p.getDisplayName() + "] 取值 '" + strVal + "' 不在合法枚举范围内");
                    }
                }
            }
        }
        String strVal = val.toString();
        if (p.getLength() != null && p.getLength() > 0 && strVal.length() > p.getLength()) {
            errors.add("字段 [" + p.getDisplayName() + "] 长度超出上限 " + p.getLength());
        }
        if (p.getMaxLength() != null && p.getMaxLength() > 0 && strVal.length() > p.getMaxLength()) {
            errors.add("字段 [" + p.getDisplayName() + "] 长度不能超过 " + p.getMaxLength());
        }
        if (p.getMinLength() != null && p.getMinLength() > 0 && strVal.length() < p.getMinLength()) {
            errors.add("字段 [" + p.getDisplayName() + "] 长度不能少于 " + p.getMinLength());
        }
        if (p.isEmail() && !EMAIL_PATTERN.matcher(strVal).matches()) {
            errors.add("字段 [" + p.getDisplayName() + "] 必须是合法邮箱地址");
        }
        if (p.getValidationRegex() != null && !p.getValidationRegex().isBlank()) {
            if (!Pattern.matches(p.getValidationRegex(), strVal)) {
                String msg = (p.getValidationMessage() != null && !p.getValidationMessage().isBlank())
                        ? p.getValidationMessage()
                        : "字段 [" + p.getDisplayName() + "] 格式不符合校验规则";
                errors.add(msg);
            }
        }
    }

    private Object serializeFieldValue(PropertyDef prop, Object val) {
        if (val == null) return null;
        if (("Json".equalsIgnoreCase(prop.getDataType()) || prop.isMultiple() || prop.isFile()) && !(val instanceof String)) {
            try {
                return objectMapper.writeValueAsString(val);
            } catch (Exception e) {
                return val.toString();
            }
        }
        return sqlEngine.normalizeValue(val, prop);
    }

    private List<RecordData> convertRowsToRecordData(ModelSnapshot snapshot, List<Map<String, Object>> rows) {
        Map<String, PropertyDef> colToProp = new HashMap<>();
        for (PropertyDef p : snapshot.getProperties()) {
            if (p.isStoredColumn()) {
                colToProp.put(p.resolvedColumnName(), p);
            }
        }
        List<RecordData> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            RecordData rd = new RecordData();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String col = entry.getKey();
                Object val = entry.getValue();
                PropertyDef prop = colToProp.get(col);
                if (prop != null) {
                    if ("Boolean".equalsIgnoreCase(prop.getDataType()) && val instanceof Number) {
                        rd.put(prop.getName(), ((Number) val).intValue() != 0);
                    } else if (("Json".equalsIgnoreCase(prop.getDataType()) || prop.isMultiple() || prop.isFile())
                            && val instanceof String && (((String) val).startsWith("[") || ((String) val).startsWith("{"))) {
                        try {
                            rd.put(prop.getName(), objectMapper.readValue((String) val, Object.class));
                        } catch (Exception e) {
                            rd.put(prop.getName(), val);
                        }
                    } else {
                        rd.put(prop.getName(), val);
                    }
                } else {
                    rd.put(col, val);
                }
            }
            result.add(rd);
        }
        return result;
    }

    private void hydrateVirtualAndDisplayFields(ModelSnapshot snapshot, List<RecordData> records, boolean useDisplayForModel) {
        if (records.isEmpty()) return;

        // 1. 预先加载并缓存相关联的 ManyToOne 目标记录（同时服务 useDisplayForModel 与 related 带出）
        Map<String, Map<String, RecordData>> m2oTargetsByField = new HashMap<>();
        if (modelLookup != null) {
            for (PropertyDef prop : snapshot.getProperties()) {
                if (!prop.isManyToOne() || prop.getRelateModel() == null) continue;
                ModelSnapshot targetSnap = modelLookup.apply(prop.getRelateModel());
                if (targetSnap == null) continue;

                Set<String> refIds = new LinkedHashSet<>();
                for (RecordData rd : records) {
                    Object raw = rd.load(prop.getName());
                    if (raw instanceof String && !((String) raw).isBlank()) {
                        refIds.add((String) raw);
                    }
                }
                if (refIds.isEmpty()) continue;

                List<RecordData> targetRows = search(targetSnap, new FilterDsl().in("id", refIds),
                        Collections.singletonList("*"), refIds.size(), 0, null, false);
                Map<String, RecordData> rowMap = new HashMap<>();
                for (RecordData tr : targetRows) rowMap.put(tr.getId(), tr);
                m2oTargetsByField.put(prop.getName(), rowMap);

                if (useDisplayForModel) {
                    for (RecordData rd : records) {
                        Object raw = rd.load(prop.getName());
                        if (raw instanceof String && rowMap.containsKey(raw)) {
                            RecordData tr = rowMap.get(raw);
                            Map<String, Object> m2o = new LinkedHashMap<>();
                            m2o.put("id", raw);
                            m2o.put("displayName", formatRecordDisplayName(targetSnap, tr));
                            rd.put(prop.getName(), m2o);
                        }
                    }
                }
            }
        }

        // 2. 数据字典 Dict 文本回显翻译（useDisplayForModel）
        if (useDisplayForModel && dictLookup != null) {
            for (PropertyDef prop : snapshot.getProperties()) {
                if (!prop.isDict() || prop.getDictType() == null || prop.getDictType().isBlank()) continue;
                List<DictItemDef> items = dictLookup.apply(prop.getDictType());
                Map<String, DictItemDef> dictMap = new HashMap<>();
                for (DictItemDef it : items) dictMap.put(it.getItemValue(), it);

                for (RecordData rd : records) {
                    Object raw = rd.load(prop.getName());
                    if (raw == null) continue;
                    if (!prop.isMultiple()) {
                        DictItemDef matched = dictMap.get(raw.toString());
                        if (matched != null) {
                            Map<String, Object> dObj = new LinkedHashMap<>();
                            dObj.put("value", matched.getItemValue());
                            dObj.put("label", matched.getItemLabel());
                            dObj.put("displayName", matched.getItemLabel());
                            dObj.put("color", matched.getColor());
                            rd.put(prop.getName(), dObj);
                        }
                    } else if (raw instanceof Collection<?>) {
                        List<Map<String, Object>> multiList = new ArrayList<>();
                        for (Object item : (Collection<?>) raw) {
                            String sv = String.valueOf(item);
                            DictItemDef matched = dictMap.get(sv);
                            Map<String, Object> dObj = new LinkedHashMap<>();
                            dObj.put("value", sv);
                            dObj.put("label", matched != null ? matched.getItemLabel() : sv);
                            dObj.put("displayName", matched != null ? matched.getItemLabel() : sv);
                            dObj.put("color", matched != null ? matched.getColor() : "info");
                            multiList.add(dObj);
                        }
                        rd.put(prop.getName(), multiList);
                    }
                }
            }
        }

        // 3. related 关联带出（例如 dept_id.code）
        for (PropertyDef prop : snapshot.getProperties()) {
            if (prop.getRelated() == null || !prop.getRelated().contains(".")) continue;
            int dot = prop.getRelated().indexOf('.');
            String fkField = prop.getRelated().substring(0, dot);
            String targetField = prop.getRelated().substring(dot + 1);
            Map<String, RecordData> rowMap = m2oTargetsByField.get(fkField);
            if (rowMap == null) continue;

            for (RecordData rd : records) {
                Object fkRaw = rd.load(fkField);
                String fkId = (fkRaw instanceof Map) ? String.valueOf(((Map<?, ?>) fkRaw).get("id"))
                        : (fkRaw != null ? fkRaw.toString() : null);
                if (fkId != null && rowMap.containsKey(fkId)) {
                    rd.put(prop.getName(), rowMap.get(fkId).get(targetField));
                }
            }
        }

        // 4. 计算字段 computeMethod / computeScript
        MetaContext ctx = MetaContext.current();
        for (PropertyDef prop : snapshot.getProperties()) {
            boolean hasMethod = prop.getComputeMethod() != null && !prop.getComputeMethod().isBlank();
            boolean hasScript = prop.getComputeScript() != null && !prop.getComputeScript().isBlank();
            if (!hasMethod && !hasScript) continue;
            for (RecordData rd : records) {
                if (hasMethod && serviceInvoker != null && snapshot.getService(prop.getComputeMethod()) != null) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("row", rd);
                    args.put("id", rd.getId());
                    Object computed = serviceInvoker.invoke(ctx, snapshot.getName(), prop.getComputeMethod(), args);
                    rd.put(prop.getName(), computed);
                } else if (hasScript && exprEvaluator != null) {
                    Object computed = exprEvaluator.evaluate(ctx, prop.getComputeScript(), Map.of("row", rd));
                    rd.put(prop.getName(), computed);
                }
            }
        }
    }

    private void hydrateOneToManyAndManyToMany(ModelSnapshot snapshot, List<RecordData> parents) {
        if (parents.isEmpty() || modelLookup == null) return;
        List<String> parentIds = parents.stream().map(RecordData::getId).filter(Objects::nonNull).toList();
        if (parentIds.isEmpty()) return;

        // 1. OneToMany 子表明细加载
        for (PropertyDef prop : snapshot.getProperties()) {
            if (!prop.isOneToMany() || prop.getRelateModel() == null || prop.getInverseName() == null) continue;
            ModelSnapshot childSnap = modelLookup.apply(prop.getRelateModel());
            if (childSnap == null) continue;
            List<RecordData> children = search(childSnap, new FilterDsl().in(prop.getInverseName(), parentIds),
                    Collections.singletonList("*"), 500, 0, null, true);

            Map<String, List<RecordData>> grouped = new HashMap<>();
            for (RecordData child : children) {
                String pid = child.getStr(prop.getInverseName());
                if (pid != null) {
                    grouped.computeIfAbsent(pid, k -> new ArrayList<>()).add(child);
                }
            }
            for (RecordData parent : parents) {
                parent.put(prop.getName(), grouped.getOrDefault(parent.getId(), Collections.emptyList()));
            }
        }

        // 2. ManyToMany 中间表加载
        for (PropertyDef prop : snapshot.getProperties()) {
            if (!prop.isManyToMany() || prop.getRelateModel() == null) continue;
            ModelSnapshot targetSnap = modelLookup.apply(prop.getRelateModel());
            if (targetSnap == null) continue;

            String jt = sqlEngine.safeIdent(prop.resolvedJoinTable(snapshot.getName()));
            String jc = sqlEngine.safeIdent(prop.resolvedJoinColumn(snapshot.getName()));
            String ic = sqlEngine.safeIdent(prop.resolvedInverseJoinColumn());
            String ph = String.join(",", Collections.nCopies(parentIds.size(), "?"));
            List<Map<String, Object>> links = jdbcTemplate.queryForList(
                    "SELECT " + jc + " AS pid, " + ic + " AS tid FROM " + jt + " WHERE " + jc + " IN (" + ph + ")",
                    parentIds.toArray());

            Set<String> allTargetIds = new LinkedHashSet<>();
            Map<String, List<String>> pidToTids = new HashMap<>();
            for (Map<String, Object> lk : links) {
                String pid = String.valueOf(lk.get("pid"));
                String tid = String.valueOf(lk.get("tid"));
                allTargetIds.add(tid);
                pidToTids.computeIfAbsent(pid, k -> new ArrayList<>()).add(tid);
            }

            Map<String, RecordData> targetById = new HashMap<>();
            if (!allTargetIds.isEmpty()) {
                List<RecordData> targets = search(targetSnap, new FilterDsl().in("id", allTargetIds),
                        Collections.singletonList("*"), allTargetIds.size(), 0, null, true);
                for (RecordData tr : targets) targetById.put(tr.getId(), tr);
            }

            for (RecordData parent : parents) {
                List<RecordData> matched = new ArrayList<>();
                for (String tid : pidToTids.getOrDefault(parent.getId(), Collections.emptyList())) {
                    if (targetById.containsKey(tid)) matched.add(targetById.get(tid));
                }
                parent.put(prop.getName(), matched);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processRelationWrites(ModelSnapshot snapshot, String parentId, Map<String, Object> vals) {
        if (modelLookup == null) return;

        // OneToMany 命令三元组: [0,0,vals] | [1,id,vals] | [2,id]
        for (PropertyDef prop : snapshot.getProperties()) {
            if (!prop.isOneToMany() || !vals.containsKey(prop.getName())) continue;
            Object raw = vals.get(prop.getName());
            if (!(raw instanceof List<?>)) continue;
            ModelSnapshot childSnap = modelLookup.apply(prop.getRelateModel());
            if (childSnap == null || prop.getInverseName() == null) continue;

            for (Object item : (List<?>) raw) {
                if (item instanceof List<?>) {
                    List<?> cmd = (List<?>) item;
                    if (cmd.size() < 2) continue;
                    int op = ((Number) cmd.get(0)).intValue();
                    if (op == 0 && cmd.size() >= 3 && cmd.get(2) instanceof Map) {
                        Map<String, Object> cv = new LinkedHashMap<>((Map<String, Object>) cmd.get(2));
                        cv.put(prop.getInverseName(), parentId);
                        create(childSnap, Collections.singletonList(cv));
                    } else if (op == 1 && cmd.size() >= 3 && cmd.get(2) instanceof Map) {
                        Map<String, Object> cv = new LinkedHashMap<>((Map<String, Object>) cmd.get(2));
                        cv.put(prop.getInverseName(), parentId);
                        update(childSnap, String.valueOf(cmd.get(1)), cv, null);
                    } else if (op == 2) {
                        delete(childSnap, Collections.singletonList(String.valueOf(cmd.get(1))));
                    }
                } else if (item instanceof Map) {
                    Map<String, Object> cv = new LinkedHashMap<>((Map<String, Object>) item);
                    cv.put(prop.getInverseName(), parentId);
                    if (cv.get("id") != null && !cv.get("id").toString().isBlank()) {
                        String cid = cv.get("id").toString();
                        if (findById(childSnap, cid, false) != null) update(childSnap, cid, cv, null);
                        else create(childSnap, Collections.singletonList(cv));
                    } else {
                        create(childSnap, Collections.singletonList(cv));
                    }
                }
            }
        }

        // ManyToMany 关联维护: [4, targetId] 绑定 | [3, targetId] 解绑 | [6, 0, [ids]] 全量替换 | 或直接传 IDs/Map 列表
        for (PropertyDef prop : snapshot.getProperties()) {
            if (!prop.isManyToMany() || !vals.containsKey(prop.getName())) continue;
            Object raw = vals.get(prop.getName());
            if (!(raw instanceof List<?>)) continue;
            List<?> list = (List<?>) raw;

            String jt = sqlEngine.safeIdent(prop.resolvedJoinTable(snapshot.getName()));
            String jc = sqlEngine.safeIdent(prop.resolvedJoinColumn(snapshot.getName()));
            String ic = sqlEngine.safeIdent(prop.resolvedInverseJoinColumn());

            boolean isTupleCommands = !list.isEmpty() && list.get(0) instanceof List<?>;
            if (isTupleCommands) {
                for (Object item : list) {
                    if (!(item instanceof List<?>)) continue;
                    List<?> cmd = (List<?>) item;
                    if (cmd.size() < 2) continue;
                    int op = ((Number) cmd.get(0)).intValue();
                    if (op == 4) {
                        insertM2mLink(jt, jc, ic, parentId, String.valueOf(cmd.get(1)));
                    } else if (op == 3) {
                        jdbcTemplate.update("DELETE FROM " + jt + " WHERE " + jc + " = ? AND " + ic + " = ?",
                                parentId, String.valueOf(cmd.get(1)));
                    } else if (op == 6 && cmd.size() >= 3 && cmd.get(2) instanceof Collection<?>) {
                        jdbcTemplate.update("DELETE FROM " + jt + " WHERE " + jc + " = ?", parentId);
                        for (Object tid : (Collection<?>) cmd.get(2)) {
                            insertM2mLink(jt, jc, ic, parentId, String.valueOf(tid));
                        }
                    }
                }
            } else {
                // 直接传完整关联 ID 或记录数组 -> 替换当前集合
                jdbcTemplate.update("DELETE FROM " + jt + " WHERE " + jc + " = ?", parentId);
                for (Object item : list) {
                    String tid = (item instanceof Map) ? String.valueOf(((Map<?, ?>) item).get("id")) : String.valueOf(item);
                    if (tid != null && !tid.isBlank() && !"null".equals(tid)) {
                        insertM2mLink(jt, jc, ic, parentId, tid);
                    }
                }
            }
        }
    }

    private void insertM2mLink(String jt, String jc, String ic, String leftId, String rightId) {
        jdbcTemplate.update(
                "INSERT INTO " + jt + " (id, " + jc + ", " + ic + ") VALUES (?, ?, ?) ON CONFLICT(" + jc + ", " + ic + ") DO NOTHING",
                UUID.randomUUID().toString(), leftId, rightId);
    }

    private void checkForeignReferencesBeforeDelete(ModelSnapshot targetSnapshot, List<String> ids) {
        if (modelLookup == null) return;
        List<Map<String, Object>> modelNames = jdbcTemplate.queryForList("SELECT name FROM meta_model WHERE lifecycle_state != 'PURGED'");
        for (Map<String, Object> mRow : modelNames) {
            String otherName = String.valueOf(mRow.get("name"));
            ModelSnapshot other = modelLookup.apply(otherName);
            if (other == null || other.isAbstractModel()) continue;
            for (PropertyDef prop : other.getProperties()) {
                if (prop.isManyToOne() && targetSnapshot.getName().equals(prop.getRelateModel())) {
                    String otherTable = sqlEngine.safeIdent(other.resolvedTableName());
                    String col = sqlEngine.safeIdent(prop.resolvedColumnName());
                    String ph = String.join(",", Collections.nCopies(ids.size(), "?"));
                    Long cnt = jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM " + otherTable + " WHERE " + col + " IN (" + ph + ")"
                                    + (other.isLogicDelete() ? " AND (delete_flag IS NULL OR delete_flag = 0)" : ""),
                            Long.class, ids.toArray());
                    if (cnt != null && cnt > 0) {
                        if ("CASCADE".equalsIgnoreCase(prop.getOnDelete())) {
                            jdbcTemplate.update("DELETE FROM " + otherTable + " WHERE " + col + " IN (" + ph + ")", ids.toArray());
                        } else if ("SET_NULL".equalsIgnoreCase(prop.getOnDelete())) {
                            jdbcTemplate.update("UPDATE " + otherTable + " SET " + col + " = NULL WHERE " + col + " IN (" + ph + ")", ids.toArray());
                        } else {
                            throw DomainException.stateGuardFailed(
                                    "删除受阻：目标记录已被 [" + other.getDisplayName() + "." + prop.getDisplayName() + "] 的 " + cnt + " 条记录引用");
                        }
                    }
                }
            }
        }
    }

    private String formatRecordDisplayName(ModelSnapshot snapshot, RecordData row) {
        if (snapshot.getDisplayFormat() != null && !snapshot.getDisplayFormat().isBlank()) {
            String formatted = snapshot.getDisplayFormat();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                formatted = formatted.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue().toString() : "");
            }
            return formatted;
        }
        PropertyDef dp = snapshot.findDisplayProperty();
        if (dp != null && row.get(dp.getName()) != null) {
            return row.get(dp.getName()).toString();
        }
        return row.getId();
    }
}
