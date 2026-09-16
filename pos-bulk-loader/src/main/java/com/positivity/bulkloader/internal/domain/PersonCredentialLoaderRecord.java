package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/**
 * One credential a person holds, as {@code people/credentials.csv} states it (CAP-328): the person
 * by employee number; the skill either by Durion code or by vendor code system + code; the issuer;
 * the dates. Strings only — the ingest endpoint types and resolves them.
 */
@Data
public class PersonCredentialLoaderRecord {
    private String employeeNumber;
    private String skillCode;
    private String sourceCode;
    private String sourceCredentialCode;
    private String issuer;
    private String issuedOn;
    private String expiresOn;
    private String proficiency;
    private String evidenceRef;
}
