/**
 * ADR-0062 tenancy runtime shared by every persisting module.
 *
 * <p>The database is the authoritative enforcement layer (row-level security on every scoped table);
 * this library binds the request's tenant to the connection ({@link
 * com.positivity.tenancy.datasource.TenantAwareDataSource}), to Hibernate ({@link
 * com.positivity.tenancy.hibernate.TenantContextIdentifierResolver}), and to Kafka consumers
 * ({@link com.positivity.tenancy.kafka.TenantRecordInterceptor}), puts it on every log line
 * ({@link com.positivity.tenancy.logging.TenantLogPatternEnvironmentPostProcessor}), and carries
 * the classification annotations the ArchUnit rules check.
 */
@NullMarked
package com.positivity.tenancy;

import org.jspecify.annotations.NullMarked;
