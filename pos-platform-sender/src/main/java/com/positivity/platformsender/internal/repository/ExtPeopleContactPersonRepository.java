package com.positivity.platformsender.internal.repository;

import com.positivity.platformsender.internal.entity.ExtPeopleContactPerson;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtPeopleContactPersonRepository extends JpaRepository<ExtPeopleContactPerson, UUID> {}
