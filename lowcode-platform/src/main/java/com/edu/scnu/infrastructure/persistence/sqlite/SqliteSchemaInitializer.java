package com.edu.scnu.infrastructure.persistence.sqlite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 平台内核元数据与治理表初始化器（纯平台底座表结构，不含任何特定业务示例数据）。
 */
@Component
public class SqliteSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public SqliteSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initKernelTables() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_app (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT UNIQUE NOT NULL, " +
                "display_name TEXT NOT NULL, " +
                "category TEXT, " +
                "version TEXT, " +
                "tag TEXT, " +
                "description TEXT, " +
                "dependencies_json TEXT, " +
                "state TEXT DEFAULT 'INSTALLED', " +
                "create_user TEXT, " +
                "create_date TEXT, " +
                "update_date TEXT)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_model (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT UNIQUE NOT NULL, " +
                "display_name TEXT NOT NULL, " +
                "app_name TEXT NOT NULL, " +
                "table_name TEXT UNIQUE NOT NULL, " +
                "kernel_model INTEGER DEFAULT 0, " +
                "active_version_id TEXT, " +
                "lifecycle_state TEXT DEFAULT 'ONLINE_ACTIVE', " +
                "offline_time TEXT, " +
                "retention_days INTEGER DEFAULT 0, " +
                "create_user TEXT, " +
                "create_date TEXT, " +
                "update_date TEXT, " +
                "version INTEGER DEFAULT 1)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_model_version (" +
                "id TEXT PRIMARY KEY, " +
                "model_name TEXT NOT NULL, " +
                "version_no TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "snapshot_json TEXT NOT NULL, " +
                "pending_ui_json TEXT, " +
                "validation_hash TEXT, " +
                "validated_at TEXT, " +
                "created_by TEXT, " +
                "created_at TEXT, " +
                "UNIQUE(model_name, version_no))");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_model_ref (" +
                "id TEXT PRIMARY KEY, " +
                "model_name TEXT NOT NULL, " +
                "version_id TEXT NOT NULL, " +
                "source_type TEXT, " +
                "source_location TEXT, " +
                "target_field TEXT, " +
                "description TEXT)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_view (" +
                "id TEXT PRIMARY KEY, " +
                "view_key TEXT UNIQUE NOT NULL, " +
                "name TEXT NOT NULL, " +
                "model_name TEXT NOT NULL, " +
                "view_type TEXT NOT NULL, " +
                "mode TEXT DEFAULT 'primary', " +
                "inherit_from TEXT, " +
                "body_json TEXT NOT NULL, " +
                "priority INTEGER DEFAULT 10, " +
                "active INTEGER DEFAULT 1)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_menu (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT UNIQUE NOT NULL, " +
                "display_name TEXT NOT NULL, " +
                "app_name TEXT NOT NULL, " +
                "parent_id TEXT, " +
                "sequence INTEGER DEFAULT 10, " +
                "icon TEXT, " +
                "model_name TEXT, " +
                "view_keys TEXT, " +
                "filter_dsl TEXT, " +
                "active INTEGER DEFAULT 1)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_publish_record (" +
                "id TEXT PRIMARY KEY, " +
                "model_name TEXT NOT NULL, " +
                "from_version_id TEXT, " +
                "to_version_id TEXT NOT NULL, " +
                "to_version_no TEXT, " +
                "action_type TEXT NOT NULL, " +
                "impact_json TEXT, " +
                "operator_id TEXT, " +
                "operated_at TEXT)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_export_ticket (" +
                "ticket_id TEXT PRIMARY KEY, " +
                "model_name TEXT NOT NULL, " +
                "record_count INTEGER, " +
                "snapshot_json TEXT, " +
                "operator_id TEXT, " +
                "created_at TEXT)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_purge_apply (" +
                "id TEXT PRIMARY KEY, " +
                "model_name TEXT NOT NULL, " +
                "export_ticket TEXT NOT NULL, " +
                "apply_by TEXT NOT NULL, " +
                "apply_time TEXT, " +
                "confirm_by TEXT, " +
                "confirm_time TEXT, " +
                "status TEXT DEFAULT 'PENDING', " +
                "reject_reason TEXT)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_purge_audit (" +
                "audit_id TEXT PRIMARY KEY, " +
                "model_name TEXT NOT NULL, " +
                "table_name TEXT NOT NULL, " +
                "export_ticket TEXT NOT NULL, " +
                "apply_by TEXT NOT NULL, " +
                "confirm_by TEXT NOT NULL, " +
                "purged_record_count INTEGER, " +
                "purged_at TEXT)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS rbac_user (" +
                "id TEXT PRIMARY KEY, " +
                "username TEXT UNIQUE NOT NULL, " +
                "display_name TEXT NOT NULL, " +
                "password TEXT NOT NULL, " +
                "tenant_id TEXT DEFAULT 'rbac_tenant_root', " +
                "status TEXT DEFAULT 'ACTIVE', " +
                "role_codes_json TEXT)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS rbac_role (" +
                "id TEXT PRIMARY KEY, " +
                "code TEXT UNIQUE NOT NULL, " +
                "name TEXT NOT NULL, " +
                "data_scope TEXT DEFAULT 'ALL', " +
                "allowed_services_json TEXT)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS rbac_session (" +
                "token TEXT PRIMARY KEY, " +
                "user_id TEXT NOT NULL, " +
                "expires_at TEXT NOT NULL)");

        // 数据字典
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_dict_type (" +
                "id TEXT PRIMARY KEY, " +
                "type_code TEXT UNIQUE NOT NULL, " +
                "name TEXT NOT NULL, " +
                "status TEXT DEFAULT 'ACTIVE', " +
                "remark TEXT, " +
                "tenant_id TEXT DEFAULT 'rbac_tenant_root')");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_dict_item (" +
                "id TEXT PRIMARY KEY, " +
                "type_code TEXT NOT NULL, " +
                "item_value TEXT NOT NULL, " +
                "item_label TEXT NOT NULL, " +
                "sequence INTEGER DEFAULT 10, " +
                "color TEXT DEFAULT 'info', " +
                "status TEXT DEFAULT 'ACTIVE', " +
                "tenant_id TEXT DEFAULT 'rbac_tenant_root', " +
                "UNIQUE(type_code, item_value))");

        // 通用编码规则及流水序号
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_code_rule (" +
                "id TEXT PRIMARY KEY, " +
                "code TEXT UNIQUE NOT NULL, " +
                "name TEXT NOT NULL, " +
                "prefix TEXT DEFAULT '', " +
                "date_format TEXT DEFAULT 'yyyyMMdd', " +
                "seq_length INTEGER DEFAULT 4, " +
                "status TEXT DEFAULT 'ACTIVE', " +
                "tenant_id TEXT DEFAULT 'rbac_tenant_root')");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_code_seq (" +
                "id TEXT PRIMARY KEY, " +
                "rule_code TEXT NOT NULL, " +
                "date_key TEXT NOT NULL, " +
                "cur_seq INTEGER DEFAULT 0, " +
                "tenant_id TEXT DEFAULT 'rbac_tenant_root', " +
                "UNIQUE(rule_code, date_key, tenant_id))");

        // 文件附件元数据
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS meta_file (" +
                "id TEXT PRIMARY KEY, " +
                "file_name TEXT NOT NULL, " +
                "content_type TEXT, " +
                "file_size INTEGER DEFAULT 0, " +
                "storage_path TEXT NOT NULL, " +
                "tenant_id TEXT DEFAULT 'rbac_tenant_root', " +
                "create_user TEXT, " +
                "create_date TEXT)");

        // 多租户与组织树
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS rbac_tenant (" +
                "id TEXT PRIMARY KEY, " +
                "code TEXT UNIQUE NOT NULL, " +
                "name TEXT NOT NULL, " +
                "contact_name TEXT, " +
                "contact_phone TEXT, " +
                "status TEXT DEFAULT 'ACTIVE', " +
                "authorized_apps_json TEXT, " +
                "max_users INTEGER DEFAULT 100, " +
                "create_date TEXT)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS rbac_org (" +
                "id TEXT PRIMARY KEY, " +
                "tenant_id TEXT NOT NULL, " +
                "parent_id TEXT, " +
                "name TEXT NOT NULL, " +
                "org_type TEXT DEFAULT 'DEPT', " +
                "sequence INTEGER DEFAULT 10)");

        ensureColumn("meta_model_version", "pending_ui_json", "TEXT");
        ensureColumn("rbac_user", "org_id", "TEXT DEFAULT 'org_root'");
        ensureColumn("rbac_user", "phone", "TEXT");
        ensureColumn("rbac_user", "email", "TEXT");
        ensureColumn("rbac_tenant", "parent_tenant_id", "TEXT DEFAULT 'rbac_tenant_root'");
        ensureColumn("rbac_tenant", "authorized_menus_json", "TEXT");
        ensureColumn("rbac_role", "tenant_id", "TEXT DEFAULT 'rbac_tenant_root'");
        ensureColumn("rbac_role", "allowed_menus_json", "TEXT");
    }

    private void ensureColumn(String table, String column, String sqlType) {
        boolean exists = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")")
                .stream()
                .anyMatch(r -> column.equalsIgnoreCase(String.valueOf(r.get("name"))));
        if (!exists) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + sqlType);
        }
    }
}
