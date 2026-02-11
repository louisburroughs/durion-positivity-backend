package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.positivity.accounting.internal.dto.DefaultGLMappingListResponse;
import com.positivity.accounting.internal.dto.DefaultGLMappingRequest;
import com.positivity.accounting.internal.dto.DefaultGLMappingResponse;
import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;

/**
 * Unit tests for DefaultGLMappingServiceImpl
 * 
 * Tests default GL mapping creation, update, retrieval, and resolution logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultGLMappingService Unit Tests")
class DefaultGLMappingServiceImplTest {

    @Mock
    private DefaultGLMappingRepository repository;

    @Mock
    private GLAccountRepository glAccountRepository;

    @Mock
    private GLAccountService glAccountService;

    @InjectMocks
    private DefaultGLMappingServiceImpl service;

    private UUID debitAccountId;
    private UUID creditAccountId;
    private UUID organizationId;
    private DefaultGLMapping testMapping;
    private DefaultGLMappingRequest testRequest;
    private GLAccount debitAccount;
    private GLAccount creditAccount;

    @BeforeEach
    void setUp() {
        debitAccountId = UUID.randomUUID();
        creditAccountId = UUID.randomUUID();
        organizationId = UUID.randomUUID();

        testMapping = new DefaultGLMapping();
        testMapping.setMappingId(UUID.randomUUID());
        testMapping.setEventType("billing.invoicePosted");
        testMapping.setOrganizationId(organizationId);
        testMapping.setDebitAccountId(debitAccountId);
        testMapping.setCreditAccountId(creditAccountId);
        testMapping.setDescription("Test mapping");
        testMapping.setActive(true);
        testMapping.setCreatedAt(Instant.now());
        testMapping.setCreatedBy("test-user");
        testMapping.setModifiedAt(Instant.now());
        testMapping.setModifiedBy("test-user");

        testRequest = DefaultGLMappingRequest.builder()
                .eventType("billing.invoicePosted")
                .organizationId(organizationId)
                .debitAccountId(debitAccountId)
                .creditAccountId(creditAccountId)
                .description("Test mapping")
                .active(true)
                .build();

        debitAccount = new GLAccount();
        debitAccount.setGlAccountId(debitAccountId);
        debitAccount.setAccountCode("1100");
        debitAccount.setAccountName("Accounts Receivable");

        creditAccount = new GLAccount();
        creditAccount.setGlAccountId(creditAccountId);
        creditAccount.setAccountCode("4000");
        creditAccount.setAccountName("Revenue");
    }

    @Nested
    @DisplayName("Create Default Mapping Tests")
    class CreateDefaultMappingTests {

        @Test
        @DisplayName("Should create default mapping successfully")
        void shouldCreateDefaultMappingSuccessfully() {
            // Arrange
            when(repository.save(any(DefaultGLMapping.class))).thenReturn(testMapping);
            when(glAccountRepository.findById(debitAccountId)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.createDefaultMapping(testRequest);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getEventType()).isEqualTo("billing.invoicePosted");
            assertThat(response.getDebitAccountId()).isEqualTo(debitAccountId);
            assertThat(response.getCreditAccountId()).isEqualTo(creditAccountId);
            assertThat(response.getDebitAccountCode()).isEqualTo("1100");
            assertThat(response.getCreditAccountCode()).isEqualTo("4000");

            verify(glAccountService).validateAccountForPosting(eq(debitAccountId), any());
            verify(glAccountService).validateAccountForPosting(eq(creditAccountId), any());
            verify(repository).save(any(DefaultGLMapping.class));
        }

        @Test
        @DisplayName("Should validate GL accounts before creating")
        void shouldValidateGLAccountsBeforeCreating() {
            // Arrange
            when(glAccountService.validateAccountForPosting(eq(debitAccountId), any()))
                    .thenThrow(new IllegalArgumentException("Invalid debit account"));

            // Act & Assert
            assertThatThrownBy(() -> service.createDefaultMapping(testRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid debit account");

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Update Default Mapping Tests")
    class UpdateDefaultMappingTests {

        @Test
        @DisplayName("Should update default mapping successfully")
        void shouldUpdateDefaultMappingSuccessfully() {
            // Arrange
            UUID mappingId = testMapping.getMappingId();
            DefaultGLMappingRequest updateRequest = DefaultGLMappingRequest.builder()
                    .eventType("billing.paymentReceived")
                    .organizationId(organizationId)
                    .debitAccountId(debitAccountId)
                    .creditAccountId(creditAccountId)
                    .description("Updated mapping")
                    .active(true)
                    .build();

            when(repository.findById(mappingId)).thenReturn(Optional.of(testMapping));
            when(repository.save(testMapping)).thenReturn(testMapping);
            when(glAccountRepository.findById(debitAccountId)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.updateDefaultMapping(mappingId, updateRequest);

            // Assert
            assertThat(response).isNotNull();
            verify(glAccountService).validateAccountForPosting(eq(debitAccountId), any());
            verify(glAccountService).validateAccountForPosting(eq(creditAccountId), any());
            verify(repository).save(testMapping);
        }

        @Test
        @DisplayName("Should throw exception when mapping not found for update")
        void shouldThrowExceptionWhenMappingNotFoundForUpdate() {
            // Arrange
            UUID mappingId = UUID.randomUUID();
            when(repository.findById(mappingId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.updateDefaultMapping(mappingId, testRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    @DisplayName("Deactivate Default Mapping Tests")
    class DeactivateDefaultMappingTests {

        @Test
        @DisplayName("Should deactivate default mapping successfully")
        void shouldDeactivateDefaultMappingSuccessfully() {
            // Arrange
            UUID mappingId = testMapping.getMappingId();
            when(repository.findById(mappingId)).thenReturn(Optional.of(testMapping));
            when(repository.save(testMapping)).thenReturn(testMapping);

            // Act
            service.deactivateDefaultMapping(mappingId);

            // Assert
            assertThat(testMapping.getActive()).isFalse();
            verify(repository).save(testMapping);
        }

        @Test
        @DisplayName("Should throw exception when mapping not found for deactivation")
        void shouldThrowExceptionWhenMappingNotFoundForDeactivation() {
            // Arrange
            UUID mappingId = UUID.randomUUID();
            when(repository.findById(mappingId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.deactivateDefaultMapping(mappingId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    @DisplayName("Get Default Mapping Tests")
    class GetDefaultMappingTests {

        @Test
        @DisplayName("Should retrieve default mapping by ID")
        void shouldRetrieveDefaultMappingById() {
            // Arrange
            UUID mappingId = testMapping.getMappingId();
            when(repository.findById(mappingId)).thenReturn(Optional.of(testMapping));
            when(glAccountRepository.findById(debitAccountId)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.getDefaultMapping(mappingId);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getMappingId()).isEqualTo(mappingId);
            assertThat(response.getEventType()).isEqualTo("billing.invoicePosted");
        }

        @Test
        @DisplayName("Should throw exception when mapping not found")
        void shouldThrowExceptionWhenMappingNotFound() {
            // Arrange
            UUID mappingId = UUID.randomUUID();
            when(repository.findById(mappingId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.getDefaultMapping(mappingId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    @DisplayName("List Default Mappings Tests")
    class ListDefaultMappingsTests {

        @Test
        @DisplayName("Should list default mappings with pagination")
        void shouldListDefaultMappingsWithPagination() {
            // Arrange
            List<DefaultGLMapping> mappings = List.of(testMapping);
            Page<DefaultGLMapping> page = new PageImpl<>(mappings, PageRequest.of(0, 20), 1);

            when(repository.findAll(any(Pageable.class))).thenReturn(page);
            when(glAccountRepository.findById(debitAccountId)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingListResponse response = service.listDefaultMappings(0, 20);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getMappings()).hasSize(1);
            assertThat(response.getPage()).isEqualTo(0);
            assertThat(response.getSize()).isEqualTo(20);
            assertThat(response.getTotalElements()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Find Active Default For Event Tests")
    class FindActiveDefaultForEventTests {

        @Test
        @DisplayName("Should find organization-specific default mapping")
        void shouldFindOrganizationSpecificDefaultMapping() {
            // Arrange
            String eventType = "billing.invoicePosted";
            when(repository.findActiveDefaultForEvent(eventType, organizationId))
                    .thenReturn(Optional.of(testMapping));
            when(glAccountRepository.findById(debitAccountId)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));

            // Act
            DefaultGLMappingResponse response = service.findActiveDefaultForEvent(eventType, organizationId);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getEventType()).isEqualTo(eventType);
            assertThat(response.getOrganizationId()).isEqualTo(organizationId);
        }

        @Test
        @DisplayName("Should return null when no default mapping found")
        void shouldReturnNullWhenNoDefaultMappingFound() {
            // Arrange
            String eventType = "billing.unknownEvent";
            when(repository.findActiveDefaultForEvent(eventType, organizationId))
                    .thenReturn(Optional.empty());

            // Act
            DefaultGLMappingResponse response = service.findActiveDefaultForEvent(eventType, organizationId);

            // Assert
            assertThat(response).isNull();
        }
    }

    @Nested
    @DisplayName("Find By Event Type Tests")
    class FindByEventTypeTests {

        @Test
        @DisplayName("Should find all active defaults for event type")
        void shouldFindAllActiveDefaultsForEventType() {
            // Arrange
            String eventType = "billing.invoicePosted";
            when(repository.findByEventTypeAndActiveTrue(eventType)).thenReturn(List.of(testMapping));
            when(glAccountRepository.findById(debitAccountId)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));

            // Act
            List<DefaultGLMappingResponse> responses = service.findByEventType(eventType);

            // Assert
            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getEventType()).isEqualTo(eventType);
        }
    }

    @Nested
    @DisplayName("Find By Organization Tests")
    class FindByOrganizationTests {

        @Test
        @DisplayName("Should find all active defaults for organization")
        void shouldFindAllActiveDefaultsForOrganization() {
            // Arrange
            when(repository.findByOrganizationIdAndActiveTrue(organizationId))
                    .thenReturn(List.of(testMapping));
            when(glAccountRepository.findById(debitAccountId)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));

            // Act
            List<DefaultGLMappingResponse> responses = service.findByOrganization(organizationId);

            // Assert
            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getOrganizationId()).isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("Find All Global Defaults Tests")
    class FindAllGlobalDefaultsTests {

        @Test
        @DisplayName("Should find all global defaults")
        void shouldFindAllGlobalDefaults() {
            // Arrange
            DefaultGLMapping globalMapping = new DefaultGLMapping();
            globalMapping.setMappingId(UUID.randomUUID());
            globalMapping.setEventType("billing.invoicePosted");
            globalMapping.setOrganizationId(null); // Global default
            globalMapping.setDebitAccountId(debitAccountId);
            globalMapping.setCreditAccountId(creditAccountId);
            globalMapping.setActive(true);

            when(repository.findAllGlobalDefaults()).thenReturn(List.of(globalMapping));
            when(glAccountRepository.findById(debitAccountId)).thenReturn(Optional.of(debitAccount));
            when(glAccountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));

            // Act
            List<DefaultGLMappingResponse> responses = service.findAllGlobalDefaults();

            // Assert
            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getOrganizationId()).isNull();
        }
    }
}
