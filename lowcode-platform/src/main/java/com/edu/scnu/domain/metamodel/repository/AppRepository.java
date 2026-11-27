package com.edu.scnu.domain.metamodel.repository;

import com.edu.scnu.domain.metamodel.model.AppAggregate;
import java.util.List;
import java.util.Optional;

public interface AppRepository {
    void save(AppAggregate app);
    Optional<AppAggregate> findByName(String name);
    List<AppAggregate> findAll();
    void deleteByName(String name);
}
