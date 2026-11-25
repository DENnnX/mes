package com.sie.lowcode.domain.runtime;

import com.sie.lowcode.domain.metamodel.model.ModelSnapshot;
import java.util.List;
import java.util.Map;

/**
 * 动态数据访问与物理 Schema 同步端口（对标 engine BussModelDataAccess + ModelDBHandler）。
 */
public interface DynamicDataAccessor {
    void syncSchema(ModelSnapshot snapshot);
    void dropTable(String tableName);

    List<RecordData> search(ModelSnapshot snapshot, FilterDsl filter, List<String> properties,
                            Integer limit, Integer offset, String order, boolean useDisplayForModel);

    long count(ModelSnapshot snapshot, FilterDsl filter);

    long countAllActiveRecords(ModelSnapshot snapshot);

    boolean exists(ModelSnapshot snapshot, FilterDsl filter);

    RecordData findById(ModelSnapshot snapshot, String id, boolean includeChildren);

    List<RecordData> findByIds(ModelSnapshot snapshot, List<String> ids, boolean includeChildren);

    RecordData computeDefaultValues(ModelSnapshot snapshot, Map<String, Object> partialContext);

    List<RecordData> create(ModelSnapshot snapshot, List<Map<String, Object>> valuesList);

    int update(ModelSnapshot snapshot, String id, Map<String, Object> values, Integer expectedVersion);

    int delete(ModelSnapshot snapshot, List<String> ids);

    List<Map<String, Object>> lookup(ModelSnapshot targetSnapshot, String keyword, FilterDsl filter, int limit);

    List<RecordData> exportAll(ModelSnapshot snapshot);
}
