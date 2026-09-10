package com.positivity.tenant.internal.service;

import com.positivity.tenant.internal.dto.AccountContactRequest;
import com.positivity.tenant.internal.dto.AccountContactResponse;
import com.positivity.tenant.internal.dto.AccountCreateRequest;
import com.positivity.tenant.internal.dto.AccountResponse;
import com.positivity.tenant.internal.dto.AccountUpdateRequest;
import com.positivity.tenant.internal.dto.BillingProfileRequest;
import com.positivity.tenant.internal.dto.BillingProfileResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Accounts, their contacts and billing profiles (ADR-0062 §7). Platform-tenant data only. */
public interface AccountService {

    @NonNull
    AccountResponse create(@NonNull AccountCreateRequest request);

    @NonNull
    AccountResponse get(@NonNull UUID accountId);

    @NonNull
    List<AccountResponse> list();

    @NonNull
    AccountResponse update(@NonNull UUID accountId, @NonNull AccountUpdateRequest request);

    @NonNull
    AccountContactResponse addContact(@NonNull UUID accountId, @NonNull AccountContactRequest request);

    @NonNull
    AccountContactResponse updateContact(
            @NonNull UUID accountId, @NonNull UUID contactId, @NonNull AccountContactRequest request);

    void removeContact(@NonNull UUID accountId, @NonNull UUID contactId);

    /** Create or replace the account's single billing profile. */
    @NonNull
    BillingProfileResponse putBillingProfile(@NonNull UUID accountId, @NonNull BillingProfileRequest request);
}
