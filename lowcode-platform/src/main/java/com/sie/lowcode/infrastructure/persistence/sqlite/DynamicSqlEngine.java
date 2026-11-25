package com.sie.lowcode.infrastructure.persistence.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sie.lowcode.domain.metamodel.model.IndexDef;
import com.sie.lowcode.domain.metamodel.model.ModelSnapshot;
import com.sie.lowcode.domain.metamodel.model.PropertyDef;
import com.sie.lowcode.domain.runtime.FilterDsl;
import com.sie.lowcode.domain.shared.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 动态 DDL 同步与 Filter DSL SQL 编译器（对标 ModelDBHandler & FilterCompiler）。
 */
@Component
public class DynamicSqlEngine {
    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Set<String> SYSTEM_COLUMNS = Set.of(
            "id", "tenant_id", "bound_version_id", "version",
            "create_user", "create_date", "update_user", "update_date", "delete_flag"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DynamicSqlEngine(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String safeIdent(String ident) {
        if (ident == null || !SAFE_IDENT.matcher(ident).matches()) {
            throw DomainException.invalidParams("非法 SQL 标识符: " + ident);
        }
        return ident;
    }

    /**
     * 增量同步物理表结构：不存在则建表，存在则增量 ADD COLUMN，并补全唯一索引、复合索引与多对多中间表。
     */
    public synchronized void syncSchema(ModelSnapshot snapshot) {
        if (snapshot.isAbstractModel() || snapshot.isExtensionPackage()) {
            return;
        }
        String table = safeIdent(snapshot.resolvedTableName());
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?",
                Integer.class, table);

        if (exists == null || exists == 0) {
            StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (")
                    .append("id TEXT PRIMARY KEY, ")
                    .append("tenant_id TEXT DEFAULT 'rbac_tenant_root', ")
                    .append("bound_version_id TEXT, ")
                    .append("version INTEGER DEFAULT 1, ")
                    .append("create_user TEXT, ")
                    .append("create_date TEXT, ")
                    .append("update_user TEXT, ")
                    .append("update_date TEXT, ")
                    .append("delete_flag INTEGER DEFAULT 0");

            for (PropertyDef prop : snapshot.getProperties()) {
                if (!prop.isStoredColumn()) continue;
                String col = safeIdent(prop.resolvedColumnName());
                if (SYSTEM_COLUMNS.contains(col)) continue;
                ddl.append(", ").append(col).append(" ").append(prop.toSqliteType());
            }
            ddl.append(")");
            jdbcTemplate.execute(ddl.toString());
        } else {
            Set<String> existingCols = getExistingColumns(table);
            for (PropertyDef prop : snapshot.getProperties()) {
                if (!prop.isStoredColumn()) continue;
                String col = safeIdent(prop.resolvedColumnName());
                if (SYSTEM_COLUMNS.contains(col) || existingCols.contains(col.toLowerCase())) continue;
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + prop.toSqliteType());
            }
        }

        // 单列唯一索引、关联外键索引维护
        for (PropertyDef prop : snapshot.getProperties()) {
            if (!prop.isStoredColumn()) continue;
            String col = safeIdent(prop.resolvedColumnName());
            if (prop.isUnique()) {
                String idx = safeIdent("uk_" + table + "_" + col);
                jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + idx + " ON " + table + "(tenant_id, " + col + ")");
            } else if (prop.isManyToOne()) {
                String idx = safeIdent("idx_" + table + "_" + col);
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + idx + " ON " + table + "(" + col + ")");
            }
        }

        // 模型复合索引 / 联合唯一索引维护（唯一索引按 tenant_id 分区隔离）
        for (IndexDef idxDef : snapshot.getIndexes()) {
            if (idxDef.getColumnList() == null || idxDef.getColumnList().isEmpty()) continue;
            List<String> cols = new ArrayList<>();
            if (idxDef.isUnique()) {
                cols.add("tenant_id");
            }
            for (String f : idxDef.getColumnList()) {
                String c = resolveColumn(f, snapshot);
                if (!cols.contains(c)) cols.add(c);
            }
            String idxName = safeIdent((idxDef.getName() != null && !idxDef.getName().isBlank())
                    ? idxDef.getName()
                    : ((idxDef.isUnique() ? "uk_" : "idx_") + table + "_" + String.join("_", cols)));
            String kw = idxDef.isUnique() ? "CREATE UNIQUE INDEX IF NOT EXISTS " : "CREATE INDEX IF NOT EXISTS ";
            jdbcTemplate.execute(kw + idxName + " ON " + table + "(" + String.join(", ", cols) + ")");
        }

