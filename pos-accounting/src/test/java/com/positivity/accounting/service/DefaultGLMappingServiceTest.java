package com.positivity.accounting.service;

import java.time.ZoneOffset;
import java.time.Clock;

import com.positivity.accounting.internal.service.GLAccountServiceImpl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.positivity.accounting.internal.dto.DefaultGLMappingListResponse;
import com.positivity.accounting.internal.dto.DefaultGLMappingRequest;
import com.positivity.accounting.internal.dto.DefaultGLMappingResponse;
import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.service.DefaultGLMappingServiceImpl;

/**
 * Unit tests for DefaultGLMappingServiceImpl.
 *
 * Tests cover:
 * - Creating default GL mappings with validation
 * - Updating existing mappings
 * - Deactivating (soft-deleting) mappings
 * - Retrieving mappings by ID
 * - Paginated listing
 * - Querying by event type, organization, and global defaults
 * - GL account validation failures
 * - Not-found error handling
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultGLMappingServiceImpl Unit Tests")
class DefaultGLMappingServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


    @Mock
    private DefaultGLMappingRepository repository;

    @Mock
    private GLAccountRepository glAccountRepository;

    @Mock
    private GLAccountServiceImpl glAccountService;

    @InjectMocks
    private DefaultGLMappingServiceImpl service;

    // Test fixtures
    private static final UUID MAPPING_ID = UUID.randomUUID();
    private static final UUID DEBIT_ACCOUNT_ID = UUID.randomUUID();
    private static final UUID CREDIT_ACCOUNT_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final String EVENT_TYPE = "INVOICE_CREATED";

    private DefaultGLMappingRequest validRequest;
    private DefaultGLMapping savedMapping;
    private GLAccount debitAccount;
    private GLAccount creditAccount;

    @BeforeEach
    void setUp() {
        validRequest = DefaultGLMappingRequest.builder()
                .eventType(EVENT_TYPE)
                .organizationId(ORG_ID)
                .debitAccountId(DEBIT_ACCOUNT_ID)
                .creditAccountId(CREDIT_ACCOUNT_ID)
                .description("Default mapping for invoice creation")
                .active(true)
                .build();

        savedMapping = new DefaultGLMapping();
        savedMapping.setMappingId(MAPPING_ID);
        savedMapping.setEventType(EVENT_TYPE);
        savedMapping.setOrganizationId(ORG_ID);
        savedMapping.setDebitAccountId(DEBIT_ACCOUNT_ID);
        savedMapping.setCreditAccountId(CREDIT_ACCOUNT_ID);
        savedMapping.setDescription("Default mapping for invoice creation");
        savedMapping.setActive(true);
        savedMapping.setCreatedAt(Instant.now(TEST_CLOCK));
        savedMapping.setCreatedBy("test-user");
        savedMapping.setUpdatedAt(Instant.now(TEST_CLOCK));
        savedMapping.setModifiedBy("test-user");

        debitAccount = new GLAccount();
        debitAccount.setGlAccountId(DEBIT_ACCOUNT_ID);
        debitAccount.setAccountCode("1200");
        debitAccount.setAccountName("Accounts Receivable");
        debitAccount.setAccountType(AccountType.ASSET);
        debitAccount.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));

        creditAccount = new GLAccount();
        creditAccount.setGlAccountId(CREDIT_ACCOUNT_ID);
        creditAccount.setAccountCode("4000");
        creditAccount.setAccountName("Revenue");
        creditAccount.setAccountType(AccountType.REVENUE);
        creditAccount.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
    }

    @Nested
    @DisplayName("createDefaultMapping")
    class CreateDefaultMapping {

        @Test
        @DisplayName("should create mapping when GL accounts are valid")
        void shouldCreateMappingWhenAccountsAreValid() {
            // Arrange
            doNothing().when(glAccountService).validateAccountForPosting(eq(DEBIT_ACCOUNT_ID),
                    any(LocalDateTime.class));
            doNothing().when(glAccountService).validateAccountForPosting(eq(CREDIT_ACCOUNT_ID),
                    any(LocalDateTime.class));
            when(repository.save(any(DefaultGLMapping.class))).thenReturn(savedMapping);
            when(glAccountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.createDefaultMapping(validRequest);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getMappingId()).isEqualTo(MAPPING_ID);
            assertThat(response.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(response.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(response.getDebitAccountId()).isEqualTo(DEBIT_ACCOUNT_ID);
            assertThat(response.getDebitAccountCode()).isEqualTo("1200");
            assertThat(response.getDebitAccountName()).isEqualTo("Accounts Receivable");
            assertThat(response.getCreditAccountId()).isEqualTo(CREDIT_ACCOUNT_ID);
            assertThat(response.getCreditAccountCode()).isEqualTo("4000");
            assertThat(response.getCreditAccountName()).isEqualTo("Revenue");
            assertThat(response.getDescription()).isEqualTo("Default mapping for invoice creation");
            assertThat(response.getActive()).isTrue();

            verify(glAccountService).validateAccountForPosting(eq(DEBIT_ACCOUNT_ID), any(LocalDateTime.class));
            verify(glAccountService).validateAccountForPosting(eq(CREDIT_ACCOUNT_ID), any(LocalDateTime.class));
            verify(repository).save(any(DefaultGLMapping.class));
        }

        @Test
        @DisplayName("should persist correct entity fields from request")
        void shouldPersistCorrectFieldsFromRequest() {
            // Arrange
            doNothing().when(glAccountService).validateAccountForPosting(any(), any());
            ArgumentCaptor<DefaultGLMapping> captor = ArgumentCaptor.forClass(DefaultGLMapping.class);
            when(repository.save(captor.capture())).thenReturn(savedMapping);
            when(glAccountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(creditAccount));

            // Act
            service.createDefaultMapping(validRequest);

            // Assert
            DefaultGLMapping captured = captor.getValue();
            assertThat(captured.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(captured.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(captured.getDebitAccountId()).isEqualTo(DEBIT_ACCOUNT_ID);
            assertThat(captured.getCreditAccountId()).isEqualTo(CREDIT_ACCOUNT_ID);
            assertThat(captured.getDescription()).isEqualTo("Default mapping for invoice creation");
            assertThat(captured.getActive()).isTrue();
        }

        @Test
        @DisplayName("should throw when debit account validation fails")
        void shouldThrowWhenDebitAccountValidationFails() {
            // Arrange
            doThrow(new IllegalArgumentException("Account 1200 is not yet active on 2026-02-11T00:00"))
                    .when(glAccountService).validateAccountForPosting(eq(DEBIT_ACCOUNT_ID), any(LocalDateTime.class));

            // Act & Assert
            assertThatThrownBy(() -> service.createDefaultMapping(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not yet active");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("should throw when credit account validation fails")
        void shouldThrowWhenCreditAccountValidationFails() {
            // Arrange
            doNothing().when(glAccountService).validateAccountForPosting(eq(DEBIT_ACCOUNT_ID),
                    any(LocalDateTime.class));
            doThrow(new IllegalArgumentException("Account 4000 is inactive"))
                    .when(glAccountService).validateAccountForPosting(eq(CREDIT_ACCOUNT_ID), any(LocalDateTime.class));

            // Act & Assert
            assertThatThrownBy(() -> service.createDefaultMapping(validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inactive");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("should create global mapping when organizationId is null")
        void shouldCreateGlobalMappingWhenOrgIsNull() {
            // Arrange
            DefaultGLMappingRequest globalRequest = DefaultGLMappingRequest.builder()
                    .eventType(EVENT_TYPE)
                    .organizationId(null)
                    .debitAccountId(DEBIT_ACCOUNT_ID)
                    .creditAccountId(CREDIT_ACCOUNT_ID)
                    .description("Global default")
                    .build();

            DefaultGLMapping globalMapping = new DefaultGLMapping();
            globalMapping.setMappingId(MAPPING_ID);
            globalMapping.setEventType(EVENT_TYPE);
            globalMapping.setOrganizationId(null);
            globalMapping.setDebitAccountId(DEBIT_ACCOUNT_ID);
            globalMapping.setCreditAccountId(CREDIT_ACCOUNT_ID);
            globalMapping.setActive(true);
            globalMapping.setCreatedAt(Instant.now(TEST_CLOCK));
            globalMapping.setCreatedBy("system");
            globalMapping.setUpdatedAt(Instant.now(TEST_CLOCK));
            globalMapping.setModifiedBy("system");

            doNothing().when(glAccountService).validateAccountForPosting(any(), any());
            when(repository.save(any(DefaultGLMapping.class))).thenReturn(globalMapping);
            when(glAccountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.createDefaultMapping(globalRequest);

            // Assert
            assertThat(response.getOrganizationId()).isNull();
        }
    }

    @Nested
    @DisplayName("updateDefaultMapping")
    class UpdateDefaultMapping {

        @Test
        @DisplayName("should update existing mapping successfully")
        void shouldUpdateExistingMapping() {
            // Arrange
            DefaultGLMappingRequest updateRequest = DefaultGLMappingRequest.builder()
                    .eventType("PAYMENT_RECEIVED")
                    .organizationId(ORG_ID)
                    .debitAccountId(DEBIT_ACCOUNT_ID)
                    .creditAccountId(CREDIT_ACCOUNT_ID)
                    .description("Updated description")
                    .active(true)
                    .build();

            DefaultGLMapping updatedMapping = new DefaultGLMapping();
            updatedMapping.setMappingId(MAPPING_ID);
            updatedMapping.setEventType("PAYMENT_RECEIVED");
            updatedMapping.setOrganizationId(ORG_ID);
            updatedMapping.setDebitAccountId(DEBIT_ACCOUNT_ID);
            updatedMapping.setCreditAccountId(CREDIT_ACCOUNT_ID);
            updatedMapping.setDescription("Updated description");
            updatedMapping.setActive(true);
            updatedMapping.setCreatedAt(Instant.now(TEST_CLOCK));
            updatedMapping.setCreatedBy("test-user");
            updatedMapping.setUpdatedAt(Instant.now(TEST_CLOCK));
            updatedMapping.setModifiedBy("test-user");

            when(repository.findById(MAPPING_ID)).thenReturn(Optional.of(savedMapping));
            doNothing().when(glAccountService).validateAccountForPosting(any(), any());
            when(repository.save(any(DefaultGLMapping.class))).thenReturn(updatedMapping);
            when(glAccountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.updateDefaultMapping(MAPPING_ID, updateRequest);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getEventType()).isEqualTo("PAYMENT_RECEIVED");
            assertThat(response.getDescription()).isEqualTo("Updated description");

            verify(repository).findById(MAPPING_ID);
            verify(repository).save(any(DefaultGLMapping.class));
        }

        @Test
        @DisplayName("should throw when mapping not found for update")
        void shouldThrowWhenMappingNotFoundForUpdate() {
            // Arrange
            UUID unknownId = UUID.randomUUID();
            when(repository.findById(unknownId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.updateDefaultMapping(unknownId, validRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Default GL mapping not found");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("should validate GL accounts before updating")
        void shouldValidateAccountsBeforeUpdate() {
            // Arrange
            when(repository.findById(MAPPING_ID)).thenReturn(Optional.of(savedMapping));
            doThrow(new IllegalArgumentException("GL account not found"))
                    .when(glAccountService).validateAccountForPosting(any(), any());

            // Act & Assert
            assertThatThrownBy(() -> service.updateDefaultMapping(MAPPING_ID, validRequest))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deactivateDefaultMapping")
    class DeactivateDefaultMapping {

        @Test
        @DisplayName("should deactivate existing mapping")
        void shouldDeactivateExistingMapping() {
            // Arrange
            when(repository.findById(MAPPING_ID)).thenReturn(Optional.of(savedMapping));
            when(repository.save(any(DefaultGLMapping.class))).thenReturn(savedMapping);

            // Act
            service.deactivateDefaultMapping(MAPPING_ID);

            // Assert
            ArgumentCaptor<DefaultGLMapping> captor = ArgumentCaptor.forClass(DefaultGLMapping.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getActive()).isFalse();
        }

        @Test
        @DisplayName("should throw when mapping not found for deactivation")
        void shouldThrowWhenMappingNotFoundForDeactivation() {
            // Arrange
            UUID unknownId = UUID.randomUUID();
            when(repository.findById(unknownId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.deactivateDefaultMapping(unknownId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Default GL mapping not found");

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getDefaultMapping")
    class GetDefaultMapping {

        @Test
        @DisplayName("should return mapping when found")
        void shouldReturnMappingWhenFound() {
            // Arrange
            when(repository.findById(MAPPING_ID)).thenReturn(Optional.of(savedMapping));
            when(glAccountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.getDefaultMapping(MAPPING_ID);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getMappingId()).isEqualTo(MAPPING_ID);
            assertThat(response.getEventType()).isEqualTo(EVENT_TYPE);
        }

        @Test
        @DisplayName("should throw when mapping not found")
        void shouldThrowWhenMappingNotFound() {
            // Arrange
            UUID unknownId = UUID.randomUUID();
            when(repository.findById(unknownId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.getDefaultMapping(unknownId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Default GL mapping not found");
        }

        @Test
        @DisplayName("should handle missing GL accounts gracefully in response")
        void shouldHandleMissingGLAccountsInResponse() {
            // Arrange
            when(repository.findById(MAPPING_ID)).thenReturn(Optional.of(savedMapping));
            when(glAccountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.empty());
            when(glAccountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.empty());

            // Act
            DefaultGLMappingResponse response = service.getDefaultMapping(MAPPING_ID);

            // Assert — mapper treats missing accounts as null code/name
            assertThat(response).isNotNull();
            assertThat(response.getDebitAccountCode()).isNull();
            assertThat(response.getDebitAccountName()).isNull();
            assertThat(response.getCreditAccountCode()).isNull();
            assertThat(response.getCreditAccountName()).isNull();
        }
    }

    @Nested
    @DisplayName("listDefaultMappings")
    class ListDefaultMappings {

        @Test
        @DisplayName("should return paginated results")
        void shouldReturnPaginatedResults() {
            // Arrange
            Pageable pageable = PageRequest.of(0, 10, Sort.by("eventType"));
            Page<DefaultGLMapping> page = new PageImpl<>(List.of(savedMapping), pageable, 1);
            when(repository.findAll(pageable)).thenReturn(page);
            when(glAccountRepository.findAllById(any())).thenReturn(List.of(debitAccount, creditAccount));

            // Act
            DefaultGLMappingListResponse response = service.listDefaultMappings(0, 10);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getMappings()).hasSize(1);
            assertThat(response.getPage()).isZero();
            assertThat(response.getSize()).isEqualTo(10);
            assertThat(response.getTotalElements()).isEqualTo(1);
            assertThat(response.getTotalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("should return empty page when no mappings exist")
        void shouldReturnEmptyPageWhenNoMappings() {
            // Arrange
            Pageable pageable = PageRequest.of(0, 10, Sort.by("eventType"));
            Page<DefaultGLMapping> emptyPage = new PageImpl<>(List.of(), pageable, 0);
            when(repository.findAll(pageable)).thenReturn(emptyPage);

            // Act
            DefaultGLMappingListResponse response = service.listDefaultMappings(0, 10);

            // Assert
            assertThat(response.getMappings()).isEmpty();
            assertThat(response.getTotalElements()).isZero();
            assertThat(response.getTotalPages()).isZero();
        }
    }

    @Nested
    @DisplayName("findActiveDefaultForEvent")
    class FindActiveDefaultForEvent {

        @Test
        @DisplayName("should return mapping when found for event type and org")
        void shouldReturnMappingWhenFoundForEventTypeAndOrg() {
            // Arrange
            when(repository.findActiveDefaultForEvent(EVENT_TYPE, ORG_ID))
                    .thenReturn(Optional.of(savedMapping));
            when(glAccountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.findActiveDefaultForEvent(EVENT_TYPE, ORG_ID);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(response.getOrganizationId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("should return null when no mapping found")
        void shouldReturnNullWhenNoMappingFound() {
            // Arrange
            when(repository.findActiveDefaultForEvent("UNKNOWN_EVENT", ORG_ID))
                    .thenReturn(Optional.empty());

            // Act
            DefaultGLMappingResponse response = service.findActiveDefaultForEvent("UNKNOWN_EVENT", ORG_ID);

            // Assert
            assertThat(response).isNull();
        }

        @Test
        @DisplayName("should support null organizationId for global lookup")
        void shouldSupportNullOrgForGlobalLookup() {
            // Arrange
            DefaultGLMapping globalMapping = new DefaultGLMapping();
            globalMapping.setMappingId(MAPPING_ID);
            globalMapping.setEventType(EVENT_TYPE);
            globalMapping.setOrganizationId(null);
            globalMapping.setDebitAccountId(DEBIT_ACCOUNT_ID);
            globalMapping.setCreditAccountId(CREDIT_ACCOUNT_ID);
            globalMapping.setActive(true);
            globalMapping.setCreatedAt(Instant.now(TEST_CLOCK));
            globalMapping.setCreatedBy("system");
            globalMapping.setUpdatedAt(Instant.now(TEST_CLOCK));
            globalMapping.setModifiedBy("system");

            when(repository.findActiveDefaultForEvent(EVENT_TYPE, null))
                    .thenReturn(Optional.of(globalMapping));
            when(glAccountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.findActiveDefaultForEvent(EVENT_TYPE, null);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getOrganizationId()).isNull();
        }
    }

    @Nested
    @DisplayName("findByEventType")
    class FindByEventType {

        @Test
        @DisplayName("should return list of active mappings for event type")
        void shouldReturnActiveMappingsForEventType() {
            // Arrange
            when(repository.findByEventTypeAndActiveTrue(EVENT_TYPE))
                    .thenReturn(List.of(savedMapping));
            when(glAccountRepository.findAllById(any())).thenReturn(List.of(debitAccount, creditAccount));

            // Act
            List<DefaultGLMappingResponse> responses = service.findByEventType(EVENT_TYPE);

            // Assert
            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getEventType()).isEqualTo(EVENT_TYPE);
        }

        @Test
        @DisplayName("should return empty list when no active mappings exist")
        void shouldReturnEmptyListWhenNoActiveMappings() {
            // Arrange
            when(repository.findByEventTypeAndActiveTrue("NO_SUCH_EVENT"))
                    .thenReturn(List.of());

            // Act
            List<DefaultGLMappingResponse> responses = service.findByEventType("NO_SUCH_EVENT");

            // Assert
            assertThat(responses).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByOrganization")
    class FindByOrganization {

        @Test
        @DisplayName("should return active mappings for organization")
        void shouldReturnActiveMappingsForOrganization() {
            // Arrange
            when(repository.findByOrganizationIdAndActiveTrue(ORG_ID))
                    .thenReturn(List.of(savedMapping));
            when(glAccountRepository.findAllById(any())).thenReturn(List.of(debitAccount, creditAccount));

            // Act
            List<DefaultGLMappingResponse> responses = service.findByOrganization(ORG_ID);

            // Assert
            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getOrganizationId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("should return empty list for organization with no mappings")
        void shouldReturnEmptyForOrgWithNoMappings() {
            // Arrange
            UUID emptyOrg = UUID.randomUUID();
            when(repository.findByOrganizationIdAndActiveTrue(emptyOrg))
                    .thenReturn(List.of());

            // Act
            List<DefaultGLMappingResponse> responses = service.findByOrganization(emptyOrg);

            // Assert
            assertThat(responses).isEmpty();
        }
    }

    @Nested
    @DisplayName("findAllGlobalDefaults")
    class FindAllGlobalDefaults {

        @Test
        @DisplayName("should return all global default mappings")
        void shouldReturnAllGlobalDefaults() {
            // Arrange
            DefaultGLMapping globalMapping = new DefaultGLMapping();
            globalMapping.setMappingId(UUID.randomUUID());
            globalMapping.setEventType("REFUND_ISSUED");
            globalMapping.setOrganizationId(null);
            globalMapping.setDebitAccountId(DEBIT_ACCOUNT_ID);
            globalMapping.setCreditAccountId(CREDIT_ACCOUNT_ID);
            globalMapping.setActive(true);
            globalMapping.setCreatedAt(Instant.now(TEST_CLOCK));
            globalMapping.setCreatedBy("system");
            globalMapping.setUpdatedAt(Instant.now(TEST_CLOCK));
            globalMapping.setModifiedBy("system");

            when(repository.findAllGlobalDefaults())
                    .thenReturn(List.of(savedMapping, globalMapping));
            when(glAccountRepository.findAllById(any())).thenReturn(List.of(debitAccount, creditAccount));

            // Act
            List<DefaultGLMappingResponse> responses = service.findAllGlobalDefaults();

            // Assert
            assertThat(responses).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list when no global defaults exist")
        void shouldReturnEmptyWhenNoGlobalDefaults() {
            // Arrange
            when(repository.findAllGlobalDefaults()).thenReturn(List.of());

            // Act
            List<DefaultGLMappingResponse> responses = service.findAllGlobalDefaults();

            // Assert
            assertThat(responses).isEmpty();
        }
    }
}
