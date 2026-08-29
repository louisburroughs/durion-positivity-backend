package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Mechanic skills, with the mechanic named by employee number. */
@Component
public class MechanicSkillLoaderStrategy implements DomainLoaderStrategy<MechanicSkillLoaderRecord> {

    private static final int MIN_PROFICIENCY = 1;
    private static final int MAX_PROFICIENCY = 5;

    @Override
    public DomainType getDomainType() {
        return DomainType.MECHANIC_SKILL;
    }

    @Override
    public MechanicSkillLoaderRecord mapRow(@NonNull Map<String, String> row) {
        MechanicSkillLoaderRecord record = new MechanicSkillLoaderRecord();
        record.setEmployeeNumber(row.get("employeeNumber"));
        record.setSkillCode(row.get("skillCode"));
        record.setProficiencyLevel(row.get("proficiencyLevel"));
        record.setPersonId(row.get("personId"));
        return record;
    }

    @Override
    @NonNull
    public MechanicSkillLoaderRecord resolve(
            @NonNull MechanicSkillLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getPersonId()) && LoaderValues.isPresent(item.getEmployeeNumber())) {
            PeopleResolutions.personId(context, item.getEmployeeNumber()).ifPresent(item::setPersonId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull MechanicSkillLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getSkillCode())) {
            errors.add("skillCode is required");
        }
        LoaderValues.requireUuid(item.getPersonId(), "personId", "an employeeNumber that resolves to one", errors);
        validateProficiency(item, errors);
        return errors;
    }

    /**
     * Checked here as well as at the endpoint so an out-of-range level is caught before the whole
     * mechanic's set is sent: the endpoint replaces a mechanic's skills in one call, so one bad row
     * would otherwise take that mechanic's other rows down with it.
     */
    private void validateProficiency(MechanicSkillLoaderRecord item, List<String> errors) {
        if (LoaderValues.isBlank(item.getProficiencyLevel())) {
            errors.add("proficiencyLevel is required");
            return;
        }
        try {
            int level = Integer.parseInt(item.getProficiencyLevel().trim());
            if (level < MIN_PROFICIENCY || level > MAX_PROFICIENCY) {
                errors.add("proficiencyLevel must be between 1 and 5");
            }
        } catch (NumberFormatException _) {
            errors.add("proficiencyLevel must be a whole number");
        }
    }
}
