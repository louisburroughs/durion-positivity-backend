package com.positivity.bulkloader.internal.entity;

import com.positivity.bulkloader.internal.enums.DomainType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps {@code bulk_load_job.domain_type} by name, tolerating names the enum no longer has (#2070).
 *
 * <p>Retiring a domain removes its constant, but the jobs already run for it stay in the table.
 * {@code @Enumerated(STRING)} throws on such a row, and because the job list is one query, a
 * single stale row turned every page of the operator's list into a 500. Unknown names now read as
 * {@link DomainType#RETIRED}.
 *
 * <p>Writes stay a plain {@code name()}, {@code RETIRED} included: Hibernate converts every constant
 * at bootstrap to build the column's check constraint, so refusing one here fails the context.
 * {@code RETIRED} never reaches the table anyway: {@code createJob} refuses it, and the column is
 * not updatable, so a retired job keeps its original name.
 */
@Slf4j
@Converter
public class DomainTypeConverter implements AttributeConverter<DomainType, String> {

    @Override
    public String convertToDatabaseColumn(DomainType domainType) {
        return domainType == null ? null : domainType.name();
    }

    @Override
    public DomainType convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return DomainType.valueOf(stored);
        } catch (IllegalArgumentException retired) {
            log.debug("bulk_load_job.domain_type '{}' is no longer a DomainType; reading it as RETIRED", stored);
            return DomainType.RETIRED;
        }
    }
}
