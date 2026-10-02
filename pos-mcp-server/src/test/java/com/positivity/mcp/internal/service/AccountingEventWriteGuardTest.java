package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #2374: the guard on pos-accounting's event retry and reprocess — HIGH risk, the status read as the
 * caller before anything runs, one event per call, no blind retry, the caller's own credential only,
 * and a preview that says what will post, under which rules, and that there is no undo.
 */
class AccountingEventWriteGuardTest {

    private static final String EVENT_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b";
    private static final String AUTH = "Bearer caller-token";
    private static final String READ_ARGS = "{\"pathParams\":{\"eventId\":\"" + EVENT_ID + "\"}}";
    private static final Map<String, Object> RETRY_ARGS = Map.of("pathParams", Map.of("eventId", EVENT_ID));
    private static final String RULE_VERSION_ID = "018f0a1b-0000-7000-8000-0000000000aa";

    private final WritePlanExecutor executor = mock(WritePlanExecutor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AccountingEventWriteGuard guard = new AccountingEventWriteGuard(executor, objectMapper);

    private void eventReads(String json) {
        when(executor.execute(AccountingEventWriteGuard.EVENT_READ_TOOL, READ_ARGS, AUTH))
                .thenReturn(json);
    }

    private void rulesResolve(String json) {
        when(executor.execute(eq(AccountingEventWriteGuard.RESOLVE_TEST_TOOL), anyString(), eq(AUTH)))
                .thenReturn(json);
    }

    private static String event(String status) {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"INVOICE_FINALIZED\",\"sourceSystem\":\"POS\","
                + "\"status\":\"" + status + "\",\"journalEntryId\":\"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e\","
                + "\"transactionDate\":\"2026-08-13T10:15:00\","
                + "\"payload\":{\"invoiceId\":\"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d\",\"totalAmount\":150.00}}";
    }

    /** A dry run that matched a published rule version and posts two lines. */
    private static String matchedRule(String version, String revenueAccount) {
        return "{\"matched\":true,\"matchedRule\":{\"ruleSetName\":\"Invoice posting\",\"versionNumber\":" + version
                + ",\"ruleVersionId\":\"" + RULE_VERSION_ID + "\"},\"resolvedLines\":["
                + "{\"accountCode\":\"1100\",\"debitAmount\":150.00,\"creditAmount\":0},"
                + "{\"accountCode\":\"" + revenueAccount + "\",\"debitAmount\":0,\"creditAmount\":150.00}]}";
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
            rulesResolve(matchedRule("3", "4000"));

            AccountingEventWriteGuard.Inspection inspection =
                    guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);

            assertThat(inspection.permitted()).isTrue();
            assertThat(inspection.eventId()).isEqualTo(EVENT_ID);
            assertThat(inspection.eventStatus()).isEqualTo("FAILED");
            assertThat(inspection.fingerprint()).hasSize(64);
            verify(executor).execute(AccountingEventWriteGuard.EVENT_READ_TOOL, READ_ARGS, AUTH);
        }

