package com.positivity.tenant.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.tenant.internal.dto.MonthlyAllowanceDto;
import com.positivity.tenant.internal.dto.ReadingTierCreateRequest;
import com.positivity.tenant.internal.dto.ReadingTierResponse;
import com.positivity.tenant.internal.dto.ReadingTierUpdateRequest;
import com.positivity.tenant.internal.entity.ReadingTierEntity;
import com.positivity.tenant.internal.entity.TenantEntity;
import com.positivity.tenant.internal.enums.TenantStatus;
import com.positivity.tenant.internal.exception.ReadingTierExistsException;
import com.positivity.tenant.internal.exception.ReadingTierInUseException;
import com.positivity.tenant.internal.exception.ReadingTierNotAssignableException;
import com.positivity.tenant.internal.exception.ResourceNotFoundException;
import com.positivity.tenant.internal.repository.ReadingTierRepository;
import com.positivity.tenant.internal.repository.TenantRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReadingTierServiceImpl (#2619): the catalog, the fan-out on an allowance change, the in-use guard")
class ReadingTierServiceImplTest {

    private final ReadingTierRepository tiers = mock(ReadingTierRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantFactPublisher facts = mock(TenantFactPublisher.class);
    private final ReadingTierServiceImpl service = new ReadingTierServiceImpl(tiers, tenants, facts);
    private ReadingTierEntity standard;

    @BeforeEach
    void setUp() {
        standard = ReadingTierEntity.builder()
                .id(UUID.randomUUID())
                .code("STANDARD")
                .displayName("Standard")
                .monthlyAllowanceAmount(new BigDecimal("20.0000"))
                .monthlyAllowanceCurrency("USD")
                .active(true)
                .version(0L)
                .build();
        when(tiers.findByCode("STANDARD")).thenReturn(Optional.of(standard));
        when(tiers.lockByCode("STANDARD")).thenReturn(Optional.of(standard));
        when(tiers.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static TenantEntity tenantOn(String code, TenantStatus status) {
        return TenantEntity.builder()
                .id(UUID.randomUUID())
                .slug("t-" + UUID.randomUUID())
                .status(status)
                .readingTierCode(code)
                .version(1L)
                .build();
    }

    @Test
    @DisplayName("create: an active tier with its allowance; a taken code is 409 READING_TIER_EXISTS")
    void create() {
        when(tiers.existsByCode("PRO")).thenReturn(false);
        ReadingTierResponse created = service.create(ReadingTierCreateRequest.builder()
                .code("PRO")
                .displayName(" Pro ")
                .monthlyAllowance(new MonthlyAllowanceDto(new BigDecimal("60.00"), "USD"))
                .build());
        assertThat(created.getCode()).isEqualTo("PRO");
        assertThat(created.getDisplayName()).isEqualTo("Pro");
        assertThat(created.isActive()).isTrue();
        assertThat(created.getMonthlyAllowance().getAmount()).isEqualByComparingTo("60.00");
        verify(facts, never()).readingAllowanceChanged(any(), any());

        when(tiers.existsByCode("STANDARD")).thenReturn(true);
        assertThatThrownBy(() -> service.create(ReadingTierCreateRequest.builder()
                        .code("STANDARD")
                        .displayName("Again")
                        .monthlyAllowance(new MonthlyAllowanceDto(BigDecimal.ONE, "USD"))
                        .build()))
                .isInstanceOf(ReadingTierExistsException.class);
    }

    @Test
    @DisplayName("update: a changed allowance publishes one fact per non-decommissioned tenant on the tier (AC 4)")
    void repriceFansOut() {
        TenantEntity a = tenantOn("STANDARD", TenantStatus.ACTIVE);
        TenantEntity b = tenantOn("STANDARD", TenantStatus.SUSPENDED);
        when(tenants.findByReadingTierCodeAndStatusNotOrderByCreatedAtAsc("STANDARD", TenantStatus.DECOMMISSIONED))
                .thenReturn(List.of(a, b));

        ReadingTierResponse repriced = service.update(
                "STANDARD",
                ReadingTierUpdateRequest.builder()
                        .monthlyAllowance(new MonthlyAllowanceDto(new BigDecimal("25.00"), "USD"))
                        .build());

        assertThat(repriced.getMonthlyAllowance().getAmount()).isEqualByComparingTo("25.00");
        verify(facts).readingAllowanceChanged(a, standard);
        verify(facts).readingAllowanceChanged(b, standard);
    }

    @Test
    @DisplayName("update: the same allowance, or a new display name only, publishes nothing")
    void noFanOutWithoutAnAllowanceChange() {
        service.update(
                "STANDARD",
                ReadingTierUpdateRequest.builder()
                        .displayName("Standard plan")
                        .monthlyAllowance(new MonthlyAllowanceDto(new BigDecimal("20.00"), "USD"))
                        .build());
        assertThat(standard.getDisplayName()).isEqualTo("Standard plan");
        verify(facts, never()).readingAllowanceChanged(any(), any());
        verify(tenants, never()).findByReadingTierCodeAndStatusNotOrderByCreatedAtAsc(any(), any());
    }

    @Test
    @DisplayName("update: deactivating a tier a non-decommissioned tenant is on is 409 READING_TIER_IN_USE (AC 5);"
            + " a free tier deactivates; an unknown code is 404")
    void deactivate() {
        ReadingTierEntity pro = ReadingTierEntity.builder()
                .id(UUID.randomUUID())
                .code("PRO")
                .displayName("Pro")
                .monthlyAllowanceAmount(new BigDecimal("60.00"))
                .monthlyAllowanceCurrency("USD")
                .active(true)
                .version(0L)
                .build();
        when(tiers.lockByCode("PRO")).thenReturn(Optional.of(pro));
        when(tenants.existsByReadingTierCodeAndStatusNot("PRO", TenantStatus.DECOMMISSIONED))
                .thenReturn(true);
        assertThatThrownBy(() -> service.update("PRO", ReadingTierUpdateRequest.builder().active(false).build()))
                .isInstanceOf(ReadingTierInUseException.class);
        assertThat(pro.isActive()).isTrue();

        when(tenants.existsByReadingTierCodeAndStatusNot("PRO", TenantStatus.DECOMMISSIONED))
                .thenReturn(false);
        assertThat(service.update("PRO", ReadingTierUpdateRequest.builder().active(false).build())
                        .isActive())
                .isFalse();

        // STANDARD is the default every create falls back to: never deactivated, even when free.
        when(tenants.existsByReadingTierCodeAndStatusNot("STANDARD", TenantStatus.DECOMMISSIONED))
                .thenReturn(false);
        assertThatThrownBy(() -> service.update(
                        "STANDARD", ReadingTierUpdateRequest.builder().active(false).build()))
                .isInstanceOf(ReadingTierInUseException.class);
        assertThat(standard.isActive()).isTrue();

        when(tiers.lockByCode("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(
                        "NOPE", ReadingTierUpdateRequest.builder().active(true).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName(
            "requireAssignable: STANDARD by default; an inactive or unknown tier is 422 READING_TIER_NOT_ASSIGNABLE")
    void requireAssignable() {
        assertThat(service.requireAssignable(null)).isSameAs(standard);
        assertThat(service.requireAssignable(" ")).isSameAs(standard);
        assertThat(service.requireAssignable("STANDARD")).isSameAs(standard);

        standard.setActive(false);
        assertThatThrownBy(() -> service.requireAssignable("STANDARD"))
                .isInstanceOf(ReadingTierNotAssignableException.class);
        when(tiers.lockByCode("PRO")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireAssignable("PRO"))
                .isInstanceOf(ReadingTierNotAssignableException.class);
    }

    @Test
    @DisplayName("list: every tier by code, active or not")
    void list() {
        ReadingTierEntity retired = ReadingTierEntity.builder()
                .id(UUID.randomUUID())
                .code("LEGACY")
                .displayName("Legacy")
                .monthlyAllowanceAmount(BigDecimal.TEN)
                .monthlyAllowanceCurrency("USD")
                .active(false)
                .build();
        when(tiers.findAllByOrderByCodeAsc()).thenReturn(List.of(retired, standard));
        assertThat(service.list()).extracting(ReadingTierResponse::getCode).containsExactly("LEGACY", "STANDARD");
        assertThat(service.list()).extracting(ReadingTierResponse::isActive).containsExactly(false, true);
    }
}
