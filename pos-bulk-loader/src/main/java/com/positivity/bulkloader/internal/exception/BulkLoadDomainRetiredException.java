package com.positivity.bulkloader.internal.exception;

/**
 * A create request named {@code DomainType.RETIRED}. That constant only labels jobs whose domain
 * has since been removed (#2070); no job can be created for it. Answered as 400 {@link #CODE}.
 */
public class BulkLoadDomainRetiredException extends RuntimeException {

    public static final String CODE = "BULK_JOB_DOMAIN_RETIRED";

    public BulkLoadDomainRetiredException() {
        super("domainType RETIRED labels jobs of a removed domain; it cannot be used to create a job");
    }
}
