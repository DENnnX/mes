package com.sie.lowcode.infrastructure.persistence.sqlite;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.sie.lowcode.domain.metamodel.model.AppAggregate;
import com.sie.lowcode.domain.metamodel.repository.AppRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class SqliteAppRepository implements AppRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SqliteAppRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(AppAggregate app) {
        String depsJson = writeJson(app.getDependencies());
        jdbcTemplate.update(
                "INSERT INTO meta_app (id, name, display_name, category, version, tag, description, dependencies_json, state, create_user, create_date, update_date) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(name) DO UPDATE SET display_name=excluded.display_name, category=excluded.category, " +
                        "version=excluded.version, tag=excluded.tag, description=excluded.description, dependencies_json=excluded.dependencies_json, " +
                        "state=excluded.state, update_date=excluded.update_date",
                app.getId(), app.getName(), app.getDisplayName(), app.getCategory(),
                app.getVersion(), app.getTag(), app.getDescription(), depsJson,
                app.getState(), app.getCreateUser(), app.getCreateDate(), app.getUpdateDate());
    }

    @Override
    public Optional<AppAggregate> findByName(String name) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_app WHERE name = ?", name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    @Override
    public List<AppAggregate> findAll() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM meta_app ORDER BY create_date ASC");
        List<AppAggregate> list = new ArrayList<>();
        for (Map<String, Object> r : rows) list.add(mapRow(r));
        return list;
    }

    private AppAggregate mapRow(Map<String, Object> r) {
        AppAggregate a = new AppAggregate();
        a.setId((String) r.get("id"));
        a.setName((String) r.get("name"));
        a.setDisplayName((String) r.get("display_name"));
        a.setCategory((String) r.get("category"));
        a.setVersion((String) r.get("version"));
        a.setTag((String) r.get("tag"));
        a.setDescription((String) r.get("description"));
        a.setState((String) r.get("state"));
        a.setCreateUser((String) r.get("create_user"));
        a.setCreateDate((String) r.get("create_date"));
        a.setUpdateDate((String) r.get("update_date"));
        try {
            String deps = (String) r.get("dependencies_json");
            if (deps != null) a.setDependencies(objectMapper.readValue(deps, new TypeReference<>() {}));
        } catch (Exception ignored) {}
        return a;
    }

    private String writeJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }
}
