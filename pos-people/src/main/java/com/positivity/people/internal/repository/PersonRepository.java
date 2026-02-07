package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PersonRepository extends JpaRepository<Person, UUID> {
    boolean existsByUsername(String username);
}
