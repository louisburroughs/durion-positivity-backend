package com.positivity.customer.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.customer.internal.entity.Contact;
import com.positivity.customer.internal.entity.CommercialParty;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContactRepository extends JpaRepository<Contact, UUID> {
    List<Contact> findByCommercialParty(CommercialParty commercialParty);

    List<Contact> findByPersonIdAndActiveTrue(UUID personId);
}
