package com.positivity.referencemock.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.referencemock.internal.dto.FeedChunkDto;
import com.positivity.referencemock.internal.dto.FeedLineDto;
import com.positivity.referencemock.internal.dto.FeedManifestDto;
import com.positivity.referencemock.internal.dto.ProviderLaborTimeDto;
import com.positivity.referencemock.internal.dto.ProviderOperationDto;
import com.positivity.referencemock.internal.dto.VehicleQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LaborGuideFixtureServiceTest {

    private static final VehicleQuery CIVIC_2019 = new VehicleQuery("2019", "Honda", "Civic", null, null);
    private static final VehicleQuery CIVIC_2019_EX = new VehicleQuery("2019", "Honda", "Civic", "EX", "L15B7");
    private static final VehicleQuery ANY_VEHICLE = new VehicleQuery(null, null, null, null, null);

    private static LaborGuideFixtureService service;

    @BeforeAll
    static void loadService() {
        service = newService();
    }

    private static LaborGuideFixtureService newService() {
        return new LaborGuideFixtureService(JsonMapper.builder().build());
    }

    @Nested
    class ManifestDeterminism {

        @Test
        void manifestIsIdenticalAcrossCalls() {
            FeedManifestDto first = service.manifest();
            FeedManifestDto second = service.manifest();
            assertThat(second).isEqualTo(first);
        }

        @Test
        void manifestIsIdenticalAcrossFreshFixtureLoads() {
            FeedManifestDto first = newService().manifest();
            FeedManifestDto second = newService().manifest();
            assertThat(second).isEqualTo(first);
        }

        @Test
        void manifestCarriesFixtureRevisionAndFixedId() {
            FeedManifestDto manifest = service.manifest();
            assertThat(manifest.sourceRevision()).isEqualTo("2026-09-01");
            assertThat(manifest.importManifestId()).isEqualTo(UUID.fromString("7f1e6b2a-4c5d-4e8f-9a0b-1c2d3e4f5a6b"));
            assertThat(manifest.expectedLineCount()).isPositive();
            assertThat(manifest.expectedChunkCount())
                    .isEqualTo((manifest.expectedLineCount() + LaborGuideFixtureService.CHUNK_SIZE - 1)
                            / LaborGuideFixtureService.CHUNK_SIZE);
        }
    }

    @Nested
    class Operations {

        @Test
        void wildcardVehicleListsEveryFixtureOperation() {
            List<ProviderOperationDto> operations = service.findOperations(ANY_VEHICLE, null);
            assertThat(operations)
                    .extracting(ProviderOperationDto::providerOperationCode)
                    .contains("MG-BRAKE-PAD-FRONT", "MG-DIAG-SCAN", "MG-FOG-LAMP-ALIGN");
        }

        @Test
        void vehicleFieldsFilterToApplicableOperations() {
            List<String> civicCodes = service.findOperations(CIVIC_2019, null).stream()
                    .map(ProviderOperationDto::providerOperationCode)
                    .toList();
            // Civic 2019 is an L4; the V8 spark-plug op only has rows for V8 trucks.
            assertThat(civicCodes).contains("MG-SPARK-PLUG-L4", "MG-BRAKE-PAD-FRONT", "MG-DIAG-SCAN");
            assertThat(civicCodes).doesNotContain("MG-SPARK-PLUG-V8");
        }

        @Test
        void unknownVehicleStillGetsWildcardOperations() {
            List<String> codes =
                    service.findOperations(new VehicleQuery("1988", "Yugo", "GV", null, null), null).stream()
                            .map(ProviderOperationDto::providerOperationCode)
                            .toList();
            assertThat(codes).contains("MG-DIAG-SCAN", "MG-MULTI-POINT-INSPECTION");
            assertThat(codes).doesNotContain("MG-BRAKE-PAD-FRONT");
        }

        @Test
        void searchIsCaseInsensitiveSubstringOnName() {
            List<ProviderOperationDto> operations = service.findOperations(CIVIC_2019, "brake pad");
            assertThat(operations)
                    .extracting(ProviderOperationDto::providerOperationCode)
                    .containsExactlyInAnyOrder("MG-BRAKE-PAD-FRONT", "MG-BRAKE-PAD-REAR");
        }
    }

    @Nested
    class LaborTimes {

        @Test
        void mostSpecificRowBeatsModelLevelRow() {
            ProviderLaborTimeDto modelLevel =
                    service.findLaborTime("MG-BRAKE-PAD-FRONT", CIVIC_2019).orElseThrow();
            ProviderLaborTimeDto trimLevel =
                    service.findLaborTime("MG-BRAKE-PAD-FRONT", CIVIC_2019_EX).orElseThrow();
            // The fixture carries a submodel/engine-specific 1.6 row that must win over the
            // model-level row once the request supplies submodel and engineCode.
            assertThat(trimLevel.hours()).isEqualByComparingTo(new BigDecimal("1.6"));
            assertThat(trimLevel.hours()).isNotEqualByComparingTo(modelLevel.hours());
        }

        @Test
        void wildcardDiagnosticRowMatchesAnyVehicle() {
            ProviderLaborTimeDto forCivic =
                    service.findLaborTime("MG-DIAG-SCAN", CIVIC_2019_EX).orElseThrow();
            ProviderLaborTimeDto forNothing =
                    service.findLaborTime("MG-DIAG-SCAN", ANY_VEHICLE).orElseThrow();
            assertThat(forCivic.hours()).isEqualByComparingTo(new BigDecimal("1.0"));
            assertThat(forNothing.hours()).isEqualByComparingTo(new BigDecimal("1.0"));
        }

        @Test
        void vehicleSpecificRowNeverAnswersAVehiclelessRequest() {
            Optional<ProviderLaborTimeDto> result = service.findLaborTime("MG-BRAKE-PAD-FRONT", ANY_VEHICLE);
            assertThat(result).isEmpty();
        }

        @Test
        void equalSpecificityTieResolvesToRetailBeforeWarranty() {
            // Honda Civic 2019 has RETAIL_FLAT_RATE and OEM_WARRANTY rows at the same
            // model-level specificity for MG-BRAKE-PAD-FRONT.
            ProviderLaborTimeDto winner =
                    service.findLaborTime("MG-BRAKE-PAD-FRONT", CIVIC_2019).orElseThrow();
            assertThat(winner.timeType()).isEqualTo("RETAIL_FLAT_RATE");
        }

        @Test
        void includedOperationsCarryDurionCodes() {
            ProviderLaborTimeDto rotor = service.findLaborTime("MG-BRAKE-ROTOR-FRONT-PAIR", CIVIC_2019)
                    .orElseThrow();
            assertThat(rotor.includedOperations()).containsExactly("BRAKE-PAD-FRONT");
            assertThat(rotor.overlapGroup()).isEqualTo("WHEEL-OFF");
        }

        @Test
        void overlapGroupSharedByFrontAndRearPads() {
            ProviderLaborTimeDto front =
                    service.findLaborTime("MG-BRAKE-PAD-FRONT", CIVIC_2019).orElseThrow();
            ProviderLaborTimeDto rear =
                    service.findLaborTime("MG-BRAKE-PAD-REAR", CIVIC_2019).orElseThrow();
            assertThat(front.overlapGroup()).isEqualTo("WHEEL-OFF").isEqualTo(rear.overlapGroup());
        }

        @Test
        void unknownOperationYieldsEmpty() {
            assertThat(service.findLaborTime("MG-DOES-NOT-EXIST", CIVIC_2019)).isEmpty();
        }
    }

    @Nested
    class FeedChunks {

        @Test
        void chunksCoverExactlyTheManifestLineCountAndChecksum() {
            FeedManifestDto manifest = service.manifest();
            List<FeedLineDto> allLines = new ArrayList<>();
            for (int seq = 1; seq <= manifest.expectedChunkCount(); seq++) {
                FeedChunkDto chunk =
                        service.chunk(seq, manifest.importManifestId()).orElseThrow();
                assertThat(chunk.chunkSequence()).isEqualTo(seq);
                assertThat(chunk.importManifestId()).isEqualTo(manifest.importManifestId());
                assertThat(chunk.lines()).hasSizeLessThanOrEqualTo(LaborGuideFixtureService.CHUNK_SIZE);
                if (seq < manifest.expectedChunkCount()) {
                    assertThat(chunk.lines()).hasSize(LaborGuideFixtureService.CHUNK_SIZE);
                }
                allLines.addAll(chunk.lines());
            }
            assertThat(allLines).hasSize(manifest.expectedLineCount());
            // The checksum a consumer recomputes over all delivered lines must equal the
            // manifest's contentChecksum — the completion check of the chunked import.
            assertThat(LaborGuideFixtureService.checksum(allLines)).isEqualTo(manifest.contentChecksum());
        }

        @Test
        void unknownSequenceYieldsEmpty() {
            FeedManifestDto manifest = service.manifest();
            assertThat(service.chunk(0, manifest.importManifestId())).isEmpty();
            assertThat(service.chunk(manifest.expectedChunkCount() + 1, manifest.importManifestId()))
                    .isEmpty();
        }

        @Test
        void mismatchedManifestIdYieldsEmpty() {
            assertThat(service.chunk(1, UUID.fromString("00000000-0000-0000-0000-000000000000")))
                    .isEmpty();
        }
    }
}
