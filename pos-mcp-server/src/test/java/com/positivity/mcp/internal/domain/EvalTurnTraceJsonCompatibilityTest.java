package com.positivity.mcp.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * #1806: {@code serverBuild} was added to a record that is persisted as a JSON payload. Rows written
 * before the field existed must still read back — as a trace with no build, not as a failed read
 * that would hide every older turn from the gate.
 */
@DisplayName("EvalTurnTrace JSON payload — the serverBuild field is optional on read")
class EvalTurnTraceJsonCompatibilityTest {

    /** Built the way Spring Boot builds the bean the repository receives, not a bare mapper. */
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    /** A pre-#1806 row as persisted: every field the record had then, and nothing else. */
    private static final String LEGACY_PAYLOAD = """
            {"turnId":"01991b8a-0000-7000-8000-000000000001",
             "startedAt":"2026-09-05T23:51:14Z","completedAt":"2026-09-05T23:51:21Z",
             "expiresAt":"2026-09-06T23:51:21Z",
             "userId":"01960010-0000-7000-8000-000000000002","username":"admin.alpha","role":"ROLE_ADMIN",
             "userMessage":"Which technicians had the most reopened work orders this quarter?",
             "simpleChat":false,"intent":"ANALYTICS","modelTier":null,"workflowState":"IDLE",
             "selectedTools":["DateWindowFacadeTool"],"systemPrompt":null,
             "offeredTools":[],"toolCalls":[],"finalResponse":"answer","error":null}
            """;

    @Test
    void aPayloadWrittenBeforeServerBuildExistedReadsBackWithANullBuild() throws Exception {
        EvalTurnTrace read = mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class);

        assertThat(read.serverBuild()).isNull();
        assertThat(read.answerSource()).isNull();
        assertThat(read.username()).isEqualTo("admin.alpha");
        assertThat(read.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("#2075: a payload written before conversationId/messageId existed reads back with both null")
    void aPayloadWrittenBeforeConversationLinkExistedReadsBackWithNullIds() throws Exception {
        EvalTurnTrace read = mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class);

        assertThat(read.conversationId()).isNull();
        assertThat(read.messageId()).isNull();
    }

    /** A pre-#1806 row (also pre-#2075): neither serverBuild/answerSource nor the message link exist. */
    private static final String PRE_1806_PAYLOAD = """
            {"turnId":"01991b8a-0000-7000-8000-000000000002",
             "startedAt":"2026-08-01T00:00:00Z","completedAt":"2026-08-01T00:00:07Z",
             "expiresAt":"2026-08-02T00:00:07Z",
             "userId":"01960010-0000-7000-8000-000000000002","username":"admin.alpha","role":"ROLE_ADMIN",
             "userMessage":"How many open work orders?",
             "simpleChat":false,"intent":"ANALYTICS","modelTier":null,"workflowState":"IDLE",
             "selectedTools":[],"systemPrompt":null,
             "offeredTools":[],"toolCalls":[],"finalResponse":"answer","error":null}
            """;

    @Test
    @DisplayName("a pre-#1806 payload (older still) also reads back with every #2075 field null")
    void aPre1806PayloadReadsBackWithEveryNewFieldNull() throws Exception {
        EvalTurnTrace read = mapper.readValue(PRE_1806_PAYLOAD, EvalTurnTrace.class);

        assertThat(read.serverBuild()).isNull();
        assertThat(read.answerSource()).isNull();
        assertThat(read.conversationId()).isNull();
        assertThat(read.messageId()).isNull();
        assertThat(read.finalResponse()).isEqualTo("answer");
    }

