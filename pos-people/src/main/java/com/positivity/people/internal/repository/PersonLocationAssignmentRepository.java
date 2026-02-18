package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.PersonLocationAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PersonLocationAssignmentRepository extends JpaRepository<PersonLocationAssignment, UUID> {

    List<PersonLocationAssignment> findByLocationId(UUID locationId);

    List<PersonLocationAssignment> findByPersonId(UUID personId);

    List<PersonLocationAssignment> findByLocationIdAndPersonId(UUID locationId, UUID personId);
}
