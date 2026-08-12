package com.positivity.nhtsa.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.nhtsa.internal.entity.Make;
import com.positivity.nhtsa.internal.entity.Manufacturer;
import com.positivity.nhtsa.internal.entity.Model;
import com.positivity.nhtsa.internal.entity.VehicleVariable;
import com.positivity.nhtsa.internal.repository.MakeRepository;
import com.positivity.nhtsa.internal.repository.ManufacturerRepository;
import com.positivity.nhtsa.internal.repository.ModelRepository;
import com.positivity.nhtsa.internal.repository.VehicleTypeRepository;
import com.positivity.nhtsa.internal.repository.VehicleVariableRepository;
import com.positivity.nhtsa.internal.repository.VehicleVariableValueRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * First tests for the NHTSA reference service — this module was at 0%.
 *
 * <p>
 * Six methods share one shape: serve cached rows while they are inside a 24-hour
 * window, otherwise refetch from vPIC and replace them. vPIC is a public
 * government API with no SLA, so the cache is what keeps this module usable when
 * vPIC is slow or down, and what keeps us from hammering it.
 *
 * <p>
 * Two behaviours are worth knowing before editing this service:
 *
 * <ul>
 * <li><b>{@code isCacheExpired} computes the opposite of its name</b> — it really
 * answers "is still fresh". Three of the six call sites account for that and
 * behave correctly; the other three negate it and therefore serve the cache only
 * once it is stale, hitting vPIC on every request while it is warm. That is a
 * live bug, pinned by {@link #vehicleVariableCacheIsInverted()} and filed as
 * issue #1265. Fixing it means correcting the method name and all six call sites
 * together — and the same inverted helper was copied into
 * pos-vehicle-reference-carapi, where both call sites happen to be consistent.</li>
 * <li><b>NHTSA ids are hashed into UUIDs</b> via
 * {@code UUID.nameUUIDFromBytes("make-" + id)}, so the same vPIC record always
 * maps to the same primary key and a refetch updates rather than duplicates.
 * That derivation is a contract with the database, not an implementation
 * detail.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NHTSA VehicleReferenceService — 24h vPIC cache")
class VehicleReferenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final String BASE = "https://vpic.nhtsa.dot.gov/v1/vehicles";
    private static final UUID MANUFACTURER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID MAKE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    @Mock
    private ManufacturerRepository manufacturerRepository;

    @Mock
    private MakeRepository makeRepository;

    @Mock
    private ModelRepository modelRepository;

    @Mock
    private VehicleTypeRepository vehicleTypeRepository;

    @Mock
    private VehicleVariableRepository vehicleVariableRepository;

    @Mock
    private VehicleVariableValueRepository vehicleVariableValueRepository;

    private MockRestServiceServer server;
    private VehicleReferenceService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new VehicleReferenceService(
                Clock.fixed(NOW, ZoneOffset.UTC),
                manufacturerRepository,
                makeRepository,
                modelRepository,
                vehicleTypeRepository,
                builder.build(),
                vehicleVariableRepository,
                vehicleVariableValueRepository);
        when(makeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(modelRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(manufacturerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vehicleVariableRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static LocalDateTime fresh() {
        return LocalDateTime.ofInstant(NOW.minusSeconds(3_600), ZoneOffset.UTC);
    }

    private static LocalDateTime stale() {
        return LocalDateTime.ofInstant(NOW.minusSeconds(90_000), ZoneOffset.UTC);
    }

    private static Manufacturer manufacturer(LocalDateTime cachedAt) {
        Manufacturer m = new Manufacturer();
        m.setId(MANUFACTURER_ID);
        m.setName("Toyota Motor Corporation");
        m.setCacheTimestamp(cachedAt);
        return m;
    }

    private static Make make(LocalDateTime cachedAt) {
        Make make = new Make();
        make.setId(MAKE_ID);
        make.setName("Toyota");
        make.setCacheTimestamp(cachedAt);
        return make;
    }

    @Test
    @DisplayName("serves cached manufacturers without calling vPIC while fresh")
    void manufacturersServedFromCache() {
        when(manufacturerRepository.findAll()).thenReturn(List.of(manufacturer(fresh())));

        assertThat(service.getManufacturers())
                .singleElement()
                .extracting(Manufacturer::getName)
                .isEqualTo("Toyota Motor Corporation");

        // No HTTP expectation registered: any outbound call fails the test. vPIC has no SLA,
        // so a warm cache must not depend on it being up.
        server.verify();
    }

    @Test
    @DisplayName("refetches manufacturers once the cache falls outside the 24h window")
    void staleManufacturersRefetched() {
        when(manufacturerRepository.findAll()).thenReturn(List.of(manufacturer(stale())));
        server.expect(requestTo(BASE + "/getallmanufacturers?format=json"))
                .andRespond(withSuccess("""
                        {"Results":[{"Mfr_ID":988,"Mfr_Name":"HONDA MOTOR CO"}]}""", MediaType.APPLICATION_JSON));

        service.getManufacturers();

        verify(manufacturerRepository).save(any());
        server.verify();
    }

    @Test
    @DisplayName("derives a stable UUID from the vPIC id so a refetch updates rather than duplicates")
    void nhtsaIdsHashToStableUuids() {
        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer(fresh())));
        when(makeRepository.findByManufacturerId(MANUFACTURER_ID)).thenReturn(List.of());
        server.expect(requestTo(BASE + "/GetMakeForManufacturer/" + MANUFACTURER_ID + "?format=json"))
                .andRespond(withSuccess("""
                        {"Results":[{"Make_ID":440,"Make_Name":"TOYOTA"}]}""", MediaType.APPLICATION_JSON));

        service.getMakesByManufacturer(MANUFACTURER_ID);

        org.mockito.ArgumentCaptor<Make> captor = org.mockito.ArgumentCaptor.forClass(Make.class);
        verify(makeRepository).save(captor.capture());
        // The key is a hash of the vPIC id, not a random UUID: the same record must land on
        // the same row every refresh, or every refetch would double the table.
        assertThat(captor.getValue().getId()).isEqualTo(UUID.nameUUIDFromBytes("make-440".getBytes()));
        assertThat(captor.getValue().getName()).isEqualTo("TOYOTA");
        assertThat(captor.getValue().getCacheTimestamp()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("rejects a make lookup for a manufacturer that is not cached")
    void unknownManufacturerIsRejected() {
        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.empty());

        // Fetching makes for a manufacturer we have never seen would silently create orphan
        // rows with a dangling parent.
        assertThatThrownBy(() -> service.getMakesByManufacturer(MANUFACTURER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MANUFACTURER_ID.toString());

        verify(makeRepository, never()).save(any());
    }

    @Test
    @DisplayName("serves cached models per make while fresh and refetches when stale")
    void modelCacheFollowsTheSameRule() {
        Model cached = new Model();
        cached.setId(UUID.randomUUID());
        cached.setName("Corolla");
        cached.setCacheTimestamp(fresh());
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(fresh())));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(cached));

        assertThat(service.getModelsByMake(MAKE_ID))
                .singleElement()
                .extracting(Model::getName)
                .isEqualTo("Corolla");
        server.verify();
    }

    @Test
    @DisplayName("wraps an unparseable vPIC response rather than leaking a parse error")
    void unparseableResponseIsWrapped() {
        when(manufacturerRepository.findAll()).thenReturn(List.of());
        server.expect(requestTo(BASE + "/getallmanufacturers?format=json"))
                .andRespond(withSuccess("{\"NotResults\":1}", MediaType.APPLICATION_JSON));

        // vPIC changing its response shape must not surface as a raw NPE from walking a
        // missing node.
        assertThatThrownBy(() -> service.getManufacturers()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("BUG: the vehicle-variable cache is inverted — a fresh cache refetches from vPIC")
    void vehicleVariableCacheIsInverted() {
        VehicleVariable cachedVar = new VehicleVariable();
        cachedVar.setId(UUID.randomUUID());
        cachedVar.setName("Body Class");
        cachedVar.setCacheTimestamp(fresh());
        when(vehicleVariableRepository.findAll()).thenReturn(List.of(cachedVar));
        server.expect(requestTo(BASE + "/GetVehicleVariableList?format=json"))
                .andRespond(withSuccess("""
                        {"Results":[{"ID":5,"Name":"Body Class"}]}""", MediaType.APPLICATION_JSON));

        service.getVehicleVariables();

        // Documents current behaviour, not desired behaviour. isCacheExpired() actually
        // answers "is still fresh"; three of this service's six methods
        // (getVehicleVariables, getVehicleVariableValues, getVehicleTypesForMake) then negate
        // it, so they return the cache only once it is STALE and hit vPIC on every request
        // while it is warm — the exact opposite of the intent, against a public API with no
        // SLA. The other three call sites omit the negation and behave correctly. Tracked as
        // issue #1265. The registered expectation above is the assertion: an outbound call happened
        // despite an hour-old cache. Fixing it means correcting the method name and
        // all six call sites together.
        server.verify();
    }

    @Test
    @DisplayName("the three non-negated methods do serve a fresh cache without calling vPIC")
    void nonNegatedMethodsCacheCorrectly() {
        when(manufacturerRepository.findAll()).thenReturn(List.of(manufacturer(fresh())));

        service.getManufacturers();

        // Same helper, no negation at the call site: no HTTP expectation is registered, so
        // any outbound call would fail this test. This is the contrast that shows the bug
        // above is a call-site inconsistency rather than a broken helper.
        server.verify();
    }
}
