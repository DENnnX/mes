package com.sie.lowcode.domain.metamodel.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

/**
 * 模型属性元数据定义（对标 engine PropertyMeta / @Property + @Validate + @Selection + @Dict + ER）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PropertyDef {
    private String name;
    private String columnName;
    private String displayName;
    private String description;
    private String tooltips;
    private String dataType = "String"; // String,Text,Integer,Long,Double,Decimal,Boolean,Date,DateTime,Selection,Dict,ManyToOne,OneToMany,ManyToMany,Json,File
    private Integer length;
    private Integer scale;
    private boolean required = false;
    private boolean readonly = false;
    private boolean unique = false;
    private boolean store = true;
    private boolean display = true; // false 表示兼容退役：新表单隐藏，历史数据契约保留
    private boolean displayForModel = false; // 等价 Odoo _rec_name
    private Object defaultValue;
    private String defaultMethod; // Groovy 表达式/服务名，用于新增时动态计算默认值
    private String widget;
    private String format;
    private String dateFormat;

    // 枚举与动态字典
    private List<SelectionOption> values = new ArrayList<>(); // Selection 枚举项
    private boolean multiple = false; // 支持 Selection/Dict 多选（List JSON 存储）
    private String selectionMethod;   // 动态调用模型自定义服务获取下拉列表
    private String dictType;          // 对标 @Dict(typeCode=...) 关联平台数据字典

    // 自动计算与关联带出
    private String related;       // 如 "dept_id.code" (通常配合 store=false)
    private String computeMethod; // 模型计算服务名
    private String computeScript; // 内联 Groovy 计算表达式

    // 自动流水编码规则
    private String codeRule;      // 关联 meta_code_rule.code，新增未填时自动生成编号

    // ER 关系字段
    private String relateModel;       // ManyToOne / OneToMany / ManyToMany 目标模型
    private String inverseName;       // OneToMany 对应子表的 ManyToOne 字段名
    private String onDelete = "RESTRICT"; // RESTRICT | SET_NULL | CASCADE
    private String joinTable;         // ManyToMany 中间表名
    private String joinColumn;        // ManyToMany 主模型外键列名
    private String inverseJoinColumn; // ManyToMany 目标模型外键列名

    // 校验规则（对标 @Validate）
    private Double minValue;
    private Double maxValue;
    private Integer minLength;
    private Integer maxLength;
    private boolean email = false;
    private String validationRegex;
    private String validationMessage;

    // 附件类型限制
    private String contentType; // File 类型限制，如 image/*,.pdf
    private boolean autoColumn = false;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SelectionOption {
        private String label;
        private String value;
        private String color;

        public SelectionOption() {}
        public SelectionOption(String label, String value, String color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
    }

    @JsonIgnore
    public String resolvedColumnName() {
        return (columnName != null && !columnName.isBlank()) ? columnName : name;
    }

    @JsonIgnore
    public boolean isManyToOne() {
        return "ManyToOne".equalsIgnoreCase(dataType);
    }

    @JsonIgnore
    public boolean isOneToMany() {
        return "OneToMany".equalsIgnoreCase(dataType);
    }

    @JsonIgnore
    public boolean isManyToMany() {
        return "ManyToMany".equalsIgnoreCase(dataType);
    }

    @JsonIgnore
    public boolean isDict() {
        return "Dict".equalsIgnoreCase(dataType) || (dictType != null && !dictType.isBlank());
    }

    @JsonIgnore
    public boolean isSelection() {
        return "Selection".equalsIgnoreCase(dataType) || "Enum".equalsIgnoreCase(dataType);
    }

    @JsonIgnore
    public boolean isFile() {
        return "File".equalsIgnoreCase(dataType);
    }

    @JsonIgnore
    public boolean isVirtualRelationOrCompute() {
        return (related != null && !related.isBlank())
                || (computeMethod != null && !computeMethod.isBlank())
                || (computeScript != null && !computeScript.isBlank());
    }

    @JsonIgnore
    public boolean isStoredColumn() {
        // OneToMany、ManyToMany、带 related/compute 且 store=false 的计算字段不建物理列
        return store && !isOneToMany() && !isManyToMany();
    }

    @JsonIgnore
    public String resolvedJoinTable(String ownerModel) {
        if (joinTable != null && !joinTable.isBlank()) return joinTable;
        return "rel_" + ownerModel + "_" + name;
    }

    @JsonIgnore
    public String resolvedJoinColumn(String ownerModel) {
        if (joinColumn != null && !joinColumn.isBlank()) return joinColumn;
        return ownerModel + "_id";
    }

    @JsonIgnore
    public String resolvedInverseJoinColumn() {
        if (inverseJoinColumn != null && !inverseJoinColumn.isBlank()) return inverseJoinColumn;
        return (relateModel != null ? relateModel : "target") + "_id";
    }

    @JsonIgnore
    public String toSqliteType() {
        if (dataType == null) return "TEXT";
        if (multiple) return "TEXT"; // 多选存 JSON 数组
        switch (dataType.toLowerCase()) {
            case "integer":
            case "long":
            case "boolean":
                return "INTEGER";
            case "double":
            case "bigdecimal":
            case "decimal":
                return "REAL";
            default:
                return "TEXT";
        }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public String getDisplayName() { return displayName != null ? displayName : name; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTooltips() { return tooltips; }
    public void setTooltips(String tooltips) { this.tooltips = tooltips; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Integer getLength() { return length; }
    public void setLength(Integer length) { this.length = length; }
    public Integer getScale() { return scale; }
    public void setScale(Integer scale) { this.scale = scale; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public boolean isReadonly() { return readonly; }
    public void setReadonly(boolean readonly) { this.readonly = readonly; }
    public boolean isUnique() { return unique; }
    public void setUnique(boolean unique) { this.unique = unique; }
    public boolean isStore() { return store; }
    public void setStore(boolean store) { this.store = store; }
    public boolean isDisplay() { return display; }
    public void setDisplay(boolean display) { this.display = display; }
    public boolean isDisplayForModel() { return displayForModel; }
    public void setDisplayForModel(boolean displayForModel) { this.displayForModel = displayForModel; }
    public Object getDefaultValue() { return defaultValue; }
    public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }
    public String getDefaultMethod() { return defaultMethod; }
    public void setDefaultMethod(String defaultMethod) { this.defaultMethod = defaultMethod; }
    public String getWidget() { return widget; }
    public void setWidget(String widget) { this.widget = widget; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getDateFormat() { return dateFormat; }
    public void setDateFormat(String dateFormat) { this.dateFormat = dateFormat; }
    public List<SelectionOption> getValues() { return values; }
    public void setValues(List<SelectionOption> values) { this.values = values != null ? values : new ArrayList<>(); }
    public boolean isMultiple() { return multiple; }
    public void setMultiple(boolean multiple) { this.multiple = multiple; }
    public String getSelectionMethod() { return selectionMethod; }
    public void setSelectionMethod(String selectionMethod) { this.selectionMethod = selectionMethod; }
    public String getDictType() { return dictType; }
    public void setDictType(String dictType) { this.dictType = dictType; }
    public String getRelated() { return related; }
    public void setRelated(String related) { this.related = related; }
    public String getComputeMethod() { return computeMethod; }
    public void setComputeMethod(String computeMethod) { this.computeMethod = computeMethod; }
    public String getComputeScript() { return computeScript; }
    public void setComputeScript(String computeScript) { this.computeScript = computeScript; }
    public String getCodeRule() { return codeRule; }
    public void setCodeRule(String codeRule) { this.codeRule = codeRule; }
    public String getRelateModel() { return relateModel; }
    public void setRelateModel(String relateModel) { this.relateModel = relateModel; }
    public String getInverseName() { return inverseName; }
    public void setInverseName(String inverseName) { this.inverseName = inverseName; }
    public String getOnDelete() { return onDelete; }
    public void setOnDelete(String onDelete) { this.onDelete = onDelete; }
    public String getJoinTable() { return joinTable; }
    public void setJoinTable(String joinTable) { this.joinTable = joinTable; }
    public String getJoinColumn() { return joinColumn; }
    public void setJoinColumn(String joinColumn) { this.joinColumn = joinColumn; }
    public String getInverseJoinColumn() { return inverseJoinColumn; }
    public void setInverseJoinColumn(String inverseJoinColumn) { this.inverseJoinColumn = inverseJoinColumn; }
    public Double getMinValue() { return minValue; }
    public void setMinValue(Double minValue) { this.minValue = minValue; }
    public Double getMaxValue() { return maxValue; }
    public void setMaxValue(Double maxValue) { this.maxValue = maxValue; }
    public Integer getMinLength() { return minLength; }
    public void setMinLength(Integer minLength) { this.minLength = minLength; }
    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
    public boolean isEmail() { return email; }
    public void setEmail(boolean email) { this.email = email; }
    public String getValidationRegex() { return validationRegex; }
    public void setValidationRegex(String validationRegex) { this.validationRegex = validationRegex; }
    public String getValidationMessage() { return validationMessage; }
    public void setValidationMessage(String validationMessage) { this.validationMessage = validationMessage; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public boolean isAutoColumn() { return autoColumn; }
    public void setAutoColumn(boolean autoColumn) { this.autoColumn = autoColumn; }
}
