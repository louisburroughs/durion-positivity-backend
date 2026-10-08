package com.positivity.supplier.internal.vendor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.domainevents.supplier.SupplierVendorUpdatedV1;
import com.positivity.supplier.PostgresSliceTestBase;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierProfilePersistenceFixtures;
import com.positivity.supplier.internal.entity.VendorTaxIdCipher;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierForbiddenException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
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
import com.positivity.supplier.internal.vendor.service.model.TaxRegistrationView;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorFactReplayResult;
import com.positivity.supplier.internal.vendor.service.model.VendorStatus;
import com.positivity.supplier.internal.vendor.service.model.VendorStatusChangeRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorUpdateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorView;
import jakarta.persistence.EntityManager;
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

    @Autowired
    private VendorTaxIdCipher taxIdCipher;

    @Autowired
    private EntityManager entityManager;

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
                List.of(new TaxRegistrationDto(null, "EIN", "000-00-1234", null)),
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
        @DisplayName("an update that changes nothing publishes no fact: the version did not move")
        void noOpUpdatePublishesNothing() {
            VendorView vendor = as("clerk.a", () -> service.createVendor(create("MICHELIN", null)));
            int factsBefore = vendorFacts(vendor.vendorId()).size();
            VendorUpdateRequest same = new VendorUpdateRequest(
                    vendor.legalName(),
                    vendor.displayName(),
                    // Kept by id, without the number: reads never carry it (#2621).
                    vendor.taxRegistrations().stream()
                            .map(r -> new TaxRegistrationDto(r.registrationId(), r.scheme(), null, r.region()))
                            .toList(),
                    vendor.defaultPaymentTerms(),
                    vendor.defaultCurrency(),
                    vendor.version());

            VendorView unchanged = as("clerk.a", () -> service.updateVendor(vendor.vendorId(), same));

            assertThat(unchanged.version()).isEqualTo(vendor.version());
            assertThat(vendorFacts(vendor.vendorId())).hasSize(factsBefore);
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

    /**
     * #2621 (Security ruling on #2617, rulings 1-4): registration numbers are sealed at rest, masked on every
     * read, minimised on the fact, and kept by id on update. Every number here is obviously fake.
     */
    @Nested
    @DisplayName("tax registrations are sealed, masked and minimised (#2621)")
    class TaxRegistrations {

        private static final String SSN = "000-00-1234";
        private static final String SSN_BARE = "000001234";

        private VendorView createWith(TaxRegistrationDto... registrations) {
            return as(
                    "clerk.a",
                    () -> service.createVendor(new VendorCreateRequest(
                            null, "Sole Proprietor", "Sole", List.of(registrations), null, "NET30", "USD")));
        }

        private VendorUpdateRequest update(VendorView vendor, TaxRegistrationDto... registrations) {
            return new VendorUpdateRequest(
                    vendor.legalName(),
                    vendor.displayName(),
                    List.of(registrations),
                    vendor.defaultPaymentTerms(),
                    vendor.defaultCurrency(),
                    vendor.version());
        }

        private String storedText(UUID vendorId) {
            entityManager.flush();
            return (String) entityManager
                    .createNativeQuery("SELECT tax_registrations::text FROM supplier_vendor WHERE vendor_id = ?1")
                    .setParameter(1, vendorId)
                    .getSingleResult();
        }

        private JsonNode stored(UUID vendorId) {
            return JSON.readTree(storedText(vendorId));
        }

        @Test
        @DisplayName("AC 1: the queued fact is schemaVersion 2 with {scheme, region, last4} and no number")
        void factIsMinimised() {
            VendorView vendor = createWith(new TaxRegistrationDto(null, "SSN", SSN, null));

            SupplierOutboxEventEntity row = vendorFacts(vendor.vendorId()).getFirst();
            JsonNode envelope = envelope(row);
            assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(2);
            JsonNode registration =
                    envelope.path("payload").path("taxRegistrations").get(0);
            assertThat(registration.path("scheme").stringValue()).isEqualTo("SSN");
            assertThat(registration.path("region").isNull()).isTrue();
            assertThat(registration.path("last4").stringValue()).isEqualTo("1234");
            assertThat(registration.has("number")).as("number absent").isFalse();
            assertThat(row.getPayload().contains(SSN) || row.getPayload().contains(SSN_BARE))
                    .as("number absent from the outbox payload")
                    .isFalse();
        }

        @Test
        @DisplayName("AC 3: the stored element is {registrationId, scheme, region, last4, numberCiphertext},"
                + " no clear number, and the configured key opens it")
        void numberIsEncryptedAtRest() {
            VendorView vendor = createWith(new TaxRegistrationDto(null, "SSN", SSN, null));

            String text = storedText(vendor.vendorId());
            assertThat(text.contains(SSN) || text.contains(SSN_BARE))
                    .as("number absent from supplier_vendor.tax_registrations")
                    .isFalse();
            JsonNode element = stored(vendor.vendorId()).get(0);
            List<String> keys = new java.util.ArrayList<>();
            element.properties().forEach(property -> keys.add(property.getKey()));
            assertThat(keys)
                    .containsExactlyInAnyOrder("registrationId", "scheme", "region", "last4", "numberCiphertext");
            UUID registrationId = UUID.fromString(element.path("registrationId").stringValue());
            assertThat(registrationId)
                    .isEqualTo(vendor.taxRegistrations().getFirst().registrationId());
            assertThat(registrationId.version()).as("UUIDv7").isEqualTo(7);
            assertThat(taxIdCipher.open(
                            TENANT,
                            vendor.vendorId(),
                            registrationId,
                            element.path("numberCiphertext").stringValue()))
                    .as("the configured key opens the stored number")
                    .isEqualTo(SSN);
        }

        @Test
        @DisplayName("AC 8: create, get, list, update and status responses carry {registrationId, scheme, region,"
                + " last4} and never the number")
        void readsAreMasked() {
            VendorView created = createWith(
                    new TaxRegistrationDto(null, "SSN", SSN, null),
                    new TaxRegistrationDto(null, "BN", "FAKE-123", "ON"));
            VendorView got = service.getVendor(created.vendorId());
            VendorView listed = service.listVendors("Sole", null, 0, 50).items().getFirst();
            VendorView updated = as(
                    "clerk.a",
                    () -> service.updateVendor(
                            created.vendorId(),
                            new VendorUpdateRequest(
                                    created.legalName(),
                                    "Sole Renamed",
                                    created.taxRegistrations().stream()
                                            .map(r -> new TaxRegistrationDto(
                                                    r.registrationId(), r.scheme(), null, r.region()))
                                            .toList(),
                                    "NET30",
                                    "USD",
                                    created.version())));
            VendorView deactivated = as(
                    "clerk.a",
                    () -> service.deactivateVendor(
                            created.vendorId(),
                            new com.positivity.supplier.internal.vendor.service.model.VendorStatusChangeRequest(
                                    "No longer buying from them")));

            for (VendorView view : List.of(created, got, listed, updated, deactivated)) {
                assertThat(view.taxRegistrations())
                        .extracting(
                                TaxRegistrationView::scheme, TaxRegistrationView::region, TaxRegistrationView::last4)
                        .containsExactly(
                                org.assertj.core.groups.Tuple.tuple("SSN", null, "1234"),
                                org.assertj.core.groups.Tuple.tuple("BN", "ON", null));
                String json = JSON.writeValueAsString(view);
                assertThat(json.contains(SSN) || json.contains(SSN_BARE) || json.contains("FAKE-123"))
                        .as("number absent from the response")
                        .isFalse();
                assertThat(json).doesNotContain("\"number\"");
            }
        }

        @Test
        @DisplayName("AC 9: a registration kept by id without its number keeps its ciphertext and queues no fact")
        void keptRegistrationIsUnchanged() {
            VendorView vendor = createWith(new TaxRegistrationDto(null, "GST_HST", "000000000RT0001", "ON"));
            String before = storedText(vendor.vendorId());
            int factsBefore = vendorFacts(vendor.vendorId()).size();
            UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();

            VendorView after = as(
                    "clerk.a",
                    () -> service.updateVendor(
                            vendor.vendorId(),
                            update(vendor, new TaxRegistrationDto(registrationId, "GST_HST", null, "ON"))));

            assertThat(storedText(vendor.vendorId())).isEqualTo(before);
            assertThat(after.version()).isEqualTo(vendor.version());
            assertThat(vendorFacts(vendor.vendorId())).hasSize(factsBefore);
            assertThat(after.taxRegistrations().getFirst().last4()).isEqualTo("0001");
        }

        @Test
        @DisplayName("AC 9: a kept registration whose scheme changes without its number is 400 on"
                + " taxRegistrations[0].number, and nothing is written")
        void schemeChangeWithoutNumberIsRefused() {
            VendorView vendor = createWith(new TaxRegistrationDto(null, "EIN", SSN, null));
            String before = storedText(vendor.vendorId());
            UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();

            assertThatThrownBy(() -> as(
                            "clerk.a",
                            () -> service.updateVendor(
                                    vendor.vendorId(),
                                    update(vendor, new TaxRegistrationDto(registrationId, "SSN", null, null)))))
                    .isInstanceOfSatisfying(SupplierValidationException.class, refused -> {
                        assertThat(refused.getCode()).isEqualTo(SupplierValidationException.VALIDATION_ERROR);
                        assertThat(refused.getFieldErrors()).singleElement().satisfies(error -> {
                            assertThat(error.field()).isEqualTo("taxRegistrations[0].number");
                            assertThat(error.message()).isEqualTo("re-enter the number to change its scheme or region");
                        });
                    });
            assertThat(storedText(vendor.vendorId())).isEqualTo(before);
            assertThat(service.getVendor(vendor.vendorId()).version()).isEqualTo(vendor.version());
        }

        /**
         * CHK-011 / IC-004 (ADR-0072 Decision 4): a supplied number is always a change, even under the same id, scheme
         * and region, whether its last4 is the same or not. Nothing decides "unchanged" on ciphertext or last4.
         */
        @Test
        @DisplayName("CHK-011: a replacement number under an unchanged id is always a change: new ciphertext, last4"
                + " stored, fact published, with the same last4 and with a different one")
        void replacementValueIsAlwaysAChange() {
            VendorView vendor = createWith(new TaxRegistrationDto(null, "SSN", SSN, null));
            UUID id = vendor.taxRegistrations().getFirst().registrationId();
            String firstCiphertext =
                    stored(vendor.vendorId()).get(0).path("numberCiphertext").stringValue();
            int factsBefore = vendorFacts(vendor.vendorId()).size();

            // The same number again: same id, scheme, region and last4.
            VendorView same = as(
                    "clerk.a",
                    () -> service.updateVendor(
                            vendor.vendorId(), update(vendor, new TaxRegistrationDto(id, "SSN", SSN, null))));
            String secondCiphertext =
                    stored(vendor.vendorId()).get(0).path("numberCiphertext").stringValue();
            assertThat(secondCiphertext).as("re-sealed").isNotEqualTo(firstCiphertext);
            assertThat(same.taxRegistrations().getFirst().last4()).isEqualTo("1234");
            assertThat(same.version()).isGreaterThan(vendor.version());
            assertThat(vendorFacts(vendor.vendorId())).hasSize(factsBefore + 1);

            // A different number with the same last4, then one with a different last4.
            VendorView sameLast4 = as(
                    "clerk.a",
                    () -> service.updateVendor(
                            vendor.vendorId(), update(same, new TaxRegistrationDto(id, "SSN", "000-99-1234", null))));
            assertThat(sameLast4.version()).isGreaterThan(same.version());
            assertThat(sameLast4.taxRegistrations().getFirst().last4()).isEqualTo("1234");
            VendorView otherLast4 = as(
                    "clerk.a",
                    () -> service.updateVendor(
                            vendor.vendorId(),
                            update(sameLast4, new TaxRegistrationDto(id, "SSN", "000-00-4321", null))));
            assertThat(otherLast4.version()).isGreaterThan(sameLast4.version());
            assertThat(otherLast4.taxRegistrations().getFirst().last4()).isEqualTo("4321");
            assertThat(vendorFacts(vendor.vendorId())).hasSize(factsBefore + 3);
            assertThat(envelope(vendorFacts(vendor.vendorId()).getLast())
                            .path("payload")
                            .path("taxRegistrations")
                            .get(0)
                            .path("last4")
                            .stringValue())
                    .isEqualTo("4321");
            assertThat(taxIdCipher.open(
                            TENANT,
                            vendor.vendorId(),
                            id,
                            stored(vendor.vendorId())
                                    .get(0)
                                    .path("numberCiphertext")
                                    .stringValue()))
                    .isEqualTo("000-00-4321");
        }

        @Test
        @DisplayName("a re-entered number re-seals the registration under the same id; an omitted one is removed")
        void reEnteredNumberKeepsTheIdAndOmittedIsRemoved() {
            VendorView vendor = createWith(
                    new TaxRegistrationDto(null, "EIN", SSN, null),
                    new TaxRegistrationDto(null, "BN", "000000000RT0001", "ON"));
            UUID ein = vendor.taxRegistrations().getFirst().registrationId();

            VendorView after = as(
                    "clerk.a",
                    () -> service.updateVendor(
                            vendor.vendorId(),
                            update(vendor, new TaxRegistrationDto(ein, "SSN", "000-00-5678", null))));

            assertThat(after.taxRegistrations()).singleElement().satisfies(view -> {
                assertThat(view.registrationId()).isEqualTo(ein);
                assertThat(view.scheme()).isEqualTo("SSN");
                assertThat(view.last4()).isEqualTo("5678");
            });
            JsonNode element = stored(vendor.vendorId()).get(0);
            assertThat(taxIdCipher.open(
                            TENANT,
                            vendor.vendorId(),
                            ein,
                            element.path("numberCiphertext").stringValue()))
                    .isEqualTo("000-00-5678");
            assertThat(after.version()).isGreaterThan(vendor.version());
        }

        @Test
        @DisplayName("AC 10: an unknown registrationId and a new entry without a number are 400; the message"
                + " never carries a number")
        void updateRefusals() {
            VendorView vendor = createWith(new TaxRegistrationDto(null, "EIN", SSN, null));
            UUID stranger = UUID.fromString("01980000-0000-7000-8000-00000000dead");

            assertThatThrownBy(() -> as(
                            "clerk.a",
                            () -> service.updateVendor(
                                    vendor.vendorId(),
                                    update(vendor, new TaxRegistrationDto(stranger, "EIN", null, null)))))
                    .isInstanceOfSatisfying(
                            SupplierValidationException.class,
                            refused -> assertThat(refused.getFieldErrors())
                                    .extracting(ApiErrorField::of)
                                    .containsExactly("taxRegistrations[0].registrationId"));
            assertThatThrownBy(() -> as(
                            "clerk.a",
                            () -> service.updateVendor(
                                    vendor.vendorId(),
                                    update(
                                            vendor,
                                            new TaxRegistrationDto(
                                                    vendor.taxRegistrations()
                                                            .getFirst()
                                                            .registrationId(),
                                                    "EIN",
                                                    "000-00-9999",
                                                    null),
                                            new TaxRegistrationDto(null, "BN", null, "ON")))))
                    .isInstanceOfSatisfying(SupplierValidationException.class, refused -> {
                        assertThat(refused.getFieldErrors())
                                .extracting(ApiErrorField::of)
                                .containsExactly("taxRegistrations[1].number");
                        assertThat(refused.getMessage()).as("number absent").doesNotContain("000-00-9999");
                    });
        }

        @Test
        @DisplayName("AC 10: a 65-character number is 400 and the message does not echo it")
        void overlongNumberIsRefused() {
            String overlong = "FAKE" + "0".repeat(61);
            assertThatThrownBy(() -> new TaxRegistrationDto(null, "EIN", overlong, null))
                    .isInstanceOfSatisfying(SupplierValidationException.class, refused -> {
                        assertThat(refused.getCode()).isEqualTo(SupplierValidationException.VALIDATION_ERROR);
                        assertThat(refused.getMessage()).as("number absent").doesNotContain("FAKE0");
                    });
            assertThat(new TaxRegistrationDto(null, "EIN", "  " + "0".repeat(64) + "  ", null).number())
                    .as("64 once trimmed is accepted")
                    .hasSize(64);
            assertThat(new TaxRegistrationDto(null, "EIN", SSN, null).toString())
                    .as("toString never prints the number")
                    .doesNotContain(SSN);
        }

        /**
         * AC 20 (ADR-0072 Decision 2; Security confirmation on louisburroughs/durion#571): every entry carrying a
         * number has its scheme and region shape-checked after trim and upper-casing; the value is never echoed.
         * [M] allowing a digit in region fails {@code US-123} here.
         */
        @Test
        @DisplayName("AC 20: EIN123, a 17-character scheme and region US-123 are 400 on the matching field;"
                + " gst_hst / qc are stored as GST_HST / QC")
        void schemeAndRegionShapes() {
            for (Object[] refused : new Object[][] {
                {"EIN123", null, "taxRegistrations[0].scheme"},
                {"ABCDEFGHIJKLMNOPQ", null, "taxRegistrations[0].scheme"},
                {"EIN", "US-123", "taxRegistrations[0].region"}
            }) {
                String scheme = (String) refused[0];
                String region = (String) refused[1];
                assertThatThrownBy(() -> createWith(new TaxRegistrationDto(null, scheme, SSN, region)))
                        .isInstanceOfSatisfying(SupplierValidationException.class, failure -> {
                            assertThat(failure.getCode()).isEqualTo(SupplierValidationException.VALIDATION_ERROR);
                            assertThat(failure.getFieldErrors())
                                    .extracting(ApiErrorField::of)
                                    .containsExactly((String) refused[2]);
                            String said = failure.getMessage() + failure.getFieldErrors();
                            assertThat(said.contains(scheme) || (region != null && said.contains(region)))
                                    .as("submitted value absent")
                                    .isFalse();
                        });
            }

            VendorView stored = createWith(new TaxRegistrationDto(null, " gst_hst ", "000000000RT0001", "qc"));
            assertThat(stored.taxRegistrations()).singleElement().satisfies(view -> {
                assertThat(view.scheme()).isEqualTo("GST_HST");
                assertThat(view.region()).isEqualTo("QC");
            });
            JsonNode element = stored(stored.vendorId()).get(0);
            assertThat(element.path("scheme").stringValue()).isEqualTo("GST_HST");
            assertThat(element.path("region").stringValue()).isEqualTo("QC");
            assertThat(createWith(new TaxRegistrationDto(null, "VAT/IVA", "FAKE00001234", "ca-qc"))
                            .taxRegistrations()
                            .getFirst()
                            .region())
                    .isEqualTo("CA-QC");
        }
    }

    /** Field name of an {@code ApiError.FieldError}, for {@code extracting}. */
    private static final class ApiErrorField {
        static String of(com.positivity.shared.error.ApiError.FieldError error) {
            return error.field();
        }
    }
}
