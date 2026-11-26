package com.edu.scnu.domain.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对标 sie-snest-sdk 的 BaseModel extends LinkedHashMap：
 * 值统一存储在 Map 结构中，支持跨上下文/Groovy/JSON 零序列化损耗透传及类型安全取值。
 */
public class RecordData extends LinkedHashMap<String, Object> {
    public RecordData() {}

    public RecordData(Map<String, Object> initial) {
        if (initial != null) {
            this.putAll(initial);
        }
    }

    public RecordData set(String property, Object value) {
        this.put(property, value);
        return this;
    }

    public Object load(String property) {
        return super.get(property);
    }

    public String getId() {
        return getStr("id");
    }

    public String getStr(String attr) {
        Object val = super.get(attr);
        if (val == null) return null;
        if (val instanceof Map) {
            Object id = ((Map<?, ?>) val).get("id");
            if (id != null) return id.toString();
            Object v = ((Map<?, ?>) val).get("value");
            if (v != null) return v.toString();
        }
        return val.toString();
    }

    public Integer getInt(String attr) {
        Object val = super.get(attr);
        if (val == null || "".equals(val)) return null;
        if (val instanceof Number) return ((Number) val).intValue();
        return Integer.parseInt(val.toString().trim());
    }

    public Double getDouble(String attr) {
        Object val = super.get(attr);
        if (val == null || "".equals(val)) return null;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return Double.parseDouble(val.toString().trim());
    }

    public Boolean getBool(String attr) {
        Object val = super.get(attr);
        if (val == null) return null;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).intValue() != 0;
        String s = val.toString().trim().toLowerCase();
        return "true".equals(s) || "1".equals(s);
    }
}
