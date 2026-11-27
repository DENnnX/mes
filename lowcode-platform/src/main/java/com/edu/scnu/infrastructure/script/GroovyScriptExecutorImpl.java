package com.edu.scnu.infrastructure.script;

import com.edu.scnu.domain.metamodel.model.ServiceDef;
import com.edu.scnu.domain.runtime.RecordData;
import com.edu.scnu.domain.runtime.RecordSet;
import com.edu.scnu.domain.runtime.ServiceActionResult;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.GroovyScriptShell;
import com.edu.scnu.domain.shared.MetaContext;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 动态模型服务执行引擎：
 * 1) 执行 GROOVY_SCRIPT 脚本服务（注入 env MetaContext、rs RecordSet、args 入参、currentUser、now）
 * 2) 执行零代码声明式 STATE_TRANSITION 状态转移服务（含守卫校验、Groovy guardScript 求值、乐观锁更新）
 */
@Component
public class GroovyScriptExecutorImpl implements RecordSet.ScriptServiceExecutor {
    private static final Logger log = LoggerFactory.getLogger(GroovyScriptExecutorImpl.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Object execute(RecordSet rs, ServiceDef serviceDef, Map<String, Object> args) {
        if (ServiceDef.TYPE_STATE_TRANSITION.equalsIgnoreCase(serviceDef.getType())) {
            return executeStateTransition(rs, serviceDef, args);
        }
        if (ServiceDef.TYPE_GROOVY_SCRIPT.equalsIgnoreCase(serviceDef.getType())) {
            return executeGroovyScript(rs, serviceDef, args);
        }
        throw DomainException.methodNotFound("未绑定可执行实现的扩展服务: " + rs.getModelName() + "." + serviceDef.getName());
    }

    private Object executeGroovyScript(RecordSet rs, ServiceDef serviceDef, Map<String, Object> args) {
        Binding binding = new Binding();
        binding.setVariable("env", rs.getMeta());
        binding.setVariable("rs", rs);
        binding.setVariable("args", args != null ? args : Collections.emptyMap());
        binding.setVariable("currentUser", rs.getMeta() != null ? rs.getMeta().getUserId() : "system");
        binding.setVariable("now", TS_FMT.format(LocalDateTime.now()));
        binding.setVariable("JsonRpcServiceResponse", ServiceActionResult.class);

        // 若提供了 id 参数，预加载当前记录作为 row 便利变量
        Object idObj = args != null ? args.get("id") : null;
        if (idObj != null && !idObj.toString().isBlank()) {
            try {
                binding.setVariable("row", rs.call("find", Map.of("id", idObj.toString())));
            } catch (Exception ignored) {
                binding.setVariable("row", null);
            }
        }

        GroovyShell shell = GroovyScriptShell.create(binding);
        try {
            return shell.evaluate(serviceDef.getScriptContent());
        } catch (DomainException de) {
            throw de;
        } catch (Exception ex) {
            log.error("Groovy script execution error in service [{}]:", serviceDef.getName(), ex);
            throw DomainException.invalidParams("Groovy 服务 [" + serviceDef.getName() + "] 执行异常: " + ex.getMessage());
        }
    }

    public Object evaluateInlineExpression(MetaContext meta, String script, Map<String, Object> vars) {
        if (script == null || script.isBlank()) return null;
        Binding binding = new Binding();
        binding.setVariable("env", meta);
        binding.setVariable("currentUser", meta != null ? meta.getUserId() : "system");
        binding.setVariable("now", TS_FMT.format(LocalDateTime.now()));
        binding.setVariable("today", TS_FMT.format(LocalDateTime.now()).substring(0, 10));
        binding.setVariable("JsonRpcServiceResponse", ServiceActionResult.class);
        if (vars != null) {
            vars.forEach(binding::setVariable);
        }
        try {
            return GroovyScriptShell.create(binding).evaluate(script);
        } catch (DomainException de) {
            throw de;
        } catch (Exception ex) {
            throw DomainException.invalidParams("表达式执行失败: " + ex.getMessage());
        }
    }

    private Object executeStateTransition(RecordSet rs, ServiceDef serviceDef, Map<String, Object> args) {
        ServiceDef.TransitionRuleDef rule = serviceDef.getTransitionRule();
        if (rule == null) {
            throw DomainException.stateGuardFailed("状态转移服务缺少 transitionRule 定义");
        }
        String id = args != null && args.get("id") != null ? args.get("id").toString() : rs.getId();
        if (id == null || id.isBlank()) {
            throw DomainException.invalidParams("执行状态转移服务 [" + serviceDef.getName() + "] 必须传入目标记录 id");
        }

        Object found = rs.call("find", Map.of("id", id));
        if (!(found instanceof RecordData)) {
            throw DomainException.invalidParams("目标记录不存在: " + id);
        }
        RecordData row = (RecordData) found;
        String statusField = rule.getStatusField();
        String currentStatus = row.getStr(statusField);

        if (!rule.getFromStates().isEmpty() && !rule.getFromStates().contains(currentStatus)) {
            String err = rule.getErrorMessage() != null && !rule.getErrorMessage().isBlank()
                    ? rule.getErrorMessage()
                    : "当前状态 [" + currentStatus + "] 不允许执行动作 [" + serviceDef.getDisplayName() + "]";
            throw DomainException.stateGuardFailed(err);
        }

        String now = TS_FMT.format(LocalDateTime.now());
        String uid = rs.getMeta() != null ? rs.getMeta().getUserId() : "system";

        if (rule.getGuardScript() != null && !rule.getGuardScript().isBlank()) {
            Binding binding = new Binding();
            binding.setVariable("row", row);
            binding.setVariable("args", args != null ? args : Collections.emptyMap());
            binding.setVariable("currentUser", uid);
            binding.setVariable("now", now);
            Object guardRes = GroovyScriptShell.create(binding).evaluate(rule.getGuardScript());
            if (!Boolean.TRUE.equals(guardRes)) {
                String err = rule.getErrorMessage() != null ? rule.getErrorMessage() : "状态转移守卫条件未满足";
                throw DomainException.stateGuardFailed(err);
            }
        }

        Map<String, Object> updateVals = new LinkedHashMap<>();
        updateVals.put(statusField, rule.getToState());
        for (Map.Entry<String, Object> e : rule.getUpdateFields().entrySet()) {
            updateVals.put(e.getKey(), resolvePlaceholder(e.getValue(), args, uid, now));
        }

        Map<String, Object> updateArgs = new LinkedHashMap<>();
        updateArgs.put("id", id);
        updateArgs.put("values", updateVals);
        if (row.getInt("version") != null) {
            updateArgs.put("version", row.getInt("version"));
        }
        return rs.call("update", updateArgs);
    }

    private Object resolvePlaceholder(Object raw, Map<String, Object> args, String currentUser, String now) {
        if (!(raw instanceof String)) return raw;
        String s = raw.toString();
        if ("${currentUser}".equals(s)) return currentUser;
        if ("${now}".equals(s)) return now;
        if (s.startsWith("${args.") && s.endsWith("}")) {
            String key = s.substring(7, s.length() - 1);
            return args != null ? args.get(key) : null;
        }
        return s;
    }
}