        @Test
        @DisplayName("reprocess of a SUSPENDED event is permitted")
        void reprocessOfSuspendedEventPermitted() {
            eventReads(event("SUSPENDED"));
            rulesResolve(matchedRule("3", "4000"));

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
            assertThat(inspection.fingerprint()).isNull();
            assertThat(inspection.message())
                    .contains(EVENT_ID, "already posted", "nothing was run", "reversing entry")
                    .contains("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e");
            verify(executor, never()).execute(eq(AccountingEventWriteGuard.RESOLVE_TEST_TOOL), anyString(), any());
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
            rulesResolve(matchedRule("3", "4000"));

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
            for (Map<String, Object> args : List.<Map<String, Object>>of(
                    Map.of(),
                    Map.of("pathParams", Map.of()),
                    Map.of("pathParams", Map.of("eventId", "not-a-uuid")),
                    Map.of("pathParams", Map.of("eventId", " ")),
                    Map.of("eventId", EVENT_ID),
                    Map.of("pathParams", Map.of("eventId", List.of(EVENT_ID, EVENT_ID))))) {
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
        @DisplayName("arguments carrying their own Authorization header, in any case, are refused without a read")
        void ownAuthorizationHeaderRefused() {
            for (String header : new String[] {"Authorization", "authorization", " AUTHORIZATION "}) {
                Map<String, Object> args = Map.of(
                        "pathParams", Map.of("eventId", EVENT_ID), "headers", Map.of(header, "Bearer someone-else"));

                AccountingEventWriteGuard.Inspection inspection =
                        guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, args, AUTH);

                assertThat(inspection.permitted()).as(header).isFalse();
                assertThat(inspection.message())
                        .as(header)
                        .contains("runs only as the signed-in caller", "own Authorization header");
            }
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("other headers do not trip the credential check")
        void otherHeadersAllowed() {
            eventReads(event("FAILED"));
            rulesResolve(matchedRule("3", "4000"));
            Map<String, Object> args =
                    Map.of("pathParams", Map.of("eventId", EVENT_ID), "headers", Map.of("X-Correlation-Id", "c-1"));

            assertThat(guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, args, AUTH)
                            .permitted())
                    .isTrue();
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
    @DisplayName("rules an unpinned posting follows")
    class Rules {

        @Test
        @DisplayName("the dry run is the event's own type, transaction date and payload, read as the caller")
        void dryRunUsesTheEventItself() throws Exception {
            eventReads(event("FAILED"));
            rulesResolve(matchedRule("3", "4000"));

            guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);

            ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
            verify(executor).execute(eq(AccountingEventWriteGuard.RESOLVE_TEST_TOOL), request.capture(), eq(AUTH));
            JsonNode body = objectMapper.readTree(request.getValue()).get("body");
            assertThat(body.get("eventType").asText()).isEqualTo("INVOICE_FINALIZED");
            assertThat(body.get("transactionDate").asText()).isEqualTo("2026-08-13");
            assertThat(body.get("samplePayload").get("totalAmount").decimalValue())
                    .isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("the preview names the matched rule version and the lines it would post, unpinned")
        void previewNamesMatchedRuleAndLines() {
            eventReads(event("FAILED"));
            rulesResolve(matchedRule("3", "4000"));

            String preview = guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                    .message();

            assertThat(preview)
                    .contains("posting rule set Invoice posting version 3 (" + RULE_VERSION_ID + ")")
                    .contains("DR 1100 150.00, CR 4000 150.00")
                    .contains("No mapping version is pinned");
        }

        @Test
        @DisplayName("no published rule set: the preview names the default GL mappings")
        void previewNamesDefaultMappings() {
            eventReads(event("SUSPENDED"));
            rulesResolve(
                    "{\"matched\":true,\"matchedRule\":null,\"resolvedLines\":["
                            + "{\"accountCode\":\"1100\",\"debitAmount\":150.00},{\"accountCode\":\"4010\",\"creditAmount\":150.00}]}");

            String preview = guard.inspect(AccountingEventWriteGuard.REPROCESS_TOOL, RETRY_ARGS, AUTH)
                    .message();

            assertThat(preview)
                    .contains("the default GL mappings (no published posting rule set)")
                    .contains("DR 1100 150.00, CR 4010 150.00");
        }

        @Test
        @DisplayName("a dry run that matches nothing refuses: the call would not post")
        void noMatchRefused() {
            eventReads(event("SUSPENDED"));
            rulesResolve("{\"matched\":false,\"noMatchReason\":\"UNMAPPED_EVENT_TYPE\"}");

            AccountingEventWriteGuard.Inspection inspection =
                    guard.inspect(AccountingEventWriteGuard.REPROCESS_TOOL, RETRY_ARGS, AUTH);

            assertThat(inspection.permitted()).isFalse();
            assertThat(inspection.message())
                    .contains("No posting rule or default GL mapping matches", "UNMAPPED_EVENT_TYPE", "would not post");
        }

        @Test
        @DisplayName("rules that cannot be told refuse; a reprocess is told how to pin a version")
        void unresolvedRulesRefused() {
            eventReads(event("SUSPENDED"));
            when(executor.execute(eq(AccountingEventWriteGuard.RESOLVE_TEST_TOOL), anyString(), any()))
                    .thenThrow(new WritePlanExecutionException("Write-plan tool execution failed: Error: 403"));

            AccountingEventWriteGuard.Inspection reprocess =
                    guard.inspect(AccountingEventWriteGuard.REPROCESS_TOOL, RETRY_ARGS, AUTH);
            assertThat(reprocess.permitted()).isFalse();
            assertThat(reprocess.message()).contains("Could not tell which posting rules", "mappingVersionToUse");

            eventReads(event("FAILED"));
            AccountingEventWriteGuard.Inspection retry =
                    guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);
            assertThat(retry.permitted()).isFalse();
            assertThat(retry.message())
                    .contains("Could not tell which posting rules")
                    .doesNotContain("mappingVersionToUse");
        }

        @Test
        @DisplayName("an event without a transaction date cannot be dry-run, so it is refused unread")
        void noTransactionDateRefused() {
            eventReads("{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"INVOICE_FINALIZED\",\"status\":\"FAILED\"}");

            AccountingEventWriteGuard.Inspection inspection =
                    guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH);

            assertThat(inspection.permitted()).isFalse();
            assertThat(inspection.message()).contains("no type or transaction date");
            verify(executor, never()).execute(eq(AccountingEventWriteGuard.RESOLVE_TEST_TOOL), anyString(), any());
        }

        @Test
        @DisplayName("a reprocess that names mappingVersionToUse posts under it: no dry run")
        void pinnedVersionSkipsDryRun() {
            eventReads(event("SUSPENDED"));
            Map<String, Object> pinned = Map.of(
                    "pathParams",
                    Map.of("eventId", EVENT_ID),
                    "body",
                    Map.of("triggeredByUserId", "jdoe", "mappingVersionToUse", RULE_VERSION_ID));

            AccountingEventWriteGuard.Inspection inspection =
                    guard.inspect(AccountingEventWriteGuard.REPROCESS_TOOL, pinned, AUTH);

            assertThat(inspection.permitted()).isTrue();
            assertThat(inspection.message()).contains("with mapping version " + RULE_VERSION_ID);
            verify(executor, never()).execute(eq(AccountingEventWriteGuard.RESOLVE_TEST_TOOL), anyString(), any());
        }

        @Test
        @DisplayName("the fingerprint holds while nothing changes, and moves when the rules do")
        void fingerprintTracksTheRules() {
            eventReads(event("FAILED"));
            rulesResolve(matchedRule("3", "4000"));
            String first = guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                    .fingerprint();
            String again = guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                    .fingerprint();

            rulesResolve(matchedRule("4", "4000"));
            String newVersion = guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                    .fingerprint();
            rulesResolve(matchedRule("3", "4100"));
            String newAccount = guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                    .fingerprint();

            assertThat(again).isEqualTo(first);
            assertThat(newVersion).isNotEqualTo(first);
            assertThat(newAccount).isNotEqualTo(first).isNotEqualTo(newVersion);
        }
    }

