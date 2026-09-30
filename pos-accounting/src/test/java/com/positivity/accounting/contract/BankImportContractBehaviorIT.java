package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportFileRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRowRepository;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Contract behaviour of the statement-file import endpoints (SPEC-manual-bank-reconciliation §4.3,
 * §4.4, §4.10, §6.1, §8.2; story S3, #2302): every code with its status and envelope ({@code
 * fieldErrors[rows[n]]}, {@code fieldErrors[activityTotal]}, the earlier ids of a whole-file
 * duplicate), the enum values served, a 403 per endpoint and the retired F2 import route.
 */
@DisplayName("Bank imports — contract behaviour (#2302)")
class BankImportContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String IMPORTS = "/v1/accounting/bank-imports";
    private static final String ACK = "First statement reconciled on this account";
    private static final String CSV = "date,description,amount,reference\n2025-09-02,ACH DEPOSIT,500.00,DEP-1\n"
            + "2025-09-15,MONTHLY FEE,-15.00,\n";
    private static final String VIEW = "accounting:reconciliation:view";
    private static final String ADJUST = "accounting:reconciliation:adjust";

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private BankImportRepository imports;

    @Autowired
    private BankImportRowRepository importRows;

    @Autowired
    private BankImportFileRepository importFiles;

    @Autowired
    private BankStatementRepository statements;

    @Autowired
    private BankTransactionRepository transactions;

    @Autowired
    private BankAccountProfileRepository profiles;

    @Autowired
    private AccountingAuditLogRepository auditLogs;

    private UUID cash;
    private final List<UUID> accountsCreated = new ArrayList<>();

    @BeforeEach
    void account() {
        String suffix = UUIDv7Generator.generate().toString().substring(24);
        GLAccount account = new GLAccount();
        account.setGlAccountId(UUIDv7Generator.generate());
        account.setAccountCode("M" + suffix);
        account.setAccountName("Import cash " + suffix);
        account.setAccountType(AccountType.ASSET);
        account.setAccountSubtype(AccountSubtype.BANK_CASH);
        account.setReconcilable(true);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        account.setCreatedBy(TEST_USER);
        account.setModifiedBy(TEST_USER);
        cash = glAccounts.save(account).getGlAccountId();
        accountsCreated.add(cash);
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : accountsCreated) {
            imports.findAll().stream()
                    .filter(i -> id.equals(i.getGlAccountId()))
                    .forEach(i -> {
                        importRows.deleteAll(importRows.findByImportIdOrderByRowNumberAsc(i.getImportId()));
                        importFiles.findById(i.getImportId()).ifPresent(importFiles::delete);
                        imports.delete(i);
                    });
            transactions.findAll().stream()
                    .filter(t -> id.equals(t.getGlAccountId()))
                    .forEach(transactions::delete);
            statements.findAll().stream()
                    .filter(s -> id.equals(s.getGlAccountId()))
                    .forEach(statements::delete);
            profiles.findById(id).ifPresent(profiles::delete);
            glAccounts.deleteById(id);
        }
        accountsCreated.clear();
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private Map<String, Object> body(String csv, String start, String end, String opening, String closing, String ack) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("glAccountId", cash.toString());
        body.put("requestId", UUIDv7Generator.generate().toString());
        body.put("formatCode", "CSV");
        body.put("fileName", "september.csv");
        body.put("content", Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8)));
        body.put(
                "statement",
                Map.of("startDate", start, "endDate", end, "openingBalance", opening, "closingBalance", closing));
        if (ack != null) {
            body.put("gapAcknowledgement", ack);
        }
        return body;
    }

    private Map<String, Object> september() {
        return body(CSV, "2025-09-01", "2025-09-30", "1000.00", "1485.00", ACK);
    }

    private ResultActions upload(Map<String, Object> body) throws Exception {
        return mockMvc.perform(withAuth(post(IMPORTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return mockMvc.perform(withAuth(builder)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String uploaded(Map<String, Object> body) throws Exception {
        return json(upload(body).andExpect(status().isCreated()))
                .get("importId")
                .asString();
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // ---- upload --------------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /bank-imports")
    class Upload {

        @Test
        void aGoodFileIs201ValidatedWithCountsAndPreview() throws Exception {
            upload(september())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("VALIDATED"))
                    .andExpect(jsonPath("$.mappingRequired").value(false))
                    .andExpect(jsonPath("$.glAccountId").value(cash.toString()))
                    .andExpect(jsonPath("$.glAccountCode").isNotEmpty())
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.formatCode").value("CSV"))
                    .andExpect(jsonPath("$.rowCount").value(2))
                    .andExpect(jsonPath("$.acceptedCount").value(2))
                    .andExpect(jsonPath("$.columns[0]").value("date"))
                    .andExpect(jsonPath("$.signConvention").value("SIGNED_AMOUNT"))
                    .andExpect(jsonPath("$.preview.ties").value(true))
                    .andExpect(jsonPath("$.preview.firstRows[1].rowStatus").value("PARSED"))
                    .andExpect(jsonPath("$.retentionUntil").isNotEmpty())
                    .andExpect(jsonPath("$.filePurged").value(false))
                    .andExpect(jsonPath("$.replayed").value(false))
                    .andExpect(jsonPath("$.version").isNumber());
        }

        @Test
        void aMultipartUploadStagesTheSameWay() throws Exception {
            Map<String, Object> meta = september();
            meta.remove("content");
            MockMultipartFile file =
                    new MockMultipartFile("file", "sept.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));
            MockMultipartFile metaPart = new MockMultipartFile(
                    "meta", "", MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(meta));
            mockMvc.perform(multipart(IMPORTS)
                            .file(file)
                            .file(metaPart)
                            .header("X-User", TEST_USER)
                            .header("X-Authorities", TEST_AUTHORITIES))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("VALIDATED"))
                    .andExpect(jsonPath("$.fileName").value("september.csv"))
                    .andExpect(jsonPath("$.contentType").value("text/csv"))
                    .andExpect(jsonPath("$.fileSize").value(CSV.getBytes(StandardCharsets.UTF_8).length));
        }

        @Test
        void aReplayReturnsTheOriginalAndADifferentPayloadIs409() throws Exception {
            Map<String, Object> body = september();
            String first = uploaded(body);
            upload(body)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.importId").value(first))
                    .andExpect(jsonPath("$.replayed").value(true));
            body.put("gapAcknowledgement", ACK + " (changed)");
            expectError(upload(body), 409, "IDEMPOTENCY_CONFLICT");
        }

        @Test
        void theAccountsFirstFileWithoutAnAcknowledgementIs422NotContiguous() throws Exception {
            expectError(
                            upload(body(CSV, "2025-09-01", "2025-09-30", "1000", "1485", null)),
                            422,
                            "STATEMENT_NOT_CONTIGUOUS")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("gapAcknowledgement"));
        }

        @Test
        void aFiveCharacterAcknowledgementIs400JustificationRequired() throws Exception {
            expectError(
                    upload(body(CSV, "2025-09-01", "2025-09-30", "1000", "1485", "short")),
                    400,
                    "JUSTIFICATION_REQUIRED");
        }

        @Test
        void aFileInEurOnAnAccountWithoutAProfileIs422CurrencyNotSupported() throws Exception {
            Map<String, Object> body = september();
            body.put("currency", "EUR");
            expectError(upload(body), 422, "CURRENCY_NOT_SUPPORTED")
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("expected USD"));
        }

        @Test
        void anUnreadableFileIs422StatementImportFailed() throws Exception {
            Map<String, Object> body = september();
            body.put("content", Base64.getEncoder().encodeToString(new byte[] {'P', 'K', 3, 4, 0, 0}));
            expectError(upload(body), 422, "STATEMENT_IMPORT_FAILED");
        }

        @Test
        void aFormatOtherThanCsvIs400() throws Exception {
            Map<String, Object> body = september();
            body.put("formatCode", "OFX");
            expectError(upload(body), 400, "VALIDATION_ERROR")
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("formatCode"));
        }

        @Test
        void aNonReconcilableAccountIs422() throws Exception {
            GLAccount revenue = glAccounts.findById(cash).orElseThrow();
            revenue.setReconcilable(false);
            glAccounts.save(revenue);
            expectError(upload(september()), 422, "ACCOUNT_NOT_RECONCILABLE");
        }
    }

    // ---- mapping, rows, reads ------------------------------------------------------------------

    @Nested
    @DisplayName("mapping, rows and reads")
    class MappingAndRows {

        @Test
        void differentHeaderNamesNeedAMappingThenValidate() throws Exception {
            String id = json(upload(body(
                                    "Posted Date,Payee,Amount\n2025-09-02,DEPOSIT,500.00",
                                    "2025-09-01",
                                    "2025-09-30",
                                    "0",
                                    "500",
                                    ACK))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.status").value("UPLOADED"))
                            .andExpect(jsonPath("$.mappingRequired").value(true))
                            .andExpect(jsonPath("$.columns[1]").value("Payee")))
                    .get("importId")
                    .asString();

            mockMvc.perform(withAuth(get(IMPORTS + "/" + id + "/rows")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.columns[0]").value("Posted Date"))
                    .andExpect(jsonPath("$.rows[0].rawValues.Payee").value("DEPOSIT"))
                    .andExpect(jsonPath("$.rows[0].rowStatus").value("REJECTED"))
                    .andExpect(jsonPath("$.rows[0].rejectionCode").value("REQUIRED_COLUMN_MISSING"));

            send(
                            put(IMPORTS + "/" + id + "/mapping"),
                            Map.of("columnMapping", Map.of("date", "Posted Date", "description", "Payee", "amount", 2)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("VALIDATED"))
                    .andExpect(jsonPath("$.mappingRequired").value(false))
                    .andExpect(jsonPath("$.acceptedCount").value(1))
                    .andExpect(jsonPath("$.columnMapping.amount").value(2));
        }

        @Test
        void rejectedRowsAreListedByStatusAndCorrected() throws Exception {
            String id = uploaded(body(
                    "date,description,amount\n2025-09-02,A,1.00\n2025-13-45,B,2.00\n2025-09-04,C,3.00",
                    "2025-09-01",
                    "2025-09-30",
                    "0",
                    "6",
                    ACK));
            JsonNode rejected = json(mockMvc.perform(withAuth(get(IMPORTS + "/" + id + "/rows?status=REJECTED")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.rows[0].rowNumber").value(2))
                    .andExpect(jsonPath("$.rows[0].rejectionCode").value("DATE_UNPARSEABLE"))
                    .andExpect(jsonPath("$.rows[0].rejectionDetail").value("Line 3: unparseable date '2025-13-45'")));
            String rowId = rejected.get("rows").get(0).get("rowId").asString();

            expectError(send(post(IMPORTS + "/" + id + "/commit"), Map.of()), 422, "IMPORT_NOT_COMMITTABLE")
                    .andExpect(
                            jsonPath("$.fieldErrors[?(@.field == 'rows[2]')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'activityTotal')].message")
                            .value("opening + activity = 4.00, closing = 6.00"));

            send(put(IMPORTS + "/" + id + "/rows/" + rowId), Map.of("correctedValues", Map.of("date", "2025-09-03")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rowStatus").value("CORRECTED"))
                    .andExpect(jsonPath("$.rawValues.date").value("2025-13-45"))
                    .andExpect(jsonPath("$.correctedBy").value(TEST_USER));
            expectError(
                    send(put(IMPORTS + "/" + id + "/rows/" + rowId), Map.of("skip", true, "reason", "short")),
                    400,
                    "JUSTIFICATION_REQUIRED");

            mockMvc.perform(withAuth(get(IMPORTS + "?glAccountId=" + cash + "&status=VALIDATED")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.imports[0].importId").value(id))
                    .andExpect(jsonPath("$.imports[0].preview").doesNotExist());
        }

        @Test
        void anOverPreciseAmountIsStagedRejectedCorrectedAndThenCommits() throws Exception {
            // #2336: 12.345 is finer than USD's minor unit; 12.340 is not (trailing zeros do not count).
            String id = uploaded(body(
                    "date,description,amount\n2025-09-02,A,100.00\n2025-09-03,B,12.345\n2025-09-04,C,12.340",
                    "2025-09-01",
                    "2025-09-30",
                    "0",
                    "124.69",
                    ACK));
            mockMvc.perform(withAuth(get(IMPORTS + "/" + id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("VALIDATED"))
                    .andExpect(jsonPath("$.rowCount").value(3))
                    .andExpect(jsonPath("$.acceptedCount").value(2))
                    .andExpect(jsonPath("$.rejectedCount").value(1));
            JsonNode rejected = json(mockMvc.perform(withAuth(get(IMPORTS + "/" + id + "/rows?status=REJECTED")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.rows[0].rowNumber").value(2))
                    .andExpect(jsonPath("$.rows[0].rowStatus").value("REJECTED"))
                    .andExpect(jsonPath("$.rows[0].rejectionCode").value("AMOUNT_PRECISION_EXCEEDS_CURRENCY")));
            mockMvc.perform(withAuth(get(IMPORTS + "/" + id + "/rows?status=PARSED")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2));

            expectError(send(post(IMPORTS + "/" + id + "/commit"), Map.of()), 422, "IMPORT_NOT_COMMITTABLE");

            String rowId = rejected.get("rows").get(0).get("rowId").asString();
            send(put(IMPORTS + "/" + id + "/rows/" + rowId), Map.of("correctedValues", Map.of("signedAmount", "12.35")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rowStatus").value("CORRECTED"))
                    .andExpect(jsonPath("$.rejectionCode").doesNotExist());

            send(post(IMPORTS + "/" + id + "/commit"), Map.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bankTransactionCount").value(3));
        }

        @Test
        void anUnknownImportOrRowIs404() throws Exception {
            expectError(
                    mockMvc.perform(withAuth(get(IMPORTS + "/" + UUIDv7Generator.generate()))),
                    404,
                    "BANK_IMPORT_NOT_FOUND");
            String id = uploaded(september());
            expectError(
                    send(
                            put(IMPORTS + "/" + id + "/rows/" + UUIDv7Generator.generate()),
                            Map.of("skip", true, "reason", "not on the bank statement")),
                    404,
                    "BANK_IMPORT_ROW_NOT_FOUND");
        }
    }

    // ---- commit, discard, download -------------------------------------------------------------

    @Nested
    @DisplayName("commit, discard and download")
    class Lifecycle {

        @Test
        void aCommitCreatesTheStatementAndIsIdempotentAndTheSameFileIs409() throws Exception {
            String id = uploaded(september());

            JsonNode committed = json(send(post(IMPORTS + "/" + id + "/commit"), Map.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bankTransactionCount").value(2))
                    .andExpect(jsonPath("$.possibleDuplicateCount").value(0))
                    .andExpect(jsonPath("$.statementIds.length()").value(1)));
            String statementId = committed.get("statementId").asString();

            send(post(IMPORTS + "/" + id + "/commit"), Map.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statementId").value(statementId))
                    .andExpect(jsonPath("$.bankTransactionCount").value(2));

            mockMvc.perform(withAuth(get("/v1/accounting/bank-statements/" + statementId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sourceKind").value("FILE_IMPORT"))
                    .andExpect(jsonPath("$.gapAcknowledgement").value(ACK))
                    .andExpect(jsonPath("$.bankTransactionCount").value(2));
            mockMvc.perform(withAuth(get(IMPORTS + "/" + id)))
                    .andExpect(jsonPath("$.status").value("COMMITTED"))
                    .andExpect(jsonPath("$.committedBy").value(TEST_USER));

            Map<String, Object> again = september();
            again.put(
                    "statement",
                    Map.of(
                            "startDate",
                            "2025-10-01",
                            "endDate",
                            "2025-10-31",
                            "openingBalance",
                            "1485.00",
                            "closingBalance",
                            "1970.00"));
            expectError(upload(again), 409, "IMPORT_FILE_ALREADY_COMMITTED")
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'importId')].message")
                            .value(id))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'statementId')].message")
                            .value(statementId));

            expectError(
                    send(put(IMPORTS + "/" + id + "/mapping"), Map.of("columnMapping", Map.of("date", 0))),
                    409,
                    "IMPORT_ALREADY_COMMITTED");

            // The next month continues September: an acknowledgement is refused.
            expectError(
                    upload(body(
                            "date,description,amount\n2025-10-02,DEP,10.00",
                            "2025-10-01",
                            "2025-10-31",
                            "1485.00",
                            "1495.00",
                            ACK)),
                    422,
                    "STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE");
            // The same window from another file is refused at the door.
            expectError(
                    upload(body(
                            "date,description,amount\n2025-09-03,X,485.00",
                            "2025-09-01",
                            "2025-09-30",
                            "1000.00",
                            "1485.00",
                            ACK)),
                    409,
                    "STATEMENT_ALREADY_IMPORTED");

            List<AccountingAuditLog> audit =
                    auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("BANK_IMPORT", UUID.fromString(id));
            assertThat(audit)
                    .extracting(AccountingAuditLog::getOperation)
                    .containsExactly("BANK_IMPORT_CREATE", "BANK_IMPORT_COMMIT");
        }

        @Test
        void twoIdenticalFeesConfirmedDistinctAreBothUnmatched() throws Exception {
            String id = uploaded(body(
                    "date,description,amount\n2025-09-02,FEE,-5.00\n2025-09-02,FEE,-5.00",
                    "2025-09-01",
                    "2025-09-30",
                    "100",
                    "90",
                    ACK));
            mockMvc.perform(withAuth(get(IMPORTS + "/" + id + "/rows?status=POSSIBLE_DUPLICATE")))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.rows[0].duplicateOfRowNumber").value(2));

            send(
                            post(IMPORTS + "/" + id + "/commit"),
                            Map.of(
                                    "duplicateDecisions",
                                    List.of(
                                            Map.of("rowNumber", 1, "decision", "DISTINCT"),
                                            Map.of("rowNumber", 2, "decision", "DISTINCT"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.possibleDuplicateCount").value(0));

            mockMvc.perform(withAuth(get("/v1/accounting/bank-transactions?glAccountId=" + cash)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(2))
                    .andExpect(jsonPath("$.transactions[0].status").value("UNMATCHED"))
                    .andExpect(jsonPath("$.transactions[1].status").value("UNMATCHED"));
        }

        @Test
        void aDiscardedImportRefusesTheCommit() throws Exception {
            String id = uploaded(september());
            expectError(
                    send(post(IMPORTS + "/" + id + "/discard"), Map.of("reason", "oops")),
                    400,
                    "JUSTIFICATION_REQUIRED");
            send(post(IMPORTS + "/" + id + "/discard"), Map.of("reason", "wrong account's file"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISCARDED"))
                    .andExpect(jsonPath("$.discardReason").value("wrong account's file"));
            expectError(send(post(IMPORTS + "/" + id + "/commit"), Map.of()), 409, "IMPORT_DISCARDED");
            expectError(
                    send(post(IMPORTS + "/" + id + "/discard"), Map.of("reason", "wrong account's file")),
                    409,
                    "IMPORT_DISCARDED");
            expectError(
                    send(
                            post(IMPORTS + "/" + id + "/discard"),
                            Map.of("reason", "wrong account's file", "version", 999)),
                    409,
                    "IMPORT_DISCARDED");
        }

        @Test
        void aStaleVersionIs409OptimisticLock() throws Exception {
            String id = uploaded(september());
            expectError(
                    send(
                            post(IMPORTS + "/" + id + "/discard"),
                            Map.of("reason", "wrong account's file", "version", 999)),
                    409,
                    "OPTIMISTIC_LOCK");
        }

        @Test
        void theDownloadReturnsTheBytesToAnApproverAndIsAudited() throws Exception {
            String id = uploaded(september());
            mockMvc.perform(withAuth(get(IMPORTS + "/" + id + "/file")))
                    .andExpect(status().isOk())
                    .andExpect(header().string(
                                    "Content-Disposition", org.hamcrest.Matchers.containsString("september.csv")))
                    .andExpect(content().bytes(CSV.getBytes(StandardCharsets.UTF_8)));
            assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("BANK_IMPORT", UUID.fromString(id)))
                    .extracting(AccountingAuditLog::getOperation)
                    .contains("BANK_IMPORT_FILE_READ");
            mockMvc.perform(withAuth(get(IMPORTS + "/" + id + "/file"), VIEW + "," + ADJUST))
                    .andExpect(status().isForbidden());
        }
    }

    // ---- authorization and the retired route ---------------------------------------------------

    @Nested
    @DisplayName("403 per endpoint and the retired F2 import")
    class Authorization {

        @Test
        void eachEndpointRefusesACallerWithoutItsPermission() throws Exception {
            String id = UUIDv7Generator.generate().toString();
            String row = UUIDv7Generator.generate().toString();
            mockMvc.perform(withAuth(post(IMPORTS), VIEW)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(september())))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withAuth(get(IMPORTS), ADJUST)).andExpect(status().isForbidden());
            mockMvc.perform(withAuth(get(IMPORTS + "/" + id), ADJUST)).andExpect(status().isForbidden());
            mockMvc.perform(withAuth(get(IMPORTS + "/" + id + "/rows"), ADJUST)).andExpect(status().isForbidden());
            mockMvc.perform(withAuth(put(IMPORTS + "/" + id + "/mapping"), VIEW)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"columnMapping\":{\"date\":0}}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withAuth(put(IMPORTS + "/" + id + "/rows/" + row), VIEW)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"skip\":true,\"reason\":\"not on the statement\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withAuth(post(IMPORTS + "/" + id + "/commit"), VIEW)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withAuth(post(IMPORTS + "/" + id + "/discard"), VIEW)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"wrong account's file\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withAuth(get(IMPORTS + "/" + id + "/file"), VIEW + "," + ADJUST))
                    .andExpect(status().isForbidden());
        }

        @Test
        void theRetiredF2ImportRouteIsNotServed() throws Exception {
            int status = mockMvc.perform(withAuth(post("/v1/accounting/reconciliations/import"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"csv\":\"2026-06-15,ACH DEPOSIT,1500.00\"}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            // /reconciliations/{id} still serves GET, so the retired POST is a routing failure: 405
            // METHOD_NOT_ALLOWED from the shared handler rather than a 404 (no route matches /import).
            assertThat(status).isEqualTo(405);
        }
    }
}
