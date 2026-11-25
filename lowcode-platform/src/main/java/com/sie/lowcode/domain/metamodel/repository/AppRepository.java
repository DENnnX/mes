package com.sie.lowcode.domain.metamodel.repository;

import com.sie.lowcode.domain.metamodel.model.AppAggregate;
import java.util.List;
import java.util.Optional;

public interface AppRepository {
    void save(AppAggregate app);
    Optional<AppAggregate> findByName(String name);
    List<AppAggregate> findAll();
}
