package com.positivity.vehiclereferencecarapi.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.vehiclereferencecarapi.internal.entity.CarApiMake;
import com.positivity.vehiclereferencecarapi.internal.entity.CarApiModel;
import com.positivity.vehiclereferencecarapi.internal.exception.CarApiException;
import com.positivity.vehiclereferencecarapi.internal.repository.CarApiMakeRepository;
import com.positivity.vehiclereferencecarapi.internal.repository.CarApiModelRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * First tests for {@link VehicleReferenceService} — this module was at 0%.
 *
 * <p>
 * The service is a 24-hour read-through cache over CarAPI: serve local rows when
 * they are fresh, otherwise refetch and replace them. That matters because
 * CarAPI is a rate-limited third party — refetching on every request is both
 * slow and a way to get throttled.
 *
 * <p>
 * Both methods gate on {@code isCacheFresh}, unnegated. That helper was formerly
 * named {@code isCacheExpired} while computing the opposite — correct here only
 * because both call sites happened to be inverted to match, and outright broken
 * in the pos-vehicle-reference-nhtsa copy (issue #1265). Keep the name and the
 * call sites agreeing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VehicleReferenceService — 24h read-through cache over CarAPI")
class VehicleReferenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID MAKE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Mock
    private CarApiMakeRepository makeRepository;

    @Mock
    private CarApiModelRepository modelRepository;

    private MockRestServiceServer server;
    private VehicleReferenceService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new VehicleReferenceService(
                Clock.fixed(NOW, ZoneOffset.UTC), makeRepository, modelRepository, builder.build());
        ReflectionTestUtils.setField(service, "carApiBaseUrl", "http://carapi");
        when(makeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(modelRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static CarApiMake make(LocalDateTime cachedAt) {
        CarApiMake make = new CarApiMake();
        make.setId(UUID.randomUUID());
        make.setMakeId(MAKE_ID);
        make.setMakeName("Toyota");
        make.setCacheTimestamp(cachedAt);
        return make;
    }

    private static LocalDateTime at(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("serves cached makes without calling CarAPI while the cache is fresh")
    void cacheIsServedWhileFresh() {
        // Cached an hour ago — comfortably inside the 24h window.
        when(makeRepository.findAll()).thenReturn(List.of(make(at(NOW.minusSeconds(3_600)))));

        List<CarApiMake> result = service.getMakes();

        assertThat(result).singleElement().extracting(CarApiMake::getMakeName).isEqualTo("Toyota");
        // No HTTP expectation was registered, so any outbound call would fail this test —
        // that is the assertion: a fresh cache must not hit the rate-limited third party.
        server.verify();
        verify(makeRepository, never()).deleteAll();
    }

    @Test
    @DisplayName("refetches and replaces makes once the cached rows fall outside the window")
    void staleCacheIsReplaced() {
        // Cached 25 hours ago — outside the 24h window.
        when(makeRepository.findAll()).thenReturn(List.of(make(at(NOW.minusSeconds(90_000)))));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        "http://carapi/makes"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"data":[{"id":"%s","name":"Honda"}]}""".formatted(MAKE_ID), org.springframework.http.MediaType.APPLICATION_JSON));

        List<CarApiMake> result = service.getMakes();

        assertThat(result).singleElement().satisfies(m -> {
            assertThat(m.getMakeName()).isEqualTo("Honda");
            assertThat(m.getMakeId()).isEqualTo(MAKE_ID);
            assertThat(m.getCacheTimestamp()).isEqualTo(at(NOW));
        });
        // Replace, not append: leaving the old rows would double every make on each refresh.
        verify(makeRepository).deleteAll();
        server.verify();
    }

    @Test
    @DisplayName("fetches from CarAPI when nothing is cached at all")
    void emptyCacheFetches() {
        when(makeRepository.findAll()).thenReturn(List.of());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        "http://carapi/makes"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"data":[{"id":"%s","name":"Toyota"}]}""".formatted(MAKE_ID), org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(service.getMakes()).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("wraps an unparseable CarAPI response in CarApiException rather than leaking it")
    void unparseableResponseIsWrapped() {
        when(makeRepository.findAll()).thenReturn(List.of());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        "http://carapi/makes"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"unexpected\":true}", org.springframework.http.MediaType.APPLICATION_JSON));

        // A third party changing its response shape must surface as this module's own
        // exception type, not as a raw NullPointerException from walking a missing node.
        assertThatThrownBy(() -> service.getMakes())
                .isInstanceOf(CarApiException.class)
                .hasMessageContaining("makes");
    }

    @Test
    @DisplayName("serves cached models per make while fresh, and refetches once stale")
    void modelCacheFollowsTheSameRule() {
        CarApiModel cached = new CarApiModel();
        cached.setId(UUID.randomUUID());
        cached.setModelId(MODEL_ID);
        cached.setModelName("Corolla");
        cached.setCacheTimestamp(at(NOW.minusSeconds(3_600)));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(cached));

        assertThat(service.getModelsByMakeId(MAKE_ID))
                .singleElement()
                .extracting(CarApiModel::getModelName)
                .isEqualTo("Corolla");
        server.verify();

        // Now stale: 25 hours old.
        cached.setCacheTimestamp(at(NOW.minusSeconds(90_000)));
        when(makeRepository.getReferenceById(MAKE_ID)).thenReturn(make(at(NOW)));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        "http://carapi/models?make_id=" + MAKE_ID))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"data":[{"id":"%s","name":"Camry"}]}""".formatted(MODEL_ID), org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(service.getModelsByMakeId(MAKE_ID))
                .singleElement()
                .extracting(CarApiModel::getModelName)
                .isEqualTo("Camry");
        // Only this make's rows are cleared — a global deleteAll would wipe every other
        // make's cached models as a side effect of refreshing one.
        verify(modelRepository).deleteAll(List.of(cached));
    }

    @Test
    @DisplayName("treats a row with no cache timestamp as needing a refetch")
    void nullTimestampRefetches() {
        when(makeRepository.findAll()).thenReturn(List.of(make(null)));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        "http://carapi/makes"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"data":[{"id":"%s","name":"Toyota"}]}""".formatted(MAKE_ID), org.springframework.http.MediaType.APPLICATION_JSON));

        // An unstamped row cannot be shown to be fresh, so it must not be trusted.
        assertThat(service.getMakes()).hasSize(1);
        server.verify();
    }
}