    @Test
    void aCurrentPayloadRoundTripsTheBuild() throws Exception {
        EvalTurnTrace legacy = mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class);
        EvalTurnTrace stamped = new EvalTurnTrace(
                legacy.turnId(),
                legacy.startedAt(),
                legacy.completedAt(),
                legacy.expiresAt(),
                legacy.userId(),
                legacy.username(),
                legacy.role(),
                legacy.userMessage(),
                legacy.simpleChat(),
                legacy.intent(),
                legacy.modelTier(),
                legacy.workflowState(),
                legacy.selectedTools(),
                legacy.systemPrompt(),
                legacy.offeredTools(),
                legacy.toolCalls(),
                legacy.finalResponse(),
                legacy.error(),
                "sha-81ff1e0",
                "RE_RENDERED");

        String json = mapper.writeValueAsString(stamped);

        assertThat(json).contains("\"serverBuild\":\"sha-81ff1e0\"");
        assertThat(mapper.readValue(json, EvalTurnTrace.class).serverBuild()).isEqualTo("sha-81ff1e0");
        assertThat(mapper.readValue(json, EvalTurnTrace.class).answerSource()).isEqualTo("RE_RENDERED");
    }

    @Test
    @DisplayName("#2075: a current payload round-trips conversationId and messageId")
    void aCurrentPayloadRoundTripsConversationAndMessageIds() throws Exception {
        EvalTurnTrace legacy = mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class);
        java.util.UUID conversationId = java.util.UUID.fromString("0199b1be-7080-7000-8000-000000000abc");
        java.util.UUID messageId = java.util.UUID.fromString("0199b1be-7080-7000-8000-000000000def");
        EvalTurnTrace stamped = new EvalTurnTrace(
                legacy.turnId(),
                legacy.startedAt(),
                legacy.completedAt(),
                legacy.expiresAt(),
                legacy.userId(),
                legacy.username(),
                legacy.role(),
                legacy.userMessage(),
                legacy.simpleChat(),
                legacy.intent(),
                legacy.modelTier(),
                legacy.workflowState(),
                legacy.selectedTools(),
                legacy.systemPrompt(),
                legacy.offeredTools(),
                legacy.toolCalls(),
                legacy.finalResponse(),
                legacy.error(),
                "sha-81ff1e0",
                "RE_RENDERED",
                conversationId,
                messageId);

        String json = mapper.writeValueAsString(stamped);
        EvalTurnTrace roundTripped = mapper.readValue(json, EvalTurnTrace.class);

        assertThat(json).contains("\"conversationId\"").contains("\"messageId\"");
        assertThat(roundTripped.conversationId()).isEqualTo(conversationId);
        assertThat(roundTripped.messageId()).isEqualTo(messageId);
    }

    @Test
    @DisplayName("ADR-0069: a payload written before scope existed reads back with a null scope")
    void aPayloadWrittenBeforeScopeExistedReadsBackWithANullScope() throws Exception {
        assertThat(mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class).scope())
                .isNull();
        assertThat(mapper.readValue(PRE_1806_PAYLOAD, EvalTurnTrace.class).scope())
                .isNull();
    }

    /** A pre-ADR-0069 row: the #2075 shape, with the message link and no scope. */
    private static final String PRE_SCOPE_PAYLOAD = """
            {"turnId":"01991b8a-0000-7000-8000-000000000003",
             "startedAt":"2026-09-28T10:00:00Z","completedAt":"2026-09-28T10:00:04Z",
             "expiresAt":"2026-10-28T10:00:04Z",
             "userId":"01960010-0000-7000-8000-000000000002","username":"admin.alpha","role":"ROLE_ADMIN",
             "userMessage":"Is work order WO-20391 billed?",
             "simpleChat":false,"intent":"LOOKUP","modelTier":"T2_SIMPLE","workflowState":"IDLE",
             "selectedTools":["WorkorderFacadeTool"],"systemPrompt":"prompt",
             "offeredTools":[{"name":"getWorkorder","description":"d","inputSchema":"{}"}],
             "toolCalls":[{"sequence":1,"name":"getWorkorder","arguments":"{}","result":"{}","error":null,"elapsedMs":12}],
             "finalResponse":"answer","error":null,"serverBuild":"sha-35b2f00","answerSource":"CONTENT",
             "conversationId":"0199b1be-7080-7000-8000-000000000abc",
             "messageId":"0199b1be-7080-7000-8000-000000000def"}
            """;

    @Test
    @DisplayName("ADR-0069: the newest pre-scope payload, with every other field set, still reads back whole")
    void theNewestPreScopePayloadStillReadsBack() throws Exception {
        EvalTurnTrace read = mapper.readValue(PRE_SCOPE_PAYLOAD, EvalTurnTrace.class);

        assertThat(read.scope()).isNull();
        assertThat(read.toolCalls()).hasSize(1);
        assertThat(read.serverBuild()).isEqualTo("sha-35b2f00");
        assertThat(read.messageId()).hasToString("0199b1be-7080-7000-8000-000000000def");
    }

    @Test
    @DisplayName(
            "ADR-0069: a current payload round-trips its scope, which holds keys, kinds and counts and no matched text")
    void aCurrentPayloadRoundTripsItsScope() throws Exception {
        EvalTurnTrace legacy = mapper.readValue(PRE_SCOPE_PAYLOAD, EvalTurnTrace.class);
        ScopeTrace scope = new ScopeTrace(
                "SHADOW",
                java.util.List.of(),
                "c878c7206d2ed660",
                java.time.Instant.parse("2026-09-30T12:00:00Z"),
                "HIGH",
                java.util.List.of(new ScopeTrace.SeedTrace("workorder", "IDENTIFIER")),
                3,
                5,
                4,
                2,
                0,
                false,
                1,
                1,
                2,
                5);
        EvalTurnTrace stamped = new EvalTurnTrace(
                legacy.turnId(),
                legacy.startedAt(),
                legacy.completedAt(),
                legacy.expiresAt(),
                legacy.userId(),
                legacy.username(),
                legacy.role(),
                legacy.userMessage(),
                legacy.simpleChat(),
                legacy.intent(),
                legacy.modelTier(),
                legacy.workflowState(),
                legacy.selectedTools(),
                legacy.systemPrompt(),
                legacy.offeredTools(),
                legacy.toolCalls(),
                legacy.finalResponse(),
                legacy.error(),
                legacy.serverBuild(),
                legacy.answerSource(),
                legacy.conversationId(),
                legacy.messageId(),
                scope);

        String json = mapper.writeValueAsString(stamped);
        EvalTurnTrace roundTripped = mapper.readValue(json, EvalTurnTrace.class);

        assertThat(roundTripped.scope()).isEqualTo(scope);
        assertThat(roundTripped).isEqualTo(stamped);
        String scopeJson = mapper.writeValueAsString(scope);
        assertThat(scopeJson)
                .contains("\"mode\":\"SHADOW\"")
                .contains("\"graphHash\":\"c878c7206d2ed660\"")
                .contains("\"seeds\":[{\"entity\":\"workorder\",\"matchKind\":\"IDENTIFIER\"}]")
                .contains("\"calledToolsInScope\":1")
                .contains("\"retrievedDocsInScope\":2")
                // The identifier the user typed is in the trace's userMessage, never in its scope.
                .doesNotContain("WO-20391");
    }

    /** A scope written before the identity lists existed (counts only): the first ADR-0069 shape. */
    private static final String COUNTS_ONLY_SCOPE_PAYLOAD = """
            {"mode":"SHADOW","enforced":[],"graphHash":"c878c7206d2ed660","graphBuiltAt":"2026-09-30T12:00:00Z",
             "confidence":"HIGH","seeds":[{"entity":"workorder","matchKind":"IDENTIFIER"}],
             "entityCount":3,"toolCount":5,"documentCount":4,"screenCount":2,"addedTools":0,
             "ragFilterApplied":false,"calledToolsInScope":1,"calledTools":1,
             "retrievedDocsInScope":2,"retrievedDocs":5}
            """;

    @Test
    @DisplayName("ADR-0069 §9: a counts-only scope payload reads back with null identity lists and nothing truncated")
    void aCountsOnlyScopePayloadReadsBackWithNullIdentityLists() throws Exception {
        ScopeTrace read = mapper.readValue(COUNTS_ONLY_SCOPE_PAYLOAD, ScopeTrace.class);

        assertThat(read.retrievedDocs()).isEqualTo(5);
        assertThat(read.retrievedDocsInScope()).isEqualTo(2);
        assertThat(read.retrievedDocuments()).isNull();
        assertThat(read.scopeDocumentIds()).isNull();
        assertThat(read.scopeDocumentIdsTruncated()).isFalse();
        assertThat(read.scopeToolNames()).isNull();
        assertThat(read.scopeToolNamesTruncated()).isFalse();
        assertThat(read.addedToolNames()).isNull();
        // The counts-only constructor still exists and produces the same value.
        assertThat(read)
                .isEqualTo(new ScopeTrace(
                        "SHADOW",
                        java.util.List.of(),
                        "c878c7206d2ed660",
                        java.time.Instant.parse("2026-09-30T12:00:00Z"),
                        "HIGH",
                        java.util.List.of(new ScopeTrace.SeedTrace("workorder", "IDENTIFIER")),
                        3,
                        5,
                        4,
                        2,
                        0,
                        false,
                        1,
                        1,
                        2,
                        5));
    }

    @Test
    @DisplayName(
            "ADR-0069 §9: a current scope payload round-trips its identity lists, ordered, with a null rag_scope kept")
    void aCurrentScopePayloadRoundTripsItsIdentityLists() throws Exception {
        ScopeTrace scope = new ScopeTrace(
                "SHADOW",
                java.util.List.of(),
                "c878c7206d2ed660",
                java.time.Instant.parse("2026-09-30T12:00:00Z"),
                "HIGH",
                java.util.List.of(new ScopeTrace.SeedTrace("workorder", "IDENTIFIER")),
                3,
                2,
                2,
                1,
                1,
                false,
                1,
                1,
                1,
                3,
                java.util.List.of(
                        new ScopeTrace.RetrievedDocument("billing.invoices", "billing"),
                        new ScopeTrace.RetrievedDocument("workorder.public", "workorder"),
                        new ScopeTrace.RetrievedDocument("order.codes", null)),
                java.util.List.of("workorder.status-lifecycle", "workorder.public"),
                false,
                java.util.List.of("WorkorderFacadeTool", "workorder_getworkorder"),
                false,
                java.util.List.of("WorkorderFacadeTool"));

        String json = mapper.writeValueAsString(scope);
        ScopeTrace roundTripped = mapper.readValue(json, ScopeTrace.class);

        assertThat(roundTripped).isEqualTo(scope);
        assertThat(roundTripped.retrievedDocuments())
                .extracting(ScopeTrace.RetrievedDocument::documentId)
                .containsExactly("billing.invoices", "workorder.public", "order.codes");
        assertThat(json)
                .contains("\"retrievedDocuments\":[{\"documentId\":\"billing.invoices\",\"ragScope\":\"billing\"}")
                .contains("\"scopeDocumentIds\":[\"workorder.status-lifecycle\",\"workorder.public\"]")
                .contains("\"scopeDocumentIdsTruncated\":false")
                .contains("\"scopeToolNames\":[\"WorkorderFacadeTool\",\"workorder_getworkorder\"]")
                .contains("\"addedToolNames\":[\"WorkorderFacadeTool\"]");
    }

    @Test
    @DisplayName("ADR-0068: every payload written before tagging existed reads back with null tags")
    void aPayloadWrittenBeforeTagsExistedReadsBackWithNullTags() throws Exception {
        assertThat(mapper.readValue(LEGACY_PAYLOAD, EvalTurnTrace.class).tags()).isNull();
        assertThat(mapper.readValue(PRE_1806_PAYLOAD, EvalTurnTrace.class).tags())
                .isNull();
        assertThat(mapper.readValue(PRE_SCOPE_PAYLOAD, EvalTurnTrace.class).tags())
                .isNull();
        // The ADR-0069 shape (a scope, no tags) reads back the same way.
        EvalTurnTrace legacy = mapper.readValue(PRE_SCOPE_PAYLOAD, EvalTurnTrace.class);
        String withScope = mapper.writeValueAsString(new EvalTurnTrace(
                legacy.turnId(),
                legacy.startedAt(),
                legacy.completedAt(),
                legacy.expiresAt(),
                legacy.userId(),
                legacy.username(),
                legacy.role(),
                legacy.userMessage(),
                legacy.simpleChat(),
                legacy.intent(),
                legacy.modelTier(),
                legacy.workflowState(),
                legacy.selectedTools(),
                legacy.systemPrompt(),
                legacy.offeredTools(),
                legacy.toolCalls(),
                legacy.finalResponse(),
                legacy.error(),
                legacy.serverBuild(),
                legacy.answerSource(),
                legacy.conversationId(),
                legacy.messageId(),
                null));
        assertThat(mapper.readValue(withScope, EvalTurnTrace.class).tags()).isNull();
    }

    @Test
    @DisplayName(
            "ADR-0068: a current payload round-trips its tags, which hold names, values and confidences and no text")
    void aCurrentPayloadRoundTripsItsTags() throws Exception {
        EvalTurnTrace legacy = mapper.readValue(PRE_SCOPE_PAYLOAD, EvalTurnTrace.class);
        TagTrace tags = new TagTrace(
                "SHADOW",
                java.util.List.of(),
                "tev1:0.8b",
                212L,
                null,
                false,
                46,
                18_432,
                "c878c7206d2ed660",
                java.util.List.of(
                        new TagTrace.TagEntry(
                                "simple_chat", "false", "HEURISTIC", "false", null, "false", 0.93, 0.07, 0.75, true),
                        new TagTrace.TagEntry(
                                "workflow_state",
                                "IDLE",
                                "HEURISTIC",
                                "IDLE",
                                "phrase:purchase order",
                                "CREATING_PO",
                                0.81,
                                null,
                                0.75,
                                false),
                        new TagTrace.TagEntry(
                                "entity_work-order", null, null, null, null, "true", 0.88, 0.88, 0.8, null)));
        EvalTurnTrace stamped = new EvalTurnTrace(
                legacy.turnId(),
                legacy.startedAt(),
                legacy.completedAt(),
                legacy.expiresAt(),
                legacy.userId(),
                legacy.username(),
                legacy.role(),
                legacy.userMessage(),
                legacy.simpleChat(),
                legacy.intent(),
                legacy.modelTier(),
                legacy.workflowState(),
                legacy.selectedTools(),
                legacy.systemPrompt(),
                legacy.offeredTools(),
                legacy.toolCalls(),
                legacy.finalResponse(),
                legacy.error(),
                legacy.serverBuild(),
                legacy.answerSource(),
                legacy.conversationId(),
                legacy.messageId(),
                null,
                tags);

        String json = mapper.writeValueAsString(stamped);
        EvalTurnTrace roundTripped = mapper.readValue(json, EvalTurnTrace.class);

        assertThat(roundTripped.tags()).isEqualTo(tags);
        assertThat(roundTripped).isEqualTo(stamped);
        String tagsJson = mapper.writeValueAsString(tags);
        assertThat(tagsJson)
                .contains("\"mode\":\"SHADOW\"")
                .contains("\"providerModel\":\"tev1:0.8b\"")
                .contains("\"name\":\"workflow_state\"")
                .contains("\"modelValue\":\"CREATING_PO\"")
                .contains("\"heuristicRule\":\"phrase:purchase order\"")
                .contains("\"modelProbability\":0.88")
                .contains("\"threshold\":0.8")
                .contains("\"questionCount\":46")
                .contains("\"requestBodyBytes\":18432")
                .contains("\"optionListHash\":\"c878c7206d2ed660\"")
                .contains("\"agree\":false")
                // The identifier the user typed is in the trace's userMessage, never in its tags.
                .doesNotContain("WO-20391");
    }
}
