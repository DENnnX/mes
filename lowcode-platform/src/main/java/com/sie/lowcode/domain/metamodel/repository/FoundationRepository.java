package com.sie.lowcode.domain.metamodel.repository;

import com.sie.lowcode.domain.metamodel.model.*;
import java.util.*;

/**
 * 平台通用基础能力仓储端口（数据字典、通用编码规则、附件元数据）。
 */
public interface FoundationRepository {
    // 数据字典
    List<DictTypeDef> findAllDictTypes(String tenantId);
    Optional<DictTypeDef> findDictTypeByCode(String typeCode);
    void saveDictType(DictTypeDef dictType);
    void deleteDictType(String typeCode);

    List<DictItemDef> findDictItemsByType(String typeCode);
    List<DictItemDef> findAllDictItems();
    void saveDictItem(DictItemDef item);
    void deleteDictItem(String id);

    // 编码规则
    List<CodeRuleDef> findAllCodeRules(String tenantId);
    Optional<CodeRuleDef> findCodeRuleByCode(String code);
    void saveCodeRule(CodeRuleDef rule);
    void deleteCodeRule(String code);
    String nextSequenceCode(String code, String tenantId);

    // 文件附件元数据
    void saveFileMeta(String id, String fileName, String contentType, long fileSize, String storagePath, String tenantId, String userId, String now);
    Optional<Map<String, Object>> findFileMetaById(String id);
    List<Map<String, Object>> findAllFileMeta(String tenantId);
}
