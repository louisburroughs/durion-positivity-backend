package com.positivity.bulkingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bulk-ingest response types must survive a JSON round trip.
 *
 * <h2>What this defends</h2>
 *
 * Every caller of a {@code /bulk-ingest} endpoint reads its reply back into {@link
 * BulkIngestResponse}. Both it and {@link BulkIngestResult} carried {@code @Data @Builder} and
 * nothing else: Lombok's {@code @Builder} generates an all-args constructor, which suppresses the
 * implicit no-arg one, so the class reached Jackson with no no-arg constructor and no
 * {@code @JsonCreator} — nothing to construct it with. Deserialization failed with
 * {@code HttpMessageConversionException: Type definition error}, and because pos-bulk-loader reads
 * the reply inside its chunk writer, the exception rolled back the chunk. Every row of every pack
 * failed while the HTTP call itself had already succeeded and the rows had already been written
 * downstream — the alpha seed load reported success=0 across all 26 packs for this reason alone.
 *
 * <p>{@link BulkIngestRequest} was unaffected and still is: it is {@code @Data} without
 * {@code @Builder}, so it kept its implicit no-arg constructor. That asymmetry is what made the
 * failure look like a downstream rejection rather than a serialization defect — requests went out
 * fine and only replies could not be read.
 *
 * <p>Serialization alone would not have caught this; the round trip is the point.
 */
@DisplayName("bulk-ingest response JSON round trip")
class BulkIngestResponseJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void readsBackAResponseItJustWrote() {
        UUID entityId = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
        BulkIngestResponse original = BulkIngestResponse.builder()
                .totalSubmitted(2)
                .successCount(1)
                .failureCount(1)
                .results(List.of(
                        BulkIngestResult.builder()
                                .rowIndex(0)
                                .entityId(entityId)
                                .success(true)
                                .build(),
                        BulkIngestResult.builder()
                                .rowIndex(1)
                                .success(false)
                                .errorCode("VALIDATION_FAILED")
                                .errorMessage("matchValue is required")
                                .correlationId("01a09700-d8c1-71f6-8a08-aff0b9c80d60")
                                .build()))
                .build();

        BulkIngestResponse readBack = mapper.readValue(mapper.writeValueAsString(original), BulkIngestResponse.class);

        assertThat(readBack)
                .as("a caller must be able to read the reply it was sent")
                .isEqualTo(original);
    }

    @Test
    void readsAResultOnItsOwn() {
        BulkIngestResult original = BulkIngestResult.builder()
                .rowIndex(7)
                .success(false)
                .errorCode("NOT_FOUND")
                .errorMessage("no catalog product carries this class")
                .build();

        assertThat(mapper.readValue(mapper.writeValueAsString(original), BulkIngestResult.class))
                .isEqualTo(original);
    }
}
