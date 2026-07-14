package com.positivity.shopmanager.service;

import com.positivity.shopmanager.internal.dto.PersonDTO;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Resolves a shop technician's person identity from the local {@code ext_people_contact_person}
 * replica (ADR-0044 §6, #885) — no synchronous call into the people domain.
 */
public interface TechnicianPersonService {

    /**
     * Person details of the technician working at the given shop location.
     *
     * @param locationId shop (location) id the technician belongs to
     * @param personId people-contact person id of the technician
     * @return person details; identity fields may be empty while the replica catches up
     * @throws org.springframework.web.server.ResponseStatusException 404 when no technician links
     *     the person to the location
     */
    @NonNull
    PersonDTO getTechnicianPerson(@NonNull UUID locationId, @NonNull UUID personId);
}
