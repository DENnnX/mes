package com.sie.lowcode.infrastructure.persistence.sqlite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sie.lowcode.domain.uischema.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class SqliteUiSchemaRepository implements UiSchemaRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SqliteUiSchemaRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveView(UiViewDef view) {
        String id = view.getId() != null ? view.getId() : view.getKey();
        String bodyJson;
        try { bodyJson = objectMapper.writeValueAsString(view.getBody()); } catch (Exception e) { bodyJson = "{}"; }
        jdbcTemplate.update(
                "INSERT INTO meta_view (id, view_key, name, model_name, view_type, mode, inherit_from, body_json, priority, active) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(view_key) DO UPDATE SET name=excluded.name, model_name=excluded.model_name, " +
                        "view_type=excluded.view_type, mode=excluded.mode, inherit_from=excluded.inherit_from, " +
                        "body_json=excluded.body_json, priority=excluded.priority, active=excluded.active",
                id, view.getKey(), view.getName(), view.getModel(), view.getType(),
                view.getMode(), view.getInheritFrom(), bodyJson, view.getPriority(), view.isActive() ? 1 : 0);
    }

    @Override
    public Optional<UiViewDef> findViewByKey(String key) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_view WHERE view_key = ?", key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapView(rows.get(0)));
    }

    @Override
    public List<UiViewDef> findViewsByModel(String modelName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM meta_view WHERE model_name = ? ORDER BY priority ASC", modelName);
        List<UiViewDef> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapView(r));
        return res;
    }

    @Override
    public List<UiViewDef> findAllViews() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_view ORDER BY model_name, view_type");
        List<UiViewDef> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapView(r));
        return res;
    }

    @Override
    public void deleteViewsByModel(String modelName) {
        jdbcTemplate.update("DELETE FROM meta_view WHERE model_name = ?", modelName);
    }

    @Override
    public void saveMenu(MenuNode menu) {
        String id = menu.getId() != null ? menu.getId() : menu.getName();
        jdbcTemplate.update(
                "INSERT INTO meta_menu (id, name, display_name, app_name, parent_id, sequence, icon, model_name, view_keys, filter_dsl, active) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(name) DO UPDATE SET display_name=excluded.display_name, app_name=excluded.app_name, " +
                        "parent_id=excluded.parent_id, sequence=excluded.sequence, icon=excluded.icon, model_name=excluded.model_name, " +
                        "view_keys=excluded.view_keys, filter_dsl=excluded.filter_dsl, active=excluded.active",
                id, menu.getName(), menu.getDisplayName(), menu.getAppName(), menu.getParentId(),
                menu.getSequence(), menu.getIcon(), menu.getModel(), menu.getView(), menu.getFilter(), menu.isActive() ? 1 : 0);
    }

    @Override
    public Optional<MenuNode> findMenuByName(String name) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_menu WHERE name = ?", name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapMenu(rows.get(0)));
    }

    @Override
    public List<MenuNode> findAllMenus() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_menu ORDER BY sequence ASC, name ASC");
        List<MenuNode> res = new ArrayList<>();
        for (Map<String, Object> r : rows) res.add(mapMenu(r));
        return res;
    }

    @Override
    public void deleteMenusByModel(String modelName) {
        jdbcTemplate.update("DELETE FROM meta_menu WHERE model_name = ?", modelName);
    }

    @Override
    public void deleteMenusByApp(String appName) {
        jdbcTemplate.update("DELETE FROM meta_menu WHERE app_name = ?", appName);
    }

    private UiViewDef mapView(Map<String, Object> r) {
        UiViewDef v = new UiViewDef();
        v.setId((String) r.get("id"));
        v.setKey((String) r.get("view_key"));
        v.setName((String) r.get("name"));
        v.setModel((String) r.get("model_name"));
        v.setType((String) r.get("view_type"));
        v.setMode((String) r.get("mode"));
        v.setInheritFrom((String) r.get("inherit_from"));
        v.setPriority(r.get("priority") instanceof Number ? ((Number) r.get("priority")).intValue() : 10);
        v.setActive(!(r.get("active") instanceof Number) || ((Number) r.get("active")).intValue() == 1);
        try {
            String bj = (String) r.get("body_json");
            if (bj != null) v.setBody(objectMapper.readValue(bj, new TypeReference<>() {}));
        } catch (Exception ignored) {}
        return v;
    }

    private MenuNode mapMenu(Map<String, Object> r) {
        MenuNode m = new MenuNode();
        m.setId((String) r.get("id"));
        m.setName((String) r.get("name"));
        m.setDisplayName((String) r.get("display_name"));
        m.setAppName((String) r.get("app_name"));
        m.setParentId((String) r.get("parent_id"));
        m.setSequence(r.get("sequence") instanceof Number ? ((Number) r.get("sequence")).intValue() : 10);
        m.setIcon((String) r.get("icon"));
        m.setModel((String) r.get("model_name"));
        m.setView((String) r.get("view_keys"));
        m.setFilter((String) r.get("filter_dsl"));
        m.setActive(!(r.get("active") instanceof Number) || ((Number) r.get("active")).intValue() == 1);
        return m;
    }
}
