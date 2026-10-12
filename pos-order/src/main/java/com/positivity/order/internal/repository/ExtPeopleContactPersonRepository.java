package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtPeopleContactPerson;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bound tenant's copy of pos-people-contact's persons (drawer actor names #2608); the tenant filter and RLS scope it. */
public interface ExtPeopleContactPersonRepository extends JpaRepository<ExtPeopleContactPerson, UUID> {}
