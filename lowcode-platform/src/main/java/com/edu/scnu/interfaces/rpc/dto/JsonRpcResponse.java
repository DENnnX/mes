package com.edu.scnu.interfaces.rpc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {
    private String jsonrpc = "2.0";
    private Object result;
    private JsonRpcError error;
    private Object id;

    public static JsonRpcResponse ok(Object result, Object id) {
        JsonRpcResponse r = new JsonRpcResponse();
        r.setResult(result);
        r.setId(id);
        return r;
    }

    public static JsonRpcResponse fail(JsonRpcError error, Object id) {
        JsonRpcResponse r = new JsonRpcResponse();
        r.setError(error);
        r.setId(id);
        return r;
    }

    public String getJsonrpc() { return jsonrpc; }
    public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
    public JsonRpcError getError() { return error; }
    public void setError(JsonRpcError error) { this.error = error; }
    public Object getId() { return id; }
    public void setId(Object id) { this.id = id; }
}
