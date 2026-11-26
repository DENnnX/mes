package com.edu.scnu.domain.runtime;

import java.util.*;

/**
 * 查询域 DSL（对标 com.sie.snest.engine.rule.Filter）。
 * 支持：
 * 1) Odoo / iidp 波兰数组语法：[["status", "=", "ACTIVE"], ["name", "like", "abc"]]
 *    以及 ["OR", ["a", "=", 1], ["b", "=", 2]] / ["AND", ...]
 * 2) 扁平键值兼容映射：{"status": "ACTIVE", "name": "%abc"}
 */
public class FilterDsl {
    public static final String OP_AND = "AND";
    public static final String OP_OR = "OR";

    private final List<Object> nodes = new ArrayList<>();

    public FilterDsl() {}

    public FilterDsl(List<Object> rawNodes) {
        if (rawNodes != null) {
            this.nodes.addAll(rawNodes);
        }
    }

    @SuppressWarnings("unchecked")
    public static FilterDsl parse(Object raw) {
        if (raw == null) return new FilterDsl();
        if (raw instanceof FilterDsl) return ((FilterDsl) raw).copy();
        if (raw instanceof List) {
            return new FilterDsl((List<Object>) raw);
        }
        if (raw instanceof Map) {
            FilterDsl dsl = new FilterDsl();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getKey() == null || e.getValue() == null || "".equals(e.getValue())) continue;
                String field = e.getKey().toString();
                Object val = e.getValue();
                if (val instanceof Map) {
                    for (Map.Entry<?, ?> sub : ((Map<?, ?>) val).entrySet()) {
                        String sop = sub.getKey().toString();
                        String mappedOp = "$like".equalsIgnoreCase(sop) ? "like" :
                                ("$eq".equalsIgnoreCase(sop) ? "=" : sop.replace("$", ""));
                        dsl.where(field, mappedOp, sub.getValue());
                    }
                } else if (val instanceof String && (((String) val).startsWith("%") || ((String) val).endsWith("%"))) {
                    dsl.where(field, "like", ((String) val).replace("%", ""));
                } else {
                    dsl.where(field, "=", val);
                }
            }
            return dsl;
        }
        return new FilterDsl();
    }

    public FilterDsl where(String field, String op, Object value) {
        nodes.add(Arrays.asList(field, op, value));
        return this;
    }

    public FilterDsl equal(String field, Object value) {
        return where(field, "=", value);
    }

    public FilterDsl like(String field, Object value) {
        return where(field, "like", value);
    }

    public FilterDsl in(String field, Collection<?> values) {
        return where(field, "in", values);
    }

    public FilterDsl and(FilterDsl other) {
        if (other == null || other.nodes.isEmpty()) return this;
        if (this.nodes.isEmpty()) {
            this.nodes.addAll(other.nodes);
            return this;
        }
        List<Object> combined = new ArrayList<>();
        combined.add(OP_AND);
        combined.add(new ArrayList<>(this.nodes));
        combined.add(new ArrayList<>(other.nodes));
        this.nodes.clear();
        this.nodes.add(combined);
        return this;
    }

    public FilterDsl or(FilterDsl other) {
        if (other == null || other.nodes.isEmpty()) return this;
        if (this.nodes.isEmpty()) {
            this.nodes.addAll(other.nodes);
            return this;
        }
        List<Object> combined = new ArrayList<>();
        combined.add(OP_OR);
        combined.add(new ArrayList<>(this.nodes));
        combined.add(new ArrayList<>(other.nodes));
        this.nodes.clear();
        this.nodes.add(combined);
        return this;
    }

    public List<Object> getNodes() {
        return nodes;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public FilterDsl copy() {
        return new FilterDsl(new ArrayList<>(this.nodes));
    }
}