    @Nested
    @DisplayName("preview")
    class Preview {

        @Test
        @DisplayName("retry preview names the event, its type, source and amount, the posting, and no undo")
        void retryPreview() {
            eventReads(event("FAILED"));
            rulesResolve(matchedRule("3", "4000"));

            String preview = guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                    .message();

            assertThat(preview)
                    .contains(EVENT_ID, "INVOICE_FINALIZED", "POS", "totalAmount=150.00", "is FAILED")
                    .contains("A journal entry will post")
                    .contains("there is no undo", "reversing entry")
                    .contains("covers this one event only");
        }

        @Test
        @DisplayName("a payload without an amount is summarised by its field names")
        void payloadWithoutAmountSummarised() {
            eventReads("{\"eventId\":\"" + EVENT_ID + "\",\"status\":\"FAILED\",\"eventType\":\"WORKORDER_CLOSED\","
                    + "\"transactionDate\":\"2026-08-13T10:15:00\",\"payload\":{\"workorderId\":\"w-1\","
                    + "\"locationId\":\"l-1\"}}");
            rulesResolve(matchedRule("3", "4000"));

            assertThat(guard.inspect(AccountingEventWriteGuard.RETRY_TOOL, RETRY_ARGS, AUTH)
                            .message())
                    .contains("payload workorderId, locationId")
                    .contains("WORKORDER_CLOSED from unknown");
        }
    }

    @Test
    @DisplayName("the chat-path note carries the status check, the rules dry run, one event per confirmation")
    void guardNoteCarriesTheRules() {
        assertThat(AccountingEventWriteGuard.guardNote(AccountingEventWriteGuard.RETRY_TOOL))
                .contains("HIGH risk", "accounting_getaccountingevent", "FAILED", "no undo", "reversing entry")
                .contains("accounting_resolvetestmapping", "Never send your own Authorization header")
                .contains("One confirmation covers one eventId", "never call again blindly")
                .doesNotContain("mappingVersionToUse");
        assertThat(AccountingEventWriteGuard.guardNote(AccountingEventWriteGuard.REPROCESS_TOOL))
                .contains("SUSPENDED", "mappingVersionToUse when given");
        assertThat(AccountingEventWriteGuard.guardNote("orders_createorder")).isEmpty();
    }
}
