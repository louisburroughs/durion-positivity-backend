package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Contact;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContactRepository extends JpaRepository<Contact, UUID> {
    List<Contact> findByCommercialParty(CommercialParty commercialParty);

    List<Contact> findByPersonIdAndActiveTrue(UUID personId);
}
