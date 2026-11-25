package com.sie.lowcode.domain.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准化自定义服务 UI 指令返回对象（对标课堂教程 JsonRpcServiceResponse）：
 * 可在 Groovy 脚本中直接返回 JsonRpcServiceResponse.message("...") 或 JsonRpcServiceResponse.reloadWindow("...")。
 */
public class ServiceActionResult extends LinkedHashMap<String, Object> {
    public ServiceActionResult(String uiAction, String message, Object data) {
        this.put("_uiAction", uiAction);
        if (message != null) this.put("message", message);
        if (data != null) this.put("data", data);
    }

    public static ServiceActionResult message(String msg) {
        return new ServiceActionResult("message", msg, null);
    }

    public static ServiceActionResult message(String msg, Object data) {
        return new ServiceActionResult("message", msg, data);
    }

    public static ServiceActionResult reloadWindow() {
        return new ServiceActionResult("reloadWindow", "操作成功", null);
    }

    public static ServiceActionResult reloadWindow(String msg) {
        return new ServiceActionResult("reloadWindow", msg, null);
    }

    public static ServiceActionResult refreshTable(String msg, Object data) {
        return new ServiceActionResult("refreshTable", msg, data);
    }
}
