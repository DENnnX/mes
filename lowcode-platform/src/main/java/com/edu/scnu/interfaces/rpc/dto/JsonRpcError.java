package com.edu.scnu.interfaces.rpc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcError {
    private int code;
    private String message;
    private Map<String, Object> data;

    public JsonRpcError() {}

    public JsonRpcError(int code, String message, String errorCode, List<String> details) {
        this.code = code;
        this.message = message;
        if (errorCode != null || (details != null && !details.isEmpty())) {
            this.data = new LinkedHashMap<>();
            if (errorCode != null) this.data.put("errorCode", errorCode);
            if (details != null && !details.isEmpty()) this.data.put("details", details);
        }
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
}
