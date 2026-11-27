package com.edu.scnu.domain.metamodel.model;

import java.util.*;

/**
 * 影响分析与结构差异报告值对象。
 */
public class ImpactReport {
    private boolean passed;
    private String modelName;
    private String candidateVersionId;
    private String validationHash;
    private List<String> blockingReasons = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<String> addedFields = new ArrayList<>();
    private List<String> deprecatedFields = new ArrayList<>();
    private List<ReferenceRecord> references = new ArrayList<>();

    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getCandidateVersionId() { return candidateVersionId; }
    public void setCandidateVersionId(String candidateVersionId) { this.candidateVersionId = candidateVersionId; }
    public String getValidationHash() { return validationHash; }
    public void setValidationHash(String validationHash) { this.validationHash = validationHash; }
    public List<String> getBlockingReasons() { return blockingReasons; }
    public void setBlockingReasons(List<String> blockingReasons) { this.blockingReasons = blockingReasons; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
    public List<String> getAddedFields() { return addedFields; }
    public void setAddedFields(List<String> addedFields) { this.addedFields = addedFields; }
    public List<String> getDeprecatedFields() { return deprecatedFields; }
    public void setDeprecatedFields(List<String> deprecatedFields) { this.deprecatedFields = deprecatedFields; }
    public List<ReferenceRecord> getReferences() { return references; }
    public void setReferences(List<ReferenceRecord> references) { this.references = references; }
}
