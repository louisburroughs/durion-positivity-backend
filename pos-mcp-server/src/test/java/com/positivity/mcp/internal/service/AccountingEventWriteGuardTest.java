package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #2374: the guard on pos-accounting's event retry and reprocess — HIGH risk, the status read as the
 * caller before anything runs, one event per call, no blind retry, and a preview that says what will
 * post and that there is no undo.
 */
class AccountingEventWriteGuardTest {

    private static final String EVENT_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b";
    private static final String AUTH = "Bearer caller-token";
    private static final String READ_ARGS = "{\"pathParams\":{\"eventId\":\"" + EVENT_ID + "\"}}";
    private static final Map<String, Object> RETRY_ARGS = Map.of("pathParams", Map.of("eventId", EVENT_ID));

    private final WritePlanExecutor executor = mock(WritePlanExecutor.class);
    private final AccountingEventWriteGuard guard = new AccountingEventWriteGuard(executor, new ObjectMapper());

    private void eventReads(String json) {
        when(executor.execute(AccountingEventWriteGuard.EVENT_READ_TOOL, READ_ARGS, AUTH))
                .thenReturn(json);
    }

    private static String event(String status) {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"INVOICE_FINALIZED\",\"sourceSystem\":\"POS\","
                + "\"status\":\"" + status + "\",\"journalEntryId\":\"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e\","
                + "\"payload\":{\"invoiceId\":\"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d\",\"totalAmount\":150.00}}";
    }

    @Test
    @DisplayName("guards retry and reprocess only; submit and the event reads are not guarded writes")
    void guardsExactlyRetryAndReprocess() {
        assertThat(AccountingEventWriteGuard.guards("accounting_retryaccountingevent"))
                .isTrue();
        assertThat(AccountingEventWriteGuard.guards("accounting_reprocesssuspendedevent"))
                .isTrue();
        assertThat(AccountingEventWriteGuard.guards("accounting_submitaccountingevent"))
                .isFalse();
        assertThat(AccountingEventWriteGuard.guards("accounting_getaccountingevent"))
                .isFalse();
        assertThat(AccountingEventWriteGuard.guards(null)).isFalse();
    }

    @Test
    @DisplayName("a guarded tool is HIGH risk whatever the classifier said; other tools keep their risk")
    void riskFloorIsHighForGuardedTools() {
        assertThat(AccountingEventWriteGuard.riskFloor(AccountingEventWriteGuard.RETRY_TOOL, NltiRiskLevel.LOW))
                .isEqualTo(NltiRiskLevel.HIGH);
        assertThat(AccountingEventWriteGuard.riskFloor(AccountingEventWriteGuard.REPROCESS_TOOL, NltiRiskLevel.MEDIUM))
                .isEqualTo(NltiRiskLevel.HIGH);
        assertThat(AccountingEventWriteGuard.riskFloor("orders_createorder", NltiRiskLevel.LOW))
                .isEqualTo(NltiRiskLevel.LOW);
    }

    @Test
    @DisplayName("inspect refuses to judge a tool it does not guard")
    void inspectRejectsUnguardedTool() {
        assertThatThrownBy(() -> guard.inspect("orders_createorder", Map.of(), AUTH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("status precondition")
    class StatusPrecondition {

        @Test
        @DisplayName("retry of a FAILED event is permitted, read as the caller with the exact event id")
        void retryOfFailedEventPermitted() {
            eventReads(event("FAILED"));

            AccountingEventWriteGuard.Inspection inspection =
                    guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);

            assertThat(inspection.permitted()).isTrue();
            assertThat(inspection.eventId()).isEqualTo(EVENT_ID);
            assertThat(inspection.eventStatus()).isEqualTo("FAILED");
            verify(executor).execute(AccountingEventWriteGuard.EVENT_READ_TOOL, READ_ARGS, AUTH);
        }

        @Test
        @DisplayName("reprocess of a SUSPENDED event is permitted")
        void reprocessOfSuspendedEventPermitted() {
            eventReads(event("SUSPENDED"));

            AccountingEventWriteGuard.Inspection inspection =
                    guard.inspect(AccountingEventWriteGuard.REPROCESS_TOOL, RETRY_ARGS, AUTH);

            assertThat(inspection.permitted()).isTrue();
            assertThat(inspection.eventStatus()).isEqualTo("SUSPENDED");
        }

        @Test
        @DisplayName("a PROCESSED event is never posted again: refused, pointing at a reversing entry")
        void processedEventRefused() {
            eventReads(event("PROCESSED"));

            AccountingEventWriteGuard.Inspection inspection =
                    guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);

            assertThat(inspection.permitted()).isFalse();
            assertThat(inspection.eventStatus()).isEqualTo("PROCESSED");
            assertThat(inspection.message())
                    .contains(EVENT_ID, "already posted", "nothing was run", "reversing entry")
                    .contains("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e");
        }

        @Test
        @DisplayName("RECEIVED or PROCESSING may be an earlier attempt still running: refused, poll first")
        void inFlightEventRefused() {
            for (String status : new String[] {"RECEIVED", "PROCESSING"}) {
                eventReads(event(status));

                AccountingEventWriteGuard.Inspection inspection =
                        guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);

                assertThat(inspection.permitted()).as(status).isFalse();
                assertThat(inspection.message())
                        .as(status)
                        .contains("earlier attempt may still be running", "reprocessing history", "Do not retry");
            }
        }

        @Test
        @DisplayName("retry of a SUSPENDED event is refused and pointed at reprocess, and the reverse")
        void wrongOperationForStatusRefused() {
            eventReads(event("SUSPENDED"));
            AccountingEventWriteGuard.Inspection retry =
                    guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);
            assertThat(retry.permitted()).isFalse();
            assertThat(retry.message()).contains("only a FAILED event can be retried", "reprocessSuspendedEvent");

            eventReads(event("FAILED"));
            AccountingEventWriteGuard.Inspection reprocess =
                    guard.inspect(AccountingEventWriteGuard.REPROCESS_TOOL, RETRY_ARGS, AUTH);
            assertThat(reprocess.permitted()).isFalse();
            assertThat(reprocess.message())
                    .contains("only a SUSPENDED event can be reprocessed", "retryAccountingEvent");
        }

