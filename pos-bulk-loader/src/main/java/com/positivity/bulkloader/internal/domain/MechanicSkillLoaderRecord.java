package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** One mechanic's proficiency in one skill, with the mechanic named by employee number. */
@Data
public class MechanicSkillLoaderRecord {

    private String employeeNumber;
    private String skillCode;
    private String proficiencyLevel;

    /** Resolved from {@code employeeNumber}, or supplied directly. */
    private String personId;
}
