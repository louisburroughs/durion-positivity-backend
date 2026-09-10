package com.positivity.tenant.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.positivity.tenant.internal.enums.AccountStatus;
import com.positivity.tenant.internal.enums.ContactRole;
import com.positivity.tenant.internal.exception.DuplicateResourceException;
import com.positivity.tenant.internal.exception.ResourceNotFoundException;
import com.positivity.tenant.internal.repository.AccountContactRepository;
import com.positivity.tenant.internal.repository.AccountRepository;
import com.positivity.tenant.internal.repository.BillingProfileRepository;
import com.positivity.tenant.internal.repository.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountServiceImplTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountContactRepository contacts = mock(AccountContactRepository.class);
    private final BillingProfileRepository billing = mock(BillingProfileRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);

    private AccountServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccountServiceImpl(accounts, contacts, billing, tenants);
        when(accounts.saveAndFlush(any())).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(contacts.saveAndFlush(any())).thenAnswer(inv -> {
            AccountContactEntity c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
        when(billing.saveAndFlush(any())).thenAnswer(inv -> {
            BillingProfileEntity b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId(UUID.randomUUID());
            }
            return b;
        });
    }

    private static AccountEntity withId(AccountEntity account) {
        if (account.getId() == null) {
            account.setId(UUID.randomUUID());
        }
        return account;
    }

    private AccountEntity existing() {
        AccountEntity account = withId(AccountEntity.builder()
                .legalName("Acme LLC")
                .homeCountry("US")
                .homeCurrency("USD")
                .build());
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void createRejectsDuplicateLegalName() {
        when(accounts.existsByLegalNameIgnoreCase("Acme LLC")).thenReturn(true);
        assertThatThrownBy(() -> service.create(AccountCreateRequest.builder()
                        .legalName("Acme LLC")
                        .homeCountry("US")
                        .homeCurrency("USD")
                        .build()))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void createReturnsAccountWithEmptyContactsAndTenants() {
        AccountResponse response = service.create(AccountCreateRequest.builder()
                .legalName("Acme LLC")
                .tradingName("Acme")
                .homeCountry("US")
                .homeCurrency("USD")
                .build());

        assertThat(response.getId()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.getContacts()).isEmpty();
        assertThat(response.getBillingProfile()).isNull();
        assertThat(response.getTenantIds()).isEmpty();
    }

    @Test
    void getAssemblesContactsBillingAndTenants() {
        AccountEntity account = existing();
        when(contacts.findByAccountIdOrderByCreatedAtAsc(account.getId()))
                .thenReturn(List.of(AccountContactEntity.builder()
                        .id(UUID.randomUUID())
                        .accountId(account.getId())
                        .name("Jordan")
                        .role(ContactRole.OWNER)
                        .email("j@acme.example")
                        .build()));
        when(billing.findByAccountId(account.getId()))
                .thenReturn(Optional.of(BillingProfileEntity.builder()
                        .id(UUID.randomUUID())
                        .accountId(account.getId())
                        .paymentProcessorCustomerToken("cus_123")
                        .build()));
        UUID tenantId = UUID.randomUUID();
        when(tenants.findByAccountIdOrderByCreatedAtAsc(account.getId()))
                .thenReturn(List.of(TenantEntity.builder().id(tenantId).build()));

        AccountResponse response = service.get(account.getId());

        assertThat(response.getContacts())
                .extracting(AccountContactResponse::getRole)
                .containsExactly(ContactRole.OWNER);
        assertThat(response.getBillingProfile().isPaymentProcessorCustomerTokenPresent())
                .as("the token itself never leaves the module")
                .isTrue();
        assertThat(response.getTenantIds()).containsExactly(tenantId);
    }

    @Test
    void updateAppliesOnlyNonNullFieldsAndGuardsLegalName() {
        AccountEntity account = existing();
        when(accounts.existsByLegalNameIgnoreCase("Taken")).thenReturn(true);

        assertThatThrownBy(() -> service.update(
                        account.getId(),
                        AccountUpdateRequest.builder().legalName("Taken").build()))
                .isInstanceOf(DuplicateResourceException.class);

        AccountResponse response = service.update(
                account.getId(),
                AccountUpdateRequest.builder()
                        .status(AccountStatus.SUSPENDED)
                        .taxId("12-3")
                        .build());
        assertThat(response.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(response.getTaxId()).isEqualTo("12-3");
        assertThat(response.getLegalName()).isEqualTo("Acme LLC");
    }

    @Test
    void contactsAreScopedToTheirAccount() {
        AccountEntity account = existing();
        AccountContactRequest request = AccountContactRequest.builder()
                .name("Jordan")
                .role(ContactRole.BILLING)
                .email("j@acme.example")
                .build();

        AccountContactResponse added = service.addContact(account.getId(), request);
        assertThat(added.getAccountId()).isEqualTo(account.getId());

        when(contacts.findByIdAndAccountId(added.getId(), account.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.removeContact(account.getId(), added.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.addContact(UUID.randomUUID(), request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateContactReplacesEveryField() {
        AccountEntity account = existing();
        AccountContactEntity contact = AccountContactEntity.builder()
                .id(UUID.randomUUID())
                .accountId(account.getId())
                .name("Old")
                .role(ContactRole.OWNER)
                .email("old@acme.example")
                .build();
        when(contacts.findByIdAndAccountId(contact.getId(), account.getId())).thenReturn(Optional.of(contact));

        AccountContactResponse response = service.updateContact(
                account.getId(),
                contact.getId(),
                AccountContactRequest.builder()
                        .name("New")
                        .role(ContactRole.TECHNICAL)
                        .email("new@acme.example")
                        .phone("+1 555 0100")
                        .build());

        assertThat(response.getName()).isEqualTo("New");
        assertThat(response.getRole()).isEqualTo(ContactRole.TECHNICAL);
        assertThat(response.getPhone()).isEqualTo("+1 555 0100");

        service.removeContact(account.getId(), contact.getId());
        verify(contacts).delete(contact);
    }

    @Test
    void putBillingProfileCreatesThenReplaces() {
        AccountEntity account = existing();
        BillingProfileRequest request = BillingProfileRequest.builder()
                .addressLine1("100 Main St")
                .city("Springfield")
                .country("US")
                .paymentTerms("NET30")
                .invoicingEmail("billing@acme.example")
                .build();
        when(billing.findByAccountId(account.getId())).thenReturn(Optional.empty());

        BillingProfileResponse created = service.putBillingProfile(account.getId(), request);
        assertThat(created.getAccountId()).isEqualTo(account.getId());
        assertThat(created.isPaymentProcessorCustomerTokenPresent()).isFalse();

        BillingProfileEntity stored = BillingProfileEntity.builder()
                .id(created.getId())
                .accountId(account.getId())
                .build();
        when(billing.findByAccountId(account.getId())).thenReturn(Optional.of(stored));
        request.setPaymentProcessorCustomerToken("cus_123");
        BillingProfileResponse replaced = service.putBillingProfile(account.getId(), request);
        assertThat(replaced.getId()).isEqualTo(created.getId());
        assertThat(replaced.isPaymentProcessorCustomerTokenPresent()).isTrue();
    }
}