        @Test
        @DisplayName("a status in another case is read the same way")
        void statusIsCaseInsensitive() {
            eventReads(event("failed"));

            assertThat(guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                            .permitted())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("fail-closed")
    class FailClosed {

        @Test
        @DisplayName("no eventId, a non-UUID eventId, or one outside pathParams is refused without a read")
        void missingOrMalformedEventIdRefused() {
            for (Map<String, Object> args : java.util.List.<Map<String, Object>>of(
                    Map.of(),
                    Map.of("pathParams", Map.of()),
                    Map.of("pathParams", Map.of("eventId", "not-a-uuid")),
                    Map.of("pathParams", Map.of("eventId", " ")),
                    Map.of("eventId", EVENT_ID),
                    Map.of("pathParams", Map.of("eventId", java.util.List.of(EVENT_ID, EVENT_ID))))) {
                AccountingEventWriteGuard.Inspection inspection =
                        guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, args, AUTH);

                assertThat(inspection.permitted()).as("%s", args).isFalse();
                assertThat(inspection.message()).as("%s", args).contains("exactly one accounting event id");
            }
            assertThat(guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, null, AUTH)
                            .permitted())
                    .isFalse();
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("an event that cannot be read (missing, not permitted, unreachable) is refused")
        void unreadableEventRefused() {
            when(executor.execute(eq(AccountingEventWriteGuard.EVENT_READ_TOOL), anyString(), any()))
                    .thenThrow(new WritePlanExecutionException("Write-plan tool execution failed: Error: 403"));

            AccountingEventWriteGuard.Inspection inspection =
                    guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);

            assertThat(inspection.permitted()).isFalse();
            assertThat(inspection.eventStatus()).isNull();
            assertThat(inspection.message()).contains("Could not read accounting event " + EVENT_ID, "nothing was run");
        }

        @Test
        @DisplayName("an answer that is not JSON, or has no status, is refused")
        void answerWithoutStatusRefused() {
            eventReads("not json");
            assertThat(guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                            .permitted())
                    .isFalse();

            eventReads("{\"eventId\":\"" + EVENT_ID + "\"}");
            AccountingEventWriteGuard.Inspection inspection =
                    guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);
            assertThat(inspection.permitted()).isFalse();
            assertThat(inspection.message()).contains("without a status");
        }
    }

    @Nested
    @DisplayName("preview")
    class Preview {

        @Test
        @DisplayName("retry preview names the event, its type, source and amount, the posting, and no undo")
        void retryPreview() {
            eventReads(event("FAILED"));

            String preview = guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                    .message();

            assertThat(preview)
                    .contains(EVENT_ID, "INVOICE_FINALIZED", "POS", "totalAmount=150.00", "is FAILED")
                    .contains("a journal entry will post")
                    .contains("There is no undo", "reversing entry")
                    .contains("covers this one event only");
        }

        @Test
        @DisplayName("reprocess preview names the mapping version asked for, or the active one")
        void reprocessPreviewNamesMappingVersion() {
            eventReads(event("SUSPENDED"));
            Map<String, Object> pinned = Map.of(
                    "pathParams", Map.of("eventId", EVENT_ID),
                    "body",
                            Map.of(
                                    "triggeredByUserId",
                                    "jdoe",
                                    "mappingVersionToUse",
                                    "018f0a1b-0000-7000-8000-0000000000aa"));

            assertThat(guard.inspect(AccountingEventWriteGuard.REPROCESS_TOOL, pinned, AUTH)
                            .message())
                    .contains("mapping version 018f0a1b-0000-7000-8000-0000000000aa", "a journal entry will post");
            assertThat(guard.inspect(AccountingEventWriteGuard.REPROCESS_TOOL, RETRY_ARGS, AUTH)
                            .message())
                    .contains("the active mapping version");
        }

        @Test
        @DisplayName("a payload without an amount is summarised by its field names")
        void payloadWithoutAmountSummarised() {
            eventReads("{\"eventId\":\"" + EVENT_ID + "\",\"status\":\"FAILED\",\"payload\":{\"workorderId\":\"w-1\","
                    + "\"locationId\":\"l-1\"}}");

            assertThat(guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                            .message())
                    .contains("payload workorderId, locationId")
                    .contains("unknown from unknown");
        }
    }

    @Test
    @DisplayName("the chat-path note carries the status check, the preview content, one event per confirmation")
    void guardNoteCarriesTheRules() {
        assertThat(AccountingEventWriteGuard.guardNote(AccountingEventWriteGuard.RETRY_TOOL))
                .contains("HIGH risk", "accounting_getaccountingevent", "FAILED", "no undo", "reversing entry")
                .contains("One confirmation covers one eventId", "never call again blindly")
                .doesNotContain("mapping version");
        assertThat(AccountingEventWriteGuard.guardNote(AccountingEventWriteGuard.REPROCESS_TOOL))
                .contains("SUSPENDED", "mapping version");
        assertThat(AccountingEventWriteGuard.guardNote("orders_createorder")).isEmpty();
    }
}
