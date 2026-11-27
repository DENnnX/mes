package com.edu.scnu.domain.metamodel.service;

import com.edu.scnu.domain.metamodel.model.*;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.GroovyScriptShell;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 元模型语义与语法校验领域服务（含 Groovy 脚本语法静态编译检验）。
 */
public class MetaValidationDomainService {
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "string", "text", "integer", "long", "double", "bigdecimal", "decimal",
            "boolean", "date", "datetime", "selection", "enum", "dict",
            "manytoone", "onetomany", "manytomany", "json", "file"
    );

    public List<String> validateSnapshot(ModelSnapshot snapshot, Set<String> availableModels) {
        List<String> errors = new ArrayList<>();
        if (snapshot == null || snapshot.getName() == null || !IDENTIFIER_PATTERN.matcher(snapshot.getName()).matches()) {
            errors.add("模型标识 (name) 必须符合 ^[a-z][a-z0-9_]*$ 命名规范");
            return errors;
        }
        String table = snapshot.resolvedTableName();
        if (!IDENTIFIER_PATTERN.matcher(table).matches()) {
            errors.add("物理表名 (" + table + ") 必须仅由小写字母、数字和下划线组成");
        }
        if (snapshot.getParents().contains(snapshot.getName()) && !snapshot.isExtensionPackage()) {
            errors.add("检测到循环继承：模型不能继承自身 (" + snapshot.getName() + ")");
        }
        if (snapshot.getExtendModel() != null && !snapshot.getExtendModel().isBlank()
                && availableModels != null && !availableModels.contains(snapshot.getExtendModel())) {
            errors.add("扩展目标模型不存在: " + snapshot.getExtendModel());
        }
        // 允许子模型完全复用父类属性而自身只扩展服务/部分属性
        if (snapshot.getProperties().isEmpty() && snapshot.getParents().isEmpty() && snapshot.getExtendModel() == null) {
            errors.add("模型至少应包含一个业务属性字段 (properties) 或继承父模型 (parents)");
        }

        Set<String> seenProps = new HashSet<>();
        GroovyShell shell = GroovyScriptShell.create(new Binding());
        for (PropertyDef prop : snapshot.getProperties()) {
            if (prop.getName() == null || !IDENTIFIER_PATTERN.matcher(prop.getName()).matches()) {
                errors.add("属性标识 [" + prop.getName() + "] 必须符合 ^[a-z][a-z0-9_]*$ 命名规范");
                continue;
            }
            if (!seenProps.add(prop.getName())) {
                errors.add("属性标识重复: " + prop.getName());
            }
            String dt = prop.getDataType() == null ? "" : prop.getDataType().toLowerCase();
            if (!SUPPORTED_TYPES.contains(dt)) {
                errors.add("属性 [" + prop.getName() + "] 使用了不受支持的数据类型: " + prop.getDataType());
            }
            if (prop.isManyToOne()) {
                if (prop.getRelateModel() == null || prop.getRelateModel().isBlank()) {
                    errors.add("ManyToOne 属性 [" + prop.getName() + "] 缺少 relateModel 目标模型声明");
                } else if (availableModels != null && !availableModels.contains(prop.getRelateModel())
                        && !prop.getRelateModel().equals(snapshot.getName())) {
                    errors.add("ManyToOne 属性 [" + prop.getName() + "] 引用了不存在的目标模型: " + prop.getRelateModel());
                }
            }
            if (prop.isOneToMany()) {
                if (prop.getRelateModel() == null || prop.getInverseName() == null || prop.getInverseName().isBlank()) {
                    errors.add("OneToMany 属性 [" + prop.getName() + "] 必须声明 relateModel 与 inverseName 反向关联字段");
                }
            }
            if (prop.isManyToMany()) {
                if (prop.getRelateModel() == null || prop.getRelateModel().isBlank()) {
                    errors.add("ManyToMany 属性 [" + prop.getName() + "] 缺少 relateModel 目标模型声明");
                } else if (availableModels != null && !availableModels.contains(prop.getRelateModel())) {
                    errors.add("ManyToMany 属性 [" + prop.getName() + "] 引用了不存在的目标模型: " + prop.getRelateModel());
                }
            }
            if ("dict".equals(dt) && (prop.getDictType() == null || prop.getDictType().isBlank())) {
                errors.add("Dict 字典属性 [" + prop.getName() + "] 必须声明 dictType");
            }
            if (prop.isSelection() && prop.getValues().isEmpty()
                    && (prop.getSelectionMethod() == null || prop.getSelectionMethod().isBlank())
                    && (prop.getDictType() == null || prop.getDictType().isBlank())) {
                errors.add("Selection 枚举属性 [" + prop.getName() + "] 缺少选项集合 values 或 selectionMethod / dictType");
            }
            if (prop.getComputeScript() != null && !prop.getComputeScript().isBlank()) {
                try {
                    shell.parse(prop.getComputeScript());
                } catch (Exception ex) {
                    errors.add("计算属性 [" + prop.getName() + "] computeScript 语法错误: " + ex.getMessage());
                }
            }
        }
        for (ServiceDef svc : snapshot.getServices()) {
            if (svc.getName() == null || svc.getName().isBlank()) {
                errors.add("模型服务缺少服务名 (name)");
                continue;
            }
            if (ServiceDef.TYPE_GROOVY_SCRIPT.equalsIgnoreCase(svc.getType())) {
                if (svc.getScriptContent() == null || svc.getScriptContent().isBlank()) {
                    errors.add("Groovy 脚本服务 [" + svc.getName() + "] 脚本内容不能为空");
                } else {
                    try {
                        shell.parse(svc.getScriptContent());
                    } catch (Exception ex) {
                        errors.add("Groovy 服务 [" + svc.getName() + "] 脚本语法错误: " + ex.getMessage());
                    }
                }
            } else if (ServiceDef.TYPE_STATE_TRANSITION.equalsIgnoreCase(svc.getType())) {
                if (svc.getTransitionRule() == null || svc.getTransitionRule().getToState() == null) {
                    errors.add("声明式状态转移服务 [" + svc.getName() + "] 必须配置 transitionRule.toState");
                } else if (svc.getTransitionRule().getGuardScript() != null && !svc.getTransitionRule().getGuardScript().isBlank()) {
                    try {
                        shell.parse(svc.getTransitionRule().getGuardScript());
                    } catch (Exception ex) {
                        errors.add("状态转移服务 [" + svc.getName() + "] guardScript 语法错误: " + ex.getMessage());
                    }
                }
            }
        }

        return errors;
    }

    public void validateOrThrow(ModelSnapshot snapshot, Set<String> availableModels) {
        List<String> errors = validateSnapshot(snapshot, availableModels);
        if (!errors.isEmpty()) {
            throw DomainException.invalidParams("元模型定义校验未通过，共发现 " + errors.size() + " 处错误", errors);
        }
    }
}
