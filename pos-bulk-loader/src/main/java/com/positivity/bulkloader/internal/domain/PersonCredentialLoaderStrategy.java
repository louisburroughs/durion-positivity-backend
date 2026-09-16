package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * {@code people/credentials.csv} → {@code POST /v1/people/credentials/bulk-ingest} (CAP-328). No
 * resolution here: the ingest resolves the employee number to a person and the vendor code to a
 * registry skill itself, and refuses a row it cannot — an unknown ASE code is a row rejection at the
 * endpoint, never a skill nobody holds.
 */
@Component
public class PersonCredentialLoaderStrategy implements DomainLoaderStrategy<PersonCredentialLoaderRecord> {

    private static final int MIN_PROFICIENCY = 1;
    private static final int MAX_PROFICIENCY = 5;

    @Override
    public DomainType getDomainType() {
        return DomainType.PERSON_CREDENTIAL;
    }

    @Override
    public PersonCredentialLoaderRecord mapRow(@NonNull Map<String, String> row) {
        PersonCredentialLoaderRecord record = new PersonCredentialLoaderRecord();
        record.setEmployeeNumber(row.get("employeeNumber"));
        record.setSkillCode(row.get("skillCode"));
        record.setSourceCode(row.get("sourceCode"));
        record.setSourceCredentialCode(row.get("sourceCredentialCode"));
        record.setIssuer(row.get("issuer"));
        record.setIssuedOn(row.get("issuedOn"));
        record.setExpiresOn(row.get("expiresOn"));
        record.setProficiency(row.get("proficiency"));
        record.setEvidenceRef(row.get("evidenceRef"));
        return record;
    }

    @Override
    @NonNull
    public PersonCredentialLoaderRecord resolve(
            @NonNull PersonCredentialLoaderRecord item, @NonNull ResolutionContext context) {
        return item;
    }

    @Override
    public List<String> validate(@NonNull PersonCredentialLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getEmployeeNumber())) {
            errors.add("employeeNumber is required");
        }
        boolean byCode = LoaderValues.isPresent(item.getSkillCode());
        boolean byVendor = LoaderValues.isPresent(item.getSourceCode()) && LoaderValues.isPresent(item.getSourceCredentialCode());
        if (!byCode && !byVendor) {
            errors.add("either skillCode or sourceCode + sourceCredentialCode is required");
        }
        if (byCode && !byVendor && LoaderValues.isBlank(item.getIssuer())) {
            errors.add("issuer is required when the skill is named by skillCode");
        }
        if (LoaderValues.isBlank(item.getIssuedOn())) {
            errors.add("issuedOn is required");
        } else {
            requireDate(item.getIssuedOn(), "issuedOn", errors);
        }
        if (LoaderValues.isPresent(item.getExpiresOn())) {
            requireDate(item.getExpiresOn(), "expiresOn", errors);
        }
        validateProficiency(item, errors);
        if (LoaderValues.isPresent(item.getEvidenceRef())) {
            LoaderValues.requireUuid(item.getEvidenceRef(), "evidenceRef", "a document id", errors);
        }
        return errors;
    }

    private static void requireDate(String value, String field, List<String> errors) {
        try {
            LocalDate.parse(value.trim());
        } catch (DateTimeParseException _) {
            errors.add(field + " must be an ISO-8601 date (yyyy-MM-dd)");
        }
    }

    private static void validateProficiency(PersonCredentialLoaderRecord item, List<String> errors) {
        if (LoaderValues.isBlank(item.getProficiency())) {
            return;
        }
        try {
            int level = Integer.parseInt(item.getProficiency().trim());
            if (level < MIN_PROFICIENCY || level > MAX_PROFICIENCY) {
                errors.add("proficiency must be between 1 and 5");
            }
        } catch (NumberFormatException _) {
            errors.add("proficiency must be a whole number");
        }
    }
}
