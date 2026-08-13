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
import com.positivity.nhtsa.internal.entity.VehicleType;
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
 * <li><b>All six methods gate on {@code isCacheFresh}, unnegated</b> — the helper
 * answers "are these rows still inside the 24-hour window", so every call site
 * reads {@code if (!cached.isEmpty() && isCacheFresh(...)) return cached;}. It
 * used to be named {@code isCacheExpired} while computing the opposite, and three
 * of the six call sites negated it on top of that: those three called vPIC on
 * every request while the cache was warm and then froze once it went stale
 * (issue #1265). Keep the name and the call sites agreeing — a helper whose name
 * inverts its body is what made half this service silently uncached.</li>
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
        // Mfr_CommonName, not Mfr_Name: the parser reads the former, so a stub using the
        // latter would assert a save happened while silently persisting an empty name.
        server.expect(requestTo(BASE + "/getallmanufacturers?format=json"))
                .andRespond(withSuccess("""
                        {"Results":[{"Mfr_ID":988,"Mfr_CommonName":"HONDA"}]}""", MediaType.APPLICATION_JSON));

        service.getManufacturers();

        org.mockito.ArgumentCaptor<Manufacturer> captor = org.mockito.ArgumentCaptor.forClass(Manufacturer.class);
        verify(manufacturerRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("HONDA");
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
        // Confirms the expectation above was actually consumed — without this the test would
        // still pass if the service returned early on some other cache path.
        server.verify();
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
        // The throw must come from parsing the response, not from failing to make the call.
        server.verify();
    }

    @Test
    @DisplayName("serves cached vehicle variables without calling vPIC while fresh")
    void vehicleVariableCacheIsServedWhileFresh() {
        VehicleVariable cachedVar = new VehicleVariable();
        cachedVar.setId(UUID.randomUUID());
        cachedVar.setName("Body Class");
        cachedVar.setCacheTimestamp(fresh());
        when(vehicleVariableRepository.findAll()).thenReturn(List.of(cachedVar));

        assertThat(service.getVehicleVariables())
                .singleElement()
                .extracting(VehicleVariable::getName)
                .isEqualTo("Body Class");

        // Regression guard for issue #1265: this call site used to negate a helper that was
        // itself inverted, so an hour-old cache still went out to vPIC — and rewrote every
        // row, making a read a database write too. No HTTP expectation is registered, so any
        // outbound call fails this test.
        server.verify();
        verify(vehicleVariableRepository, never()).deleteAll();
    }

    @Test
    @DisplayName("refetches vehicle variables once the cache falls outside the 24h window")
    void staleVehicleVariablesRefetched() {
        VehicleVariable cachedVar = new VehicleVariable();
        cachedVar.setId(UUID.randomUUID());
        cachedVar.setName("Body Class");
        cachedVar.setCacheTimestamp(stale());
        when(vehicleVariableRepository.findAll()).thenReturn(List.of(cachedVar));
        server.expect(requestTo(BASE + "/GetVehicleVariableList?format=json"))
                .andRespond(withSuccess("""
                        {"Results":[{"ID":5,"Name":"Body Class","Description":"Sedan, SUV, ..."}]}""", MediaType.APPLICATION_JSON));

        service.getVehicleVariables();

        // The other half of #1265: with the double inversion this branch was unreachable once
        // the cache aged past 24h, so the rows froze at whatever vPIC last returned.
        org.mockito.ArgumentCaptor<VehicleVariable> captor = org.mockito.ArgumentCaptor.forClass(VehicleVariable.class);
        verify(vehicleVariableRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Body Class");
        assertThat(captor.getValue().getCacheTimestamp()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        server.verify();
    }

    @Test
    @DisplayName("serves cached vehicle types per make without calling vPIC while fresh")
    void vehicleTypeCacheIsServedWhileFresh() {
        VehicleType cachedType = new VehicleType();
        cachedType.setId(UUID.randomUUID());
        cachedType.setVehicleTypeName("Passenger Car");
        cachedType.setCacheTimestamp(fresh());
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(fresh())));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(cachedType));

        assertThat(service.getVehicleTypesForMake(MAKE_ID))
                .singleElement()
                .extracting(VehicleType::getVehicleTypeName)
                .isEqualTo("Passenger Car");

        // Third of the three call sites corrected by #1265.
        server.verify();
    }

    @Test
    @DisplayName("treats a row with no cache timestamp as needing a refetch")
    void nullTimestampRefetches() {
        when(manufacturerRepository.findAll()).thenReturn(List.of(manufacturer(null)));
        server.expect(requestTo(BASE + "/getallmanufacturers?format=json"))
                .andRespond(withSuccess("""
                        {"Results":[{"Mfr_ID":988,"Mfr_CommonName":"HONDA"}]}""", MediaType.APPLICATION_JSON));

        // An unstamped row cannot be shown to be fresh, so it must not be trusted — the
        // freshness check has to fail closed, not open.
        service.getManufacturers();

        verify(manufacturerRepository).save(any());
        server.verify();
    }
}
