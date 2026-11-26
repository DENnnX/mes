package com.edu.scnu.domain.metamodel.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 平台通用编码规则模型（对标实验手册第 12.3/13.3 节 BarcodeRule）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeRuleDef {
    private String id;
    private String code;
    private String name;
    private String prefix = "";
    private String dateFormat = "yyyyMMdd"; // NONE | yyyy | yyyyMM | yyyyMMdd
    private int seqLength = 4;
    private String status = "ACTIVE";
    private String tenantId = "rbac_tenant_root";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getDateFormat() { return dateFormat; }
    public void setDateFormat(String dateFormat) { this.dateFormat = dateFormat; }
    public int getSeqLength() { return seqLength; }
    public void setSeqLength(int seqLength) { this.seqLength = seqLength; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
