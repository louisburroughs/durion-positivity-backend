package com.positivity.supplier.internal.vendor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.domainevents.supplier.SupplierVendorUpdatedV1;
import com.positivity.supplier.PostgresSliceTestBase;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierProfilePersistenceFixtures;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierForbiddenException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierVendorRemitChangeRepository;
import com.positivity.supplier.internal.repository.SupplierVendorRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.vendor.service.model.RemitApprovalRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeStatus;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeView;
import com.positivity.supplier.internal.vendor.service.model.RemitRejectionRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitToDto;
import com.positivity.supplier.internal.vendor.service.model.TaxRegistrationDto;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorFactReplayResult;
import com.positivity.supplier.internal.vendor.service.model.VendorStatus;
import com.positivity.supplier.internal.vendor.service.model.VendorStatusChangeRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorUpdateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorView;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The vendor master against the real schema (#2516, SPEC §4.9): number allocation and uniqueness,
 * the remit-to state machine and its second-person rule, status changes, stale updates, and the
 * {@code supplier.vendor.updated} rows each committed change queues.
 */
@Import({
    JpaConfig.class,
    SupplierVendorServiceImpl.class,
    VendorNumberAllocator.class,
    VendorFactPublisher.class,
    SupplierOutboxEventWriter.class,
    SupplierVendorServiceImplTest.SupportConfig.class
})
@DisplayName("SupplierVendorServiceImpl — the vendor master (#2516)")
class SupplierVendorServiceImplTest extends PostgresSliceTestBase {

    @TestConfiguration
    static class SupportConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final RemitToDto REMIT_V1 =
            new RemitToDto("Michelin NA", "PO Box 100", null, "Greenville", "SC", "29615", "US", null);
    private static final RemitToDto REMIT_V2 =
            new RemitToDto("Michelin NA", "PO Box 200", "Lockbox 7", "Greenville", "SC", "29615", "US", "ar@m.example");

    @Autowired
    private SupplierVendorService service;

    @Autowired
    private SupplierVendorRepository vendorRepository;

    @Autowired
    private SupplierVendorRemitChangeRepository remitChangeRepository;

    @Autowired
    private SupplierOutboxEventRepository outboxRepository;

    @Autowired
    private SupplierProfileRepository profileRepository;

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    private static <T> T as(String user, Supplier<T> work) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user, "n/a", List.of());
        authentication.setDetails(Map.of("username", user));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static VendorCreateRequest create(String vendorNumber, RemitToDto remitTo) {
        return new VendorCreateRequest(
                vendorNumber,
                "Michelin North America, Inc.",
                "Michelin",
                List.of(new TaxRegistrationDto("EIN", "12-3456789", null)),
                remitTo,
                "NET30",
                "USD");
    }

    private List<SupplierOutboxEventEntity> vendorFacts(UUID vendorId) {
        return outboxRepository.findAll().stream()
                .filter(row -> SupplierVendorUpdatedV1.EVENT_TYPE.equals(row.getEventType()))
                .filter(row -> row.getRecordKey().equals(vendorId.toString()))
                .sorted(java.util.Comparator.comparing(SupplierOutboxEventEntity::getId))
                .toList();
    }

    private static JsonNode envelope(SupplierOutboxEventEntity row) {
        return JSON.readTree(row.getPayload());
    }

    @Nested
    @DisplayName("creating a vendor")
    class Create {

        @Test
        @DisplayName(
                "AC 1: without a number, V-000001 then V-000002 are allocated, ACTIVE, one fact each keyed on vendorId")
        void allocatesNumbersAndQueuesOneFactInTheSameTransaction() {
            VendorView first = as("clerk.a", () -> service.createVendor(create(null, null)));
            VendorView second = as("clerk.a", () -> service.createVendor(create(null, null)));

            assertThat(first.vendorNumber()).isEqualTo("V-000001");
            assertThat(second.vendorNumber()).isEqualTo("V-000002");
            assertThat(first.status()).isEqualTo(VendorStatus.ACTIVE);
            assertThat(first.remitToVersion()).isZero();

            List<SupplierOutboxEventEntity> facts = vendorFacts(first.vendorId());
            assertThat(facts).singleElement().satisfies(row -> {
                assertThat(row.getTopic()).isEqualTo("supplier.events.v1");
                assertThat(row.getTenantId()).isEqualTo(TENANT);
                JsonNode envelope = envelope(row);
                assertThat(envelope.path("aggregateId").stringValue())
                        .isEqualTo(first.vendorId().toString());
                assertThat(envelope.path("aggregateVersion").asLong()).isEqualTo(first.version());
                assertThat(envelope.path("payload").path("vendorNumber").stringValue())
                        .isEqualTo("V-000001");
                assertThat(envelope.path("payload").path("status").stringValue())
                        .isEqualTo("ACTIVE");
                assertThat(envelope.path("payload").path("createdBy").stringValue())
                        .isEqualTo("clerk.a");
            });
        }

        @Test
        @DisplayName("an allocated number skips a number someone already chose by hand")
        void allocationSkipsAHandChosenNumber() {
            as("clerk.a", () -> service.createVendor(create("V-000001", null)));

            VendorView allocated = as("clerk.a", () -> service.createVendor(create(null, null)));

            assertThat(allocated.vendorNumber()).isEqualTo("V-000002");
        }

        @Test
        @DisplayName("AC 2: a number already used in the tenant is 409 SUPPLIER_VENDOR_NUMBER_TAKEN")
        void duplicateNumberIsTaken() {
            as("clerk.a", () -> service.createVendor(create("MICHELIN", null)));

            assertThatThrownBy(() -> as("clerk.a", () -> service.createVendor(create("MICHELIN", null))))
                    .isInstanceOf(SupplierConflictException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConflictException.VENDOR_NUMBER_TAKEN);
        }

        @Test
        @DisplayName("a remit-to given at creation is version 1 without approval; the creator is its requester")
        void remitToAtCreationIsVersionOne() {
            VendorView vendor = as("clerk.a", () -> service.createVendor(create("MICHELIN", REMIT_V1)));

            assertThat(vendor.remitToVersion()).isEqualTo(1);
            assertThat(vendor.remitTo()).isEqualTo(REMIT_V1);
            assertThat(vendor.remitToRequestedBy()).isEqualTo("clerk.a");
            assertThat(vendor.remitToApprovedBy()).isNull();
        }
    }

    @Nested
    @DisplayName("remit-to changes need a second person")
    class RemitTo {

        @Test
        @DisplayName("AC 3: requesting publishes nothing; the requester cannot approve; a second person can")
        void secondPersonRule() {
            VendorView vendor = as("clerk.a", () -> service.createVendor(create("MICHELIN", REMIT_V1)));
            int factsBefore = vendorFacts(vendor.vendorId()).size();

            RemitChangeView requested = as(
                    "clerk.a",
                    () -> service.requestRemitChange(
                            vendor.vendorId(), new RemitChangeRequest(REMIT_V2, "Vendor letter: new lockbox address")));

            assertThat(requested.status()).isEqualTo(RemitChangeStatus.PENDING);
            assertThat(service.getVendor(vendor.vendorId()).remitToVersion()).isEqualTo(1);
            assertThat(service.getVendor(vendor.vendorId()).remitTo()).isEqualTo(REMIT_V1);
            assertThat(vendorFacts(vendor.vendorId()))
                    .as("a pending change is never published")
                    .hasSize(factsBefore);

            assertThatThrownBy(() -> as(
                            "clerk.a",
                            () -> service.approveRemitChange(
                                    vendor.vendorId(),
                                    requested.changeId(),
                                    new RemitApprovalRequest("Approving my own change, which must fail"))))
                    .isInstanceOf(SupplierForbiddenException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierForbiddenException.VENDOR_REMIT_SELF_APPROVAL);
            assertThat(service.getVendor(vendor.vendorId()).remitToVersion()).isEqualTo(1);

            RemitChangeView approved = as(
                    "controller.b",
                    () -> service.approveRemitChange(
                            vendor.vendorId(),
                            requested.changeId(),
                            new RemitApprovalRequest("Called the AR line on file; lockbox confirmed")));

            assertThat(approved.status()).isEqualTo(RemitChangeStatus.APPROVED);
            assertThat(approved.toVersion()).isEqualTo(2);
            assertThat(approved.decidedBy()).isEqualTo("controller.b");
            VendorView after = service.getVendor(vendor.vendorId());
            assertThat(after.remitToVersion()).isEqualTo(2);
            assertThat(after.remitTo()).isEqualTo(REMIT_V2);

            List<SupplierOutboxEventEntity> facts = vendorFacts(vendor.vendorId());
            assertThat(facts).hasSize(factsBefore + 1);
            JsonNode payload = envelope(facts.getLast()).path("payload");
            assertThat(payload.path("remitToVersion").asInt()).isEqualTo(2);
            assertThat(payload.path("remitTo").path("addressLine1").stringValue())
                    .isEqualTo("PO Box 200");
            assertThat(payload.path("remitToRequestedBy").stringValue()).isEqualTo("clerk.a");
            assertThat(payload.path("remitToApprovedBy").stringValue()).isEqualTo("controller.b");
            assertThat(envelope(facts.getLast()).path("aggregateVersion").asLong())
                    .as("the fact carries the version the approval produced")
                    .isEqualTo(after.version())
                    .isGreaterThan(
                            envelope(facts.getFirst()).path("aggregateVersion").asLong());
        }

        @Test
        @DisplayName("AC 4: a second request while one is pending is 409 SUPPLIER_VENDOR_REMIT_CHANGE_PENDING")
        void onePendingPerVendor() {
            VendorView vendor = as("clerk.a", () -> service.createVendor(create("MICHELIN", null)));
            as(
                    "clerk.a",
                    () -> service.requestRemitChange(
                            vendor.vendorId(), new RemitChangeRequest(REMIT_V1, "First remit-to for this vendor")));

            assertThatThrownBy(() -> as(
                            "clerk.a",
                            () -> service.requestRemitChange(
                                    vendor.vendorId(), new RemitChangeRequest(REMIT_V2, "A second one while pending"))))
                    .isInstanceOf(SupplierConflictException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConflictException.VENDOR_REMIT_CHANGE_PENDING);
        }

        @Test
        @DisplayName("a decided change cannot be decided again: 409 SUPPLIER_VENDOR_REMIT_CHANGE_NOT_PENDING")
        void decidedChangeIsTerminal() {
            VendorView vendor = as("clerk.a", () -> service.createVendor(create("MICHELIN", null)));
            RemitChangeView change = as(
                    "clerk.a",
                    () -> service.requestRemitChange(
                            vendor.vendorId(), new RemitChangeRequest(REMIT_V1, "First remit-to for this vendor")));
            RemitChangeView rejected = as(
                    "controller.b",
                    () -> service.rejectRemitChange(
                            vendor.vendorId(), change.changeId(), new RemitRejectionRequest("AR line knows nothing")));

            assertThat(rejected.status()).isEqualTo(RemitChangeStatus.REJECTED);
            assertThat(service.getVendor(vendor.vendorId()).remitToVersion()).isZero();
            assertThatThrownBy(() -> as(
                            "controller.b",
                            () -> service.approveRemitChange(
                                    vendor.vendorId(),
                                    change.changeId(),
                                    new RemitApprovalRequest("Approving after a rejection"))))
                    .isInstanceOf(SupplierConflictException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConflictException.VENDOR_REMIT_CHANGE_NOT_PENDING);
            assertThat(service.listRemitChanges(vendor.vendorId(), RemitChangeStatus.REJECTED))
                    .extracting(RemitChangeView::changeId)
                    .containsExactly(change.changeId());
            assertThat(remitChangeRepository.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("status and updates")
    class StatusAndUpdates {

        @Test
        @DisplayName("AC 5: deactivation publishes INACTIVE with statusChangedAt and leaves the profiles enabled")
        void deactivationKeepsProfilesEnabled() {
            VendorView vendor = as("clerk.a", () -> service.createVendor(create("MICHELIN", null)));
            SupplierProfileEntity profile = profileRepository.saveAndFlush(
                    SupplierProfilePersistenceFixtures.profile("michelin-us", vendor.vendorId()));

            VendorView inactive = as(
                    "clerk.a",
                    () -> service.deactivateVendor(
                            vendor.vendorId(), new VendorStatusChangeRequest("Merged into another vendor")));

            assertThat(inactive.status()).isEqualTo(VendorStatus.INACTIVE);
            assertThat(inactive.statusChangedAt()).isNotNull();
            JsonNode payload =
                    envelope(vendorFacts(vendor.vendorId()).getLast()).path("payload");
            assertThat(payload.path("status").stringValue()).isEqualTo("INACTIVE");
            assertThat(payload.path("statusChangedAt").isMissingNode()).isFalse();
            assertThat(payload.path("statusReason").stringValue()).isEqualTo("Merged into another vendor");
            assertThat(profileRepository
                            .findById(profile.getVendorProfileId())
                            .orElseThrow()
                            .isEnabled())
                    .isTrue();

            assertThatThrownBy(() -> as(
                            "clerk.a",
                            () -> service.deactivateVendor(
                                    vendor.vendorId(), new VendorStatusChangeRequest("Deactivating it twice"))))
                    .isInstanceOf(SupplierConflictException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConflictException.CONFLICT);

            VendorView reactivated = as(
                    "clerk.a",
                    () -> service.reactivateVendor(
                            vendor.vendorId(), new VendorStatusChangeRequest("Trading with them again")));
            assertThat(reactivated.status()).isEqualTo(VendorStatus.ACTIVE);
        }

        @Test
        @DisplayName("an update carrying a stale version is 409 CONFLICT; the vendor number never changes")
        void staleUpdateConflicts() {
            VendorView vendor = as("clerk.a", () -> service.createVendor(create("MICHELIN", null)));
            VendorUpdateRequest update = new VendorUpdateRequest(
                    "Michelin North America, Inc.", "Michelin NA", List.of(), "NET45", "USD", vendor.version());

            VendorView updated = as("clerk.a", () -> service.updateVendor(vendor.vendorId(), update));

            assertThat(updated.displayName()).isEqualTo("Michelin NA");
            assertThat(updated.defaultPaymentTerms()).isEqualTo("NET45");
            assertThat(updated.vendorNumber()).isEqualTo("MICHELIN");
            assertThat(updated.version()).isGreaterThan(vendor.version());
            assertThatThrownBy(() -> as("clerk.b", () -> service.updateVendor(vendor.vendorId(), update)))
                    .isInstanceOf(SupplierConflictException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConflictException.CONFLICT);
        }

        @Test
        @DisplayName("an unknown vendor is 404 SUPPLIER_VENDOR_NOT_FOUND")
        void unknownVendorIsNotFound() {
            assertThatThrownBy(() -> service.getVendor(UUID.randomUUID()))
                    .isInstanceOf(SupplierNotFoundException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierNotFoundException.VENDOR_NOT_FOUND);
        }

        @Test
        @DisplayName("the list matches number, display and legal name and narrows by status")
        void listFiltersByTextAndStatus() {
            VendorView michelin = as("clerk.a", () -> service.createVendor(create("MICHELIN", null)));
            as(
                    "clerk.a",
                    () -> service.createVendor(new VendorCreateRequest(
                            "CONTI", "Continental Tire the Americas", "Continental", null, null, "NET60", "USD")));
            as(
                    "clerk.a",
                    () -> service.deactivateVendor(
                            michelin.vendorId(), new VendorStatusChangeRequest("No longer trading with them")));

            assertThat(service.listVendors("conti", null, 0, 50).items())
                    .extracting(VendorView::vendorNumber)
                    .containsExactly("CONTI");
            assertThat(service.listVendors(null, VendorStatus.INACTIVE, 0, 50).items())
                    .extracting(VendorView::vendorNumber)
                    .containsExactly("MICHELIN");
            assertThat(service.listVendors(null, null, 0, 50).totalElements()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("facts replay (ADR-0044 §4)")
    class Replay {

        @Test
        @DisplayName("AC 11: one fact per vendor of the tenant at its current version, paged by cursor")
        void replaysOneFactPerVendorAtItsCurrentVersion() {
            VendorView a = as("clerk.a", () -> service.createVendor(create("A-VENDOR", null)));
            VendorView b = as("clerk.a", () -> service.createVendor(create("B-VENDOR", null)));
            VendorView c = as("clerk.a", () -> service.createVendor(create("C-VENDOR", null)));
            VendorView bUpdated = as(
                    "clerk.a",
                    () -> service.updateVendor(
                            b.vendorId(),
                            new VendorUpdateRequest("B Legal", "B", List.of(), "NET10", "USD", b.version())));
            outboxRepository.deleteAll();

            VendorFactReplayResult firstPage = as("admin", () -> service.replayFacts(null, 2));
            VendorFactReplayResult secondPage =
                    as("admin", () -> service.replayFacts(firstPage.nextAfterVendorId(), 2));

            assertThat(firstPage.emitted()).isEqualTo(2);
            assertThat(firstPage.complete()).isFalse();
            assertThat(secondPage.emitted()).isEqualTo(1);
            assertThat(secondPage.complete()).isTrue();
            assertThat(secondPage.nextAfterVendorId()).isNull();
            List<SupplierOutboxEventEntity> rows = outboxRepository.findAll();
            assertThat(rows)
                    .extracting(SupplierOutboxEventEntity::getRecordKey)
                    .containsExactlyInAnyOrder(
                            a.vendorId().toString(),
                            b.vendorId().toString(),
                            c.vendorId().toString());
            SupplierOutboxEventEntity bRow = rows.stream()
                    .filter(row -> row.getRecordKey().equals(b.vendorId().toString()))
                    .findFirst()
                    .orElseThrow();
            assertThat(envelope(bRow).path("aggregateVersion").asLong()).isEqualTo(bUpdated.version());
        }
    }
}
