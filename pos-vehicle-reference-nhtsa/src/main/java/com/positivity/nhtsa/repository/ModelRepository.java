package com.positivity.nhtsa.repository;

import com.positivity.nhtsa.entity.Model;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ModelRepository extends JpaRepository<Model, Long> {
    List<Model> findByMakeId(Long makeId);
}

