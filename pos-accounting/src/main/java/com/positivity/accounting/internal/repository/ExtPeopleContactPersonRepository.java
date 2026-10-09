package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtPeopleContactPerson;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bound tenant's copy of pos-people-contact's persons (AP reads #2670); the tenant filter and RLS scope it. */
public interface ExtPeopleContactPersonRepository extends JpaRepository<ExtPeopleContactPerson, UUID> {}
