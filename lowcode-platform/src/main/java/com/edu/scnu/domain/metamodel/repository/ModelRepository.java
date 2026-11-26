package com.edu.scnu.domain.metamodel.repository;

import com.edu.scnu.domain.metamodel.model.*;
import java.util.List;
import java.util.Optional;

public interface ModelRepository {
    void saveModel(ModelAggregate model);
    Optional<ModelAggregate> findModelByName(String name);
    List<ModelAggregate> findAllModels();
    List<ModelAggregate> findModelsByApp(String appName);
    void deleteModelMeta(String modelName);

    void saveVersion(ModelVersion version);
    Optional<ModelVersion> findVersionById(String versionId);
    Optional<ModelVersion> findVersion(String modelName, String versionNo);
    List<ModelVersion> findVersionsByModel(String modelName);
    void deleteVersionsByModel(String modelName);

    void replaceReferences(String versionId, List<ReferenceRecord> references);
    List<ReferenceRecord> findReferencesByVersion(String versionId);
    void deleteReferencesByModel(String modelName);
}