        // ManyToMany 中间关联表自动创建
        for (PropertyDef prop : snapshot.getProperties()) {
            if (!prop.isManyToMany()) continue;
            String jt = safeIdent(prop.resolvedJoinTable(snapshot.getName()));
            String jc = safeIdent(prop.resolvedJoinColumn(snapshot.getName()));
            String ic = safeIdent(prop.resolvedInverseJoinColumn());
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + jt + " (" +
                    "id TEXT PRIMARY KEY, " +
                    jc + " TEXT NOT NULL, " +
                    ic + " TEXT NOT NULL, " +
                    "UNIQUE(" + jc + ", " + ic + "))");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + jt + "_l ON " + jt + "(" + jc + ")");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + jt + "_r ON " + jt + "(" + ic + ")");
        }
    }

    public synchronized void dropTable(String tableName) {
        String table = safeIdent(tableName);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
    }

    public Set<String> getExistingColumns(String tableName) {
        String table = safeIdent(tableName);
        Set<String> cols = new HashSet<>();
        List<Map<String, Object>> info = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
        for (Map<String, Object> row : info) {
            Object name = row.get("name");
            if (name != null) cols.add(name.toString().toLowerCase());
        }
        return cols;
    }

    /**
     * 编译 FilterDsl 为参数化 SQL 条件串（返回空字符串表示无额外过滤条件）。
     */
    public String compileFilter(FilterDsl filter, ModelSnapshot snapshot, List<Object> args) {
        if (filter == null || filter.isEmpty()) return "";
        return compileNodeList(filter.getNodes(), "AND", snapshot, args);
    }

    private String compileNodeList(List<?> nodes, String defaultConnector, ModelSnapshot snapshot, List<Object> args) {
        if (nodes == null || nodes.isEmpty()) return "";
        Object first = nodes.get(0);
        if (first instanceof String) {
            String rawOp = first.toString().trim().toUpperCase();
            if ("OR".equals(rawOp) || "AND".equals(rawOp) || "&".equals(rawOp) || "|".equals(rawOp)) {
                String sqlOp = ("OR".equals(rawOp) || "|".equals(rawOp)) ? "OR" : "AND";
                int[] pos = new int[]{1};
                List<String> parts = new ArrayList<>();
                while (pos[0] < nodes.size()) {
                    String sub = parsePolishPrefixNode(nodes, pos, snapshot, args);
                    if (!sub.isEmpty()) parts.add(sub);
                }
                return parts.isEmpty() ? "" : "(" + String.join(" " + sqlOp + " ", parts) + ")";
            }
            if (nodes.size() >= 2) {
                return compileTriple(nodes, snapshot, args);
            }
        }

        List<String> parts = new ArrayList<>();
        String pendingConn = defaultConnector;
        for (int i = 0; i < nodes.size(); i++) {
            Object item = nodes.get(i);
            if (item instanceof String) {
                String s = item.toString().trim().toUpperCase();
                if ("OR".equals(s) || "AND".equals(s) || "&".equals(s) || "|".equals(s)) {
                    pendingConn = ("OR".equals(s) || "|".equals(s)) ? "OR" : "AND";
                }
                continue;
            }
            String sub = compileSingleNode(item, snapshot, args);
            if (!sub.isEmpty()) {
                if (parts.isEmpty()) {
                    parts.add(sub);
                } else {
                    parts.add(pendingConn + " " + sub);
                    pendingConn = defaultConnector;
                }
            }
        }
        if (parts.isEmpty()) return "";
        return "(" + String.join(" ", parts) + ")";
    }

    /**
     * 递归波兰前缀消费器：完美支持 IIDP 扁平树格式 ["&", cond1, "|", cond2, cond3]。
     */
    private String parsePolishPrefixNode(List<?> nodes, int[] pos, ModelSnapshot snapshot, List<Object> args) {
        if (pos[0] >= nodes.size()) return "";
        Object token = nodes.get(pos[0]);
        if (token instanceof String) {
            String op = token.toString().trim().toUpperCase();
            if ("&".equals(op) || "AND".equals(op) || "|".equals(op) || "OR".equals(op)) {
                String sqlOp = ("|".equals(op) || "OR".equals(op)) ? "OR" : "AND";
                pos[0]++;
                String left = parsePolishPrefixNode(nodes, pos, snapshot, args);
                String right = parsePolishPrefixNode(nodes, pos, snapshot, args);
                if (left.isEmpty()) return right;
                if (right.isEmpty()) return left;
                return "(" + left + " " + sqlOp + " " + right + ")";
            }
        }
        pos[0]++;
        return compileSingleNode(token, snapshot, args);
    }

    private String compileSingleNode(Object node, ModelSnapshot snapshot, List<Object> args) {
        if (node instanceof List<?>) {
            List<?> list = (List<?>) node;
            if (list.isEmpty()) return "";
            return compileNodeList(list, "AND", snapshot, args);
        }
        return "";
    }

    private String compileTriple(List<?> triple, ModelSnapshot snapshot, List<Object> args) {
        String fieldName = String.valueOf(triple.get(0));
        String op = String.valueOf(triple.get(1)).trim().toLowerCase();
        Object value = triple.size() > 2 ? triple.get(2) : null;

        String col = resolveColumn(fieldName, snapshot);
        if (value == null && ("=".equals(op) || "is".equals(op) || "is_null".equals(op))) {
            return col + " IS NULL";
        }
        if (value == null && ("!=".equals(op) || "<>".equals(op) || "is not".equals(op) || "is_not_null".equals(op))) {
            return col + " IS NOT NULL";
        }
        switch (op) {
            case "=":
            case "eq":
                args.add(normalizeValue(value, snapshot.getProperty(fieldName)));
                return col + " = ?";
            case "!=":
            case "<>":
            case "ne":
                args.add(normalizeValue(value, snapshot.getProperty(fieldName)));
                return "(" + col + " IS NULL OR " + col + " != ?)";
            case ">":
            case "gt":
                args.add(value);
                return col + " > ?";
            case ">=":
            case "ge":
                args.add(value);
                return col + " >= ?";
            case "<":
            case "lt":
                args.add(value);
                return col + " < ?";
            case "<=":
            case "le":
                args.add(value);
                return col + " <= ?";
            case "like":
            case "ilike":
                args.add("%" + value + "%");
                return col + " LIKE ?";
            case "llike":
                args.add("%" + value);
                return col + " LIKE ?";
            case "rlike":
                args.add(value + "%");
                return col + " LIKE ?";
            case "not like":
                args.add("%" + value + "%");
                return "(" + col + " IS NULL OR " + col + " NOT LIKE ?)";
            case "in":
            case "not in": {
                Collection<?> colVals = value instanceof Collection ? (Collection<?>) value : List.of(value);
                if (colVals.isEmpty()) {
                    return "in".equals(op) ? "1 = 0" : "1 = 1";
                }
                String placeholders = String.join(",", Collections.nCopies(colVals.size(), "?"));
                args.addAll(colVals);
                return col + ("not in".equals(op) ? " NOT IN (" : " IN (") + placeholders + ")";
            }
            case "is":
                return col + " IS NULL";
            case "is not":
                return col + " IS NOT NULL";
            default:
                throw DomainException.invalidParams("不支持的 Filter 操作符: " + op);
        }
    }

    public String resolveColumn(String fieldName, ModelSnapshot snapshot) {
        if (SYSTEM_COLUMNS.contains(fieldName)) {
            return safeIdent(fieldName);
        }
        PropertyDef prop = snapshot.getProperty(fieldName);
        if (prop == null) {
            throw DomainException.invalidParams("模型 [" + snapshot.getName() + "] 中不存在字段: " + fieldName);
        }
        if (!prop.isStoredColumn()) {
            throw DomainException.invalidParams("字段 [" + fieldName + "] 为非物理存储字段，不支持 SQL 过滤");
        }
        return safeIdent(prop.resolvedColumnName());
    }

    public Object normalizeValue(Object val, PropertyDef prop) {
        if (val == null || prop == null) return val;
        if (prop.isMultiple()) {
            if (val instanceof String) return val;
            try {
                return objectMapper.writeValueAsString(val);
            } catch (Exception e) {
                return val.toString();
            }
        }
        String dt = prop.getDataType() != null ? prop.getDataType().toLowerCase() : "string";
        if ("".equals(val.toString().trim()) && ("integer".equals(dt) || "long".equals(dt)
                || "double".equals(dt) || "decimal".equals(dt) || "bigdecimal".equals(dt))) {
            return null;
        }
        if ("boolean".equals(dt)) {
            if (val instanceof Boolean) return ((Boolean) val) ? 1 : 0;
            if (val instanceof Number) return ((Number) val).intValue() != 0 ? 1 : 0;
            String s = val.toString().trim().toLowerCase();
            return ("true".equals(s) || "1".equals(s)) ? 1 : 0;
        }
        if (prop.isManyToOne() && val instanceof Map) {
            Object id = ((Map<?, ?>) val).get("id");
            return id != null ? id.toString() : null;
        }
        if ((prop.isDict() || prop.isSelection()) && val instanceof Map) {
            Object v = ((Map<?, ?>) val).get("value");
            if (v == null) v = ((Map<?, ?>) val).get("id");
            return v != null ? v.toString() : null;
        }
        if (prop.isFile() && val instanceof Map) {
            try {
                return objectMapper.writeValueAsString(val);
            } catch (Exception e) {
                return val.toString();
            }
        }
        return val;
    }
}
