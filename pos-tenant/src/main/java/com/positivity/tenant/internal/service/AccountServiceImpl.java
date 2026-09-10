package com.positivity.tenant.internal.service;

import com.positivity.tenant.internal.dto.AccountContactRequest;
import com.positivity.tenant.internal.dto.AccountContactResponse;
import com.positivity.tenant.internal.dto.AccountCreateRequest;
import com.positivity.tenant.internal.dto.AccountResponse;
import com.positivity.tenant.internal.dto.AccountUpdateRequest;
import com.positivity.tenant.internal.dto.BillingProfileRequest;
import com.positivity.tenant.internal.dto.BillingProfileResponse;
import com.positivity.tenant.internal.entity.AccountContactEntity;
import com.positivity.tenant.internal.entity.AccountEntity;
import com.positivity.tenant.internal.entity.BillingProfileEntity;
import com.positivity.tenant.internal.entity.TenantEntity;
import com.positivity.tenant.internal.exception.DuplicateResourceException;
import com.positivity.tenant.internal.exception.ResourceNotFoundException;
import com.positivity.tenant.internal.repository.AccountContactRepository;
import com.positivity.tenant.internal.repository.AccountRepository;
import com.positivity.tenant.internal.repository.BillingProfileRepository;
import com.positivity.tenant.internal.repository.TenantRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accounts and what hangs off them. No facts are published: account data never leaves this module. */
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final AccountContactRepository contactRepository;
    private final BillingProfileRepository billingProfileRepository;
    private final TenantRepository tenantRepository;

    @Override
    @Transactional
    public @NonNull AccountResponse create(@NonNull AccountCreateRequest request) {
        if (accountRepository.existsByLegalNameIgnoreCase(request.getLegalName())) {
            throw new DuplicateResourceException("Account legal name already taken: " + request.getLegalName());
        }
        AccountEntity account = AccountEntity.builder()
                .legalName(request.getLegalName())
                .tradingName(request.getTradingName())
                .taxId(request.getTaxId())
                .homeCountry(request.getHomeCountry())
                .homeCurrency(request.getHomeCurrency())
                .build();
        return toResponse(accountRepository.saveAndFlush(account));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull AccountResponse get(@NonNull UUID accountId) {
        return toResponse(load(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<AccountResponse> list() {
        return accountRepository.findAllByOrderByLegalNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull AccountResponse update(@NonNull UUID accountId, @NonNull AccountUpdateRequest request) {
        AccountEntity account = load(accountId);
        if (request.getLegalName() != null && !request.getLegalName().equalsIgnoreCase(account.getLegalName())) {
            if (accountRepository.existsByLegalNameIgnoreCase(request.getLegalName())) {
                throw new DuplicateResourceException("Account legal name already taken: " + request.getLegalName());
            }
            account.setLegalName(request.getLegalName());
        }
        if (request.getTradingName() != null) {
            account.setTradingName(request.getTradingName());
        }
        if (request.getStatus() != null) {
            account.setStatus(request.getStatus());
        }
        if (request.getTaxId() != null) {
            account.setTaxId(request.getTaxId());
        }
        if (request.getHomeCountry() != null) {
            account.setHomeCountry(request.getHomeCountry());
        }
        if (request.getHomeCurrency() != null) {
            account.setHomeCurrency(request.getHomeCurrency());
        }
        return toResponse(accountRepository.saveAndFlush(account));
    }

    @Override
    @Transactional
    public @NonNull AccountContactResponse addContact(@NonNull UUID accountId, @NonNull AccountContactRequest request) {
        load(accountId);
        AccountContactEntity contact = AccountContactEntity.builder()
                .accountId(accountId)
                .name(request.getName())
                .role(request.getRole())
                .email(request.getEmail())
                .phone(request.getPhone())
                .build();
        return toResponse(contactRepository.saveAndFlush(contact));
    }

    @Override
    @Transactional
    public @NonNull AccountContactResponse updateContact(
            @NonNull UUID accountId, @NonNull UUID contactId, @NonNull AccountContactRequest request) {
        AccountContactEntity contact = loadContact(accountId, contactId);
        contact.setName(request.getName());
        contact.setRole(request.getRole());
        contact.setEmail(request.getEmail());
        contact.setPhone(request.getPhone());
        return toResponse(contactRepository.saveAndFlush(contact));
    }

    @Override
    @Transactional
    public void removeContact(@NonNull UUID accountId, @NonNull UUID contactId) {
        contactRepository.delete(loadContact(accountId, contactId));
    }

    @Override
    @Transactional
    public @NonNull BillingProfileResponse putBillingProfile(
            @NonNull UUID accountId, @NonNull BillingProfileRequest request) {
        load(accountId);
        BillingProfileEntity profile = billingProfileRepository
                .findByAccountId(accountId)
                .orElseGet(() ->
                        BillingProfileEntity.builder().accountId(accountId).build());
        profile.setAddressLine1(request.getAddressLine1());
        profile.setAddressLine2(request.getAddressLine2());
        profile.setCity(request.getCity());
        profile.setRegion(request.getRegion());
        profile.setPostalCode(request.getPostalCode());
        profile.setCountry(request.getCountry());
        profile.setPaymentTerms(request.getPaymentTerms());
        profile.setInvoicingEmail(request.getInvoicingEmail());
        profile.setPaymentProcessorCustomerToken(request.getPaymentProcessorCustomerToken());
        return toResponse(billingProfileRepository.saveAndFlush(profile));
    }

    private AccountEntity load(UUID accountId) {
        return accountRepository
                .findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
    }

    private AccountContactEntity loadContact(UUID accountId, UUID contactId) {
        return contactRepository
                .findByIdAndAccountId(contactId, accountId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Contact not found: " + contactId + " on account " + accountId));
    }

    private AccountResponse toResponse(AccountEntity account) {
        List<AccountContactResponse> contacts =
                contactRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                        .map(AccountServiceImpl::toResponse)
                        .toList();
        BillingProfileResponse billing = billingProfileRepository
                .findByAccountId(account.getId())
                .map(AccountServiceImpl::toResponse)
                .orElse(null);
        List<UUID> tenantIds = tenantRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .map(TenantEntity::getId)
                .toList();
        return AccountResponse.builder()
                .id(account.getId())
                .legalName(account.getLegalName())
                .tradingName(account.getTradingName())
                .status(account.getStatus())
                .taxId(account.getTaxId())
                .homeCountry(account.getHomeCountry())
                .homeCurrency(account.getHomeCurrency())
                .contacts(contacts)
                .billingProfile(billing)
                .tenantIds(tenantIds)
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    static AccountContactResponse toResponse(AccountContactEntity contact) {
        return AccountContactResponse.builder()
                .id(contact.getId())
                .accountId(contact.getAccountId())
                .name(contact.getName())
                .role(contact.getRole())
                .email(contact.getEmail())
                .phone(contact.getPhone())
                .createdAt(contact.getCreatedAt())
                .updatedAt(contact.getUpdatedAt())
                .build();
    }

    static BillingProfileResponse toResponse(BillingProfileEntity profile) {
        return BillingProfileResponse.builder()
                .id(profile.getId())
                .accountId(profile.getAccountId())
                .addressLine1(profile.getAddressLine1())
                .addressLine2(profile.getAddressLine2())
                .city(profile.getCity())
                .region(profile.getRegion())
                .postalCode(profile.getPostalCode())
                .country(profile.getCountry())
                .paymentTerms(profile.getPaymentTerms())
                .invoicingEmail(profile.getInvoicingEmail())
                .paymentProcessorCustomerTokenPresent(profile.getPaymentProcessorCustomerToken() != null
                        && !profile.getPaymentProcessorCustomerToken().isBlank())
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }
}
