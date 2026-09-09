/**
 * Tenancy binding for ADR-0062: the thread-bound {@link com.positivity.tenancy.TenantContext} and
 * the {@link com.positivity.tenancy.TenantAwareDataSource} that writes it to
 * {@code app.current_tenant} on every connection checkout, which is what the row-level security
 * policies in every module's baseline read.
 */
@NullMarked
package com.positivity.tenancy;

import org.jspecify.annotations.NullMarked;
