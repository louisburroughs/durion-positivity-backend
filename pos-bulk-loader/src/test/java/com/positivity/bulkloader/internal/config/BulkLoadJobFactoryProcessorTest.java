package com.positivity.bulkloader.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.positivity.bulkloader.internal.domain.DomainLoaderStrategy;
import com.positivity.bulkloader.internal.domain.NumberedRecord;
import com.positivity.bulkloader.internal.domain.ResolutionContext;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.service.BulkIngestResultRecorder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.Data;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * The processor's contract: number every row, resolve before validating, and leave a reviewable
 * record behind for anything it drops.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class BulkLoadJobFactoryProcessorTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Mock
    BulkIngestResultRecorder resultRecorder;

    @Data
    static class Row {
        private String name;
        private String id;
    }

    /** Resolves a name to an id, the way a real strategy turns a business key into a UUID. */
    private static class NameToIdStrategy implements DomainLoaderStrategy<Row> {
        @Override
        public DomainType getDomainType() {
            return DomainType.LOCATION;
        }

        @Override
        public Row mapRow(@NonNull Map<String, String> row) {
            Row mapped = new Row();
            mapped.setName(row.get("name"));
            return mapped;
        }

        @Override
        public List<String> validate(@NonNull Row item) {
            return item.getId() == null ? List.of("id is required") : List.of();
        }

        @Override
        @NonNull
        public Row resolve(@NonNull Row item, @NonNull ResolutionContext context) {
            if ("known".equals(item.getName())) {
                item.setId("resolved-id");
            }
            return item;
        }
    }

    private static final ResolutionContext NOOP_CONTEXT = new ResolutionContext() {
        @Override
        @NonNull
        public UUID jobLocationId() {
            return UUID.fromString("00000000-0000-0000-0000-0000000000c2");
        }

        @Override
        @NonNull
        public <R> Optional<R> get(@NonNull String serviceId, @NonNull String uri, @NonNull Class<R> responseType) {
            return Optional.empty();
        }

        @Override
        @NonNull
        public <R> Optional<R> memoize(@NonNull String cacheKey, @NonNull Supplier<Optional<R>> loader) {
            return loader.get();
        }
    };

    private BulkLoadJobFactory factory() {
        return new BulkLoadJobFactory(null, null, null, null, null, resultRecorder, null);
    }

    private static Row row(String name) {
        Row row = new Row();
        row.setName(name);
        return row;
    }

    @Test
    void processor_resolvesBeforeValidating() throws Exception {
        // Validating first would reject every row of a name-keyed file, because the id it checks
        // for does not exist until resolution supplies it.
        ItemProcessor<Row, NumberedRecord<Row>> processor =
                factory().processor(new NameToIdStrategy(), JOB_ID, NOOP_CONTEXT);

        NumberedRecord<Row> result = processor.process(row("known"));

        assertThat(result).isNotNull();
        assertThat(result.record().getId()).isEqualTo("resolved-id");
        verifyNoInteractions(resultRecorder);
    }

    @Test
    void processor_numbersRowsInFileOrder() throws Exception {
        ItemProcessor<Row, NumberedRecord<Row>> processor =
                factory().processor(new NameToIdStrategy(), JOB_ID, NOOP_CONTEXT);

        assertThat(processor.process(row("known")).rowNumber()).isZero();
        assertThat(processor.process(row("known")).rowNumber()).isEqualTo(1L);
        assertThat(processor.process(row("known")).rowNumber()).isEqualTo(2L);
    }

    @Test
    void processor_keepsNumberingThroughASkip() throws Exception {
        // The row a skip consumed still used up its line, so the next row is 2, not 1. This is the
        // number the audit trail reports, and the one an operator looks for in their file.
        ItemProcessor<Row, NumberedRecord<Row>> processor =
                factory().processor(new NameToIdStrategy(), JOB_ID, NOOP_CONTEXT);

        assertThat(processor.process(row("known")).rowNumber()).isZero();
        assertThat(processor.process(row("unknown"))).isNull();
        assertThat(processor.process(row("known")).rowNumber()).isEqualTo(2L);
    }

    @Test
    void processor_recordsARejectedRow_soItIsReviewable() throws Exception {
        ItemProcessor<Row, NumberedRecord<Row>> processor =
                factory().processor(new NameToIdStrategy(), JOB_ID, NOOP_CONTEXT);

        assertThat(processor.process(row("unknown"))).isNull();

        verify(resultRecorder)
                .recordRejected(
                        eq(JOB_ID),
                        eq(DomainType.LOCATION),
                        eq(0L),
                        any(),
                        eq("BULK_LOAD_VALIDATION_FAILED"),
                        anyString());
    }

    @Test
    void processor_withoutAJobId_stillSkipsButRecordsNothing() throws Exception {
        // An audit row keyed to no job has nowhere to belong, so the skip is logged only.
        ItemProcessor<Row, NumberedRecord<Row>> processor =
                factory().processor(new NameToIdStrategy(), null, NOOP_CONTEXT);

        assertThat(processor.process(row("unknown"))).isNull();

        verifyNoInteractions(resultRecorder);
    }

    @Test
    void processor_withoutAResolutionContext_validatesTheRowAsRead() throws Exception {
        // A job with no location cannot resolve anything; a file that already carries its ids must
        // still load rather than failing wholesale.
        ItemProcessor<Row, NumberedRecord<Row>> processor = factory().processor(new NameToIdStrategy(), JOB_ID, null);

        Row alreadyResolved = row("known");
        alreadyResolved.setId("supplied-id");

        assertThat(processor.process(alreadyResolved).record().getId()).isEqualTo("supplied-id");
        assertThat(processor.process(row("known"))).isNull();
        verify(resultRecorder).recordRejected(any(), any(), anyLong(), any(), anyString(), anyString());
    }
}
