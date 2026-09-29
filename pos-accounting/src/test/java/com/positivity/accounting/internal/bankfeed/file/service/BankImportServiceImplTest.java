package com.positivity.accounting.internal.bankfeed.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCreateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportDiscardRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportMappingRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowUpdateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportSplitPoint;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportStatementHeader;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImport;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImportFile;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImportRow;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import com.positivity.accounting.internal.bankfeed.file.parser.CsvStatementFileParser;
import com.positivity.accounting.internal.bankfeed.file.parser.StatementFileParsers;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportFileRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRowRepository;
import com.positivity.accounting.internal.bankrec.intake.BankIntakeLookup;
import com.positivity.accounting.internal.bankrec.intake.BankIntakeLookup.BankAccountTerms;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.BankTransactionIntake;
import com.positivity.accounting.internal.bankrec.intake.IntakeContext;
import com.positivity.accounting.internal.bankrec.intake.IntakeResult;
import com.positivity.accounting.internal.bankrec.intake.ReconciliationStarter;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The import lifecycle (SPEC-manual-bank-reconciliation §3.8, §4.2–§4.5, §6.3; story S3, #2302): the
 * state machine, the upload and commit check order, replay, the commit preconditions and the batch the
 * intake receives. Repositories are in-memory fakes; the intake and its lookup are mocks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankImportServiceImpl (#2302)")
class BankImportServiceImplTest {

    private static final UUID ACCOUNT = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-31T12:00:00Z"), ZoneOffset.UTC);
    private static final String ACK = "First statement reconciled on this account";
    private static final String CSV = "date,description,amount,reference\n2026-09-02,ACH DEPOSIT,500.00,DEP-1\n"
            + "2026-09-15,MONTHLY FEE,-15.00,\n";

    @Mock
    private BankImportRepository imports;

    @Mock
    private BankImportRowRepository rowRepository;

    @Mock
    private BankImportFileRepository files;

    @Mock
    private BankIntakeLookup lookup;

    @Mock
    private BankTransactionIntake intake;

    @Mock
    private BankImportAuditRecorder audit;

    @Mock
    private ReconciliationStarter reconciliationStarter;

    private BankImportServiceImpl service;
    private final Map<UUID, BankImport> importStore = new HashMap<>();
    private final Map<UUID, BankImportFile> fileStore = new HashMap<>();
    private final List<BankImportRow> rowStore = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new BankImportServiceImpl(
                imports,
                rowRepository,
                files,
                new StatementFileParsers(List.of(new CsvStatementFileParser())),
                lookup,
                intake,
                reconciliationStarter,
                audit,
                CLOCK,
                2555,
                1_000_000);
        lenient()
                .when(lookup.requireAccount(ACCOUNT))
                .thenReturn(new BankAccountTerms(ACCOUNT, "1000", "Cash", "USD", 2, null, false));
        lenient()
                .when(lookup.collidingFingerprints(eq(ACCOUNT), anyCollection()))
                .thenReturn(Map.of());
        lenient().when(lookup.accountDisplay(anyCollection())).thenReturn(Map.of());
        lenient().when(imports.saveAndFlush(any(BankImport.class))).thenAnswer(inv -> {
            BankImport i = inv.getArgument(0);
            if (i.getImportId() == null) {
                i.setImportId(nextId());
                i.setVersion(0L);
                i.setCreatedAt(Instant.now(CLOCK));
            } else {
                i.setVersion(i.getVersion() + 1);
            }
            importStore.put(i.getImportId(), i);
            return i;
        });
        lenient()
                .when(imports.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(importStore.get(inv.getArgument(0))));
        lenient()
                .when(imports.findByRequestId(any()))
                .thenAnswer(inv -> importStore.values().stream()
                        .filter(i -> inv.getArgument(0).equals(i.getRequestId()))
                        .findFirst());
        lenient()
                .when(imports.findFirstByGlAccountIdAndFileSha256AndStatus(any(), any(), any()))
                .thenAnswer(inv -> importStore.values().stream()
                        .filter(i ->
                                i.getFileSha256().equals(inv.getArgument(1)) && i.getStatus() == inv.getArgument(2))
                        .findFirst());
        lenient().when(files.save(any(BankImportFile.class))).thenAnswer(inv -> {
            BankImportFile f = inv.getArgument(0);
            fileStore.put(f.getImportId(), f);
            return f;
        });
        lenient().when(files.findById(any())).thenAnswer(inv -> Optional.ofNullable(fileStore.get(inv.getArgument(0))));
        lenient().when(rowRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<BankImportRow> rows = inv.getArgument(0);
            for (BankImportRow row : rows) {
                if (row.getRowId() == null) {
                    row.setRowId(nextId());
                }
                if (!rowStore.contains(row)) {
                    rowStore.add(row);
                }
            }
            return rows;
        });
        lenient()
                .when(rowRepository.findByImportIdOrderByRowNumberAsc(any()))
                .thenAnswer(inv -> rowStore.stream()
                        .filter(r -> r.getImportId().equals(inv.getArgument(0)))
                        .sorted(Comparator.comparing(BankImportRow::getRowNumber))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        lenient()
                .when(rowRepository.findByRowIdAndImportId(any(), any()))
                .thenAnswer(inv -> rowStore.stream()
                        .filter(r -> r.getRowId().equals(inv.getArgument(0)))
                        .findFirst());
        lenient()
                .doAnswer(inv -> {
                    Iterable<BankImportRow> gone = inv.getArgument(0);
                    gone.forEach(rowStore::remove);
                    return null;
                })
                .when(rowRepository)
                .deleteAll(any());
    }

    private UUID nextId() {
        return UUID.fromString(String.format("01990000-0000-7000-8000-%012d", ids.incrementAndGet()));
    }

    private static UUID requestId() {
        return com.positivity.shared.id.UUIDv7Generator.generate();
    }

    private static BankImportCreateRequest upload(String csv, String opening, String closing, String ack) {
        return BankImportCreateRequest.builder()
                .glAccountId(ACCOUNT)
                .requestId(requestId())
                .formatCode("CSV")
                .fileName("september.csv")
                .content(Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8)))
                .statement(BankImportStatementHeader.builder()
                        .startDate(LocalDate.of(2026, 9, 1))
                        .endDate(LocalDate.of(2026, 9, 30))
                        .openingBalance(new BigDecimal(opening))
                        .closingBalance(new BigDecimal(closing))
                        .build())
                .gapAcknowledgement(ack)
                .build();
    }

    private BankImportResponse validUpload() {
        return service.create(upload(CSV, "1000.00", "1485.00", ACK), null, null, null);
    }

    private static BankRecErrorCode codeOf(Throwable thrown) {
        return ((BankRecException) thrown).code();
    }

    private void acceptReturns(UUID statementId, int rows, int possibleDuplicates) {
        List<UUID> transactionIds = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            transactionIds.add(UUID.fromString(String.format("01980000-0000-7000-8000-%012d", 900 + i)));
        }
        when(intake.accept(any(), any()))
                .thenReturn(new IntakeResult(
                        statementId, transactionIds, rows, possibleDuplicates, 0, LocalDate.of(2026, 9, 1), true));
    }

    @Nested
    @DisplayName("upload (§4.3, §4.2)")
    class Upload {

        @Test
        void aGoodFileIsValidatedWithCountsPreviewAndTheRetainedBytes() {
            BankImportResponse response = validUpload();

            assertThat(response.getStatus()).isEqualTo(BankImportStatus.VALIDATED);
            assertThat(response.getMappingRequired()).isFalse();
            assertThat(response.getRowCount()).isEqualTo(2);
            assertThat(response.getAcceptedCount()).isEqualTo(2);
            assertThat(response.getColumns()).containsExactly("date", "description", "amount", "reference");
            assertThat(response.getPreview().getTies()).isTrue();
            assertThat(response.getPreview().getFirstRows()).hasSize(2);
            assertThat(response.getRetentionUntil())
                    .isEqualTo(LocalDate.of(2026, 12, 31).plusDays(2555));
            assertThat(response.getFileSha256()).hasSize(64);
            assertThat(fileStore.get(response.getImportId()).getFileBytes())
                    .isEqualTo(CSV.getBytes(StandardCharsets.UTF_8));
            verify(audit)
                    .record(
                            eq(response.getImportId()),
                            eq(BankImportAuditRecorder.BANK_IMPORT_CREATE),
                            any(),
                            eq(ACK),
                            isNull(),
                            any());
        }

        @Test
        void headerNamesThatDifferFromTheDefaultsLeaveItUploadedWithMappingRequired() {
            BankImportResponse response = service.create(
                    upload("Posted,Payee,Amt\n2026-09-02,DEP,500.00", "0", "500", ACK), null, null, null);

            assertThat(response.getStatus()).isEqualTo(BankImportStatus.UPLOADED);
            assertThat(response.getMappingRequired()).isTrue();
            assertThat(response.getColumns()).containsExactly("Posted", "Payee", "Amt");
            assertThat(response.getPreview()).isNull();
            assertThat(rowStore).singleElement().satisfies(row -> {
                assertThat(row.getRowStatus()).isEqualTo(BankImportRowStatus.REJECTED);
                assertThat(row.getRawValues()).containsEntry("Payee", "DEP");
            });
        }

        @Test
        void theChecksRunInOrderAccountCurrencyFileHeaderThenParse() {
            BankImportCreateRequest request = upload("\u0000binary", "0", "0", null);
            when(lookup.checkHeader(eq(ACCOUNT), any(), isNull()))
                    .thenThrow(new BankRecException(BankRecErrorCode.STATEMENT_NOT_CONTIGUOUS, "first"));

            assertThatThrownBy(() -> service.create(request, null, null, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.STATEMENT_NOT_CONTIGUOUS));
            InOrder order = inOrder(lookup, imports);
            order.verify(lookup).requireAccount(ACCOUNT);
            order.verify(imports).findFirstByGlAccountIdAndFileSha256AndStatus(any(), any(), any());
            order.verify(lookup).checkHeader(eq(ACCOUNT), any(), isNull());
            verify(imports, never()).saveAndFlush(any());
        }

        @Test
        void anUnreadableFileIs422AndNothingIsStored() {
            assertThatThrownBy(() -> service.create(upload("\u0000binary", "0", "0", ACK), null, null, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.STATEMENT_IMPORT_FAILED));
            verify(imports, never()).saveAndFlush(any());
            verify(files, never()).save(any());
        }

        @Test
        void aFileInAnotherCurrencyIs422() {
            BankImportCreateRequest request = upload(CSV, "0", "485", ACK);
            request.setCurrency("EUR");
            assertThatThrownBy(() -> service.create(request, null, null, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.CURRENCY_NOT_SUPPORTED));
        }

        @Test
        void theSameBytesCommittedBeforeAre409NamingTheEarlierImportAndStatement() {
            BankImportResponse first = validUpload();
            BankImport committed = importStore.get(first.getImportId());
            committed.setStatus(BankImportStatus.COMMITTED);
            committed.setStatementId(UUID.fromString("01980000-0000-7000-8000-000000000777"));

            assertThatThrownBy(() -> validUpload()).isInstanceOfSatisfying(BankRecException.class, e -> {
                assertThat(e.code()).isEqualTo(BankRecErrorCode.IMPORT_FILE_ALREADY_COMMITTED);
                assertThat(e.fieldErrors())
                        .containsEntry("importId", first.getImportId().toString())
                        .containsEntry("statementId", "01980000-0000-7000-8000-000000000777");
            });
        }

        @Test
        void aReplayReturnsTheOriginalAndADifferentPayloadIsAConflict() {
            BankImportCreateRequest request = upload(CSV, "1000.00", "1485.00", ACK);
            BankImportResponse first = service.create(request, null, null, null);

            BankImportResponse replay = service.create(request, null, null, null);
            assertThat(replay.getImportId()).isEqualTo(first.getImportId());
            assertThat(replay.getReplayed()).isTrue();
            verify(imports, times(1)).saveAndFlush(any());

            request.setGapAcknowledgement(ACK + " (changed)");
            assertThatThrownBy(() -> service.create(request, null, null, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.IDEMPOTENCY_CONFLICT));
        }

        @Test
        void theShapeIsCheckedFirstAndNamesEachField() {
            BankImportCreateRequest request = upload(CSV, "0", "0", ACK);
            request.setRequestId(UUID.randomUUID());
            request.setFormatCode(null);
            request.getStatement().setEndDate(LocalDate.of(2026, 8, 1));
            assertThatThrownBy(() -> service.create(request, null, null, null))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
                        assertThat(e.fieldErrors()).containsKeys("requestId", "formatCode", "statement.startDate");
                    });

            BankImportCreateRequest ofx = upload(CSV, "0", "0", ACK);
            ofx.setFormatCode("OFX");
            assertThatThrownBy(() -> service.create(ofx, null, null, null))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.fieldErrors()).containsKey("formatCode"));
        }

        @Test
        void splitPointsMustLieInsideTheWindow() {
            BankImportCreateRequest request = upload(CSV, "0", "0", ACK);
            request.setSplitAt(List.of(new BankImportSplitPoint(LocalDate.of(2026, 9, 30), BigDecimal.ONE)));
            assertThatThrownBy(() -> service.create(request, null, null, null))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.fieldErrors()).containsKey("splitAt[0].date"));
        }
    }

    @Test
    @DisplayName("the request hash is length-prefixed: a delimiter inside free text cannot collide two payloads")
    void theRequestHashCannotBeShiftedByADelimiterInFreeText() {
        com.positivity.accounting.internal.bankfeed.file.parser.ParserOptions options =
                com.positivity.accounting.internal.bankfeed.file.parser.ParserOptions.defaults();
        BankImportCreateRequest a = upload(CSV, "0", "0", "moved banks|in August");
        a.getStatement().setStatementRef("R");
        BankImportCreateRequest b = upload(CSV, "0", "0", "in August");
        b.setRequestId(a.getRequestId());
        b.getStatement().setStatementRef("R|moved banks");

        assertThat(BankImportServiceImpl.requestHash(a, "sha", options, null, List.of()))
                .isNotEqualTo(BankImportServiceImpl.requestHash(b, "sha", options, null, List.of()));
        assertThat(BankImportServiceImpl.requestHash(
                        a,
                        "sha",
                        options,
                        com.positivity.accounting.internal.bankfeed.file.parser.ColumnMapping.fromJson(
                                Map.of("date", 0), "m"),
                        List.of()))
                .isNotEqualTo(BankImportServiceImpl.requestHash(
                        a,
                        "sha",
                        options,
                        com.positivity.accounting.internal.bankfeed.file.parser.ColumnMapping.fromJson(
                                Map.of("date", "0"), "m"),
                        List.of()));
    }

    @Nested
    @DisplayName("state machine (§3.8)")
    class StateMachine {

        @Test
        void aCommittedImportRefusesMappingRowAndDiscardChanges() {
            UUID id = validUpload().getImportId();
            importStore.get(id).setStatus(BankImportStatus.COMMITTED);
            UUID rowId = rowStore.getFirst().getRowId();

            assertThatThrownBy(() -> service.updateMapping(
                            id,
                            BankImportMappingRequest.builder()
                                    .columnMapping(Map.of("date", 0))
                                    .build()))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.IMPORT_ALREADY_COMMITTED));
            assertThatThrownBy(() -> service.updateRow(
                            id,
                            rowId,
                            BankImportRowUpdateRequest.builder()
                                    .skip(true)
                                    .reason("0123456789")
                                    .build()))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.IMPORT_ALREADY_COMMITTED));
            assertThatThrownBy(() -> service.discard(id, new BankImportDiscardRequest("wrong file uploaded", null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.IMPORT_ALREADY_COMMITTED));
        }

        @Test
        void aDiscardedImportRefusesEveryChangeAndTheCommit() {
            UUID id = validUpload().getImportId();
            BankImportResponse discarded =
                    service.discard(id, new BankImportDiscardRequest("wrong file uploaded", null));
            assertThat(discarded.getStatus()).isEqualTo(BankImportStatus.DISCARDED);
            assertThat(discarded.getDiscardReason()).isEqualTo("wrong file uploaded");

            assertThatThrownBy(() -> service.commit(id, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.IMPORT_DISCARDED));
            assertThatThrownBy(() -> service.discard(id, new BankImportDiscardRequest("again, twice over", null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.IMPORT_DISCARDED));
            assertThatThrownBy(() -> service.updateMapping(
                            id,
                            BankImportMappingRequest.builder()
                                    .columnMapping(Map.of("date", 0))
                                    .build()))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.IMPORT_DISCARDED));
            verify(intake, never()).accept(any(), any());
        }

        @Test
        void aDiscardReasonShorterThanTenCharactersIs400() {
            UUID id = validUpload().getImportId();
            assertThatThrownBy(() -> service.discard(id, new BankImportDiscardRequest("oops", null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
        }

        @Test
        void aStaleVersionIs409OptimisticLock() {
            UUID id = validUpload().getImportId();
            assertThatThrownBy(() -> service.discard(id, new BankImportDiscardRequest("wrong file uploaded", 99L)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.OPTIMISTIC_LOCK));
        }

        @Test
        void anUnknownImportIs404() {
            assertThatThrownBy(() -> service.get(UUID.fromString("01990000-0000-7000-8000-00000000ffff")))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.BANK_IMPORT_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("mapping and rows (§4.4)")
    class MappingAndRows {

        @Test
        void aMappingReparsesToValidatedAndSavesTheDefaultWhenAsked() {
            UUID id = service.create(
                            upload("Posted,Payee,Amt\n2026-09-02,DEP,500.00", "0", "500", ACK), null, null, null)
                    .getImportId();

            BankImportResponse mapped = service.updateMapping(
                    id,
                    BankImportMappingRequest.builder()
                            .columnMapping(Map.of("date", "Posted", "description", "Payee", "amount", "Amt"))
                            .saveAsAccountDefault(true)
                            .build());

            assertThat(mapped.getStatus()).isEqualTo(BankImportStatus.VALIDATED);
            assertThat(mapped.getAcceptedCount()).isEqualTo(1);
            assertThat(mapped.getSaveMappingAsDefault()).isTrue();
            assertThat(mapped.getPreview().getTies()).isTrue();
            verify(lookup)
                    .saveDefaultColumnMapping(
                            eq(ACCOUNT), eq(Map.of("date", "Posted", "description", "Payee", "amount", "Amt")), any());
        }

        @Test
        void aLaterMappingWithoutTheOptInResetsSaveAsAccountDefault() {
            UUID id = service.create(
                            upload("Posted,Payee,Amt\n2026-09-02,DEP,500.00", "0", "500", ACK), null, null, null)
                    .getImportId();
            Map<String, Object> columns = Map.of("date", "Posted", "description", "Payee", "amount", "Amt");
            service.updateMapping(
                    id,
                    BankImportMappingRequest.builder()
                            .columnMapping(columns)
                            .saveAsAccountDefault(true)
                            .build());
            assertThat(service.get(id).getSaveMappingAsDefault()).isTrue();

            BankImportResponse again = service.updateMapping(
                    id,
                    BankImportMappingRequest.builder().columnMapping(columns).build());

            assertThat(again.getSaveMappingAsDefault()).isFalse();
            assertThat(service.get(id).getSaveMappingAsDefault()).isFalse();
            verify(lookup, times(1)).saveDefaultColumnMapping(eq(ACCOUNT), eq(columns), any());
        }

        @Test
        void aHeaderCorrectionThatMakesTheStatementContiguousCanDropTheAcknowledgement() {
            UUID id = validUpload().getImportId();
            assertThat(importStore.get(id).getGapAcknowledgement()).isEqualTo(ACK);
            BankImportStatementHeader contiguous = BankImportStatementHeader.builder()
                    .startDate(LocalDate.of(2026, 9, 1))
                    .endDate(LocalDate.of(2026, 9, 30))
                    .openingBalance(new BigDecimal("1000.00"))
                    .closingBalance(new BigDecimal("1485.00"))
                    .build();

            service.updateMapping(
                    id,
                    BankImportMappingRequest.builder()
                            .columnMapping(Map.of("date", "date", "description", "description", "amount", "amount"))
                            .statement(contiguous)
                            .build());

            // A corrected header states its own acknowledgement: absent means none, not the stored one.
            verify(lookup).checkHeader(eq(ACCOUNT), any(), isNull());
            assertThat(importStore.get(id).getGapAcknowledgement()).isNull();
        }

        @Test
        void aHeaderCorrectionThatOpensAGapCarriesANewAcknowledgement() {
            UUID id = service.create(upload(CSV, "1000.00", "1485.00", null), null, null, null)
                    .getImportId();
            String ack = "The bank merged two accounts in August";

            service.updateMapping(
                    id,
                    BankImportMappingRequest.builder()
                            .columnMapping(Map.of("date", "date", "description", "description", "amount", "amount"))
                            .gapAcknowledgement("  " + ack + " ")
                            .build());

            verify(lookup).checkHeader(eq(ACCOUNT), any(), eq("  " + ack + " "));
            assertThat(importStore.get(id).getGapAcknowledgement()).isEqualTo(ack);
        }

        @Test
        void aMappingAcknowledgementIsValidatedLikeTheUploadOne() {
            UUID id = validUpload().getImportId();
            assertThatThrownBy(() -> service.updateMapping(
                            id,
                            BankImportMappingRequest.builder()
                                    .columnMapping(
                                            Map.of("date", "date", "description", "description", "amount", "amount"))
                                    .gapAcknowledgement("x".repeat(1001))
                                    .build()))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.fieldErrors()).containsKey("gapAcknowledgement"));
            assertThat(importStore.get(id).getGapAcknowledgement()).isEqualTo(ACK);
        }

        @Test
        void aMappingMayChangeTheGapAcknowledgementTheCorrectedHeaderIsCheckedWith() {
            UUID id = validUpload().getImportId();
            assertThat(service.get(id).getGapAcknowledgement()).isEqualTo(ACK);
            Map<String, Object> columns = Map.of("date", "date", "description", "description", "amount", "amount");
            BankImportStatementHeader widened = BankImportStatementHeader.builder()
                    .startDate(LocalDate.of(2026, 8, 1))
                    .endDate(LocalDate.of(2026, 9, 30))
                    .openingBalance(new BigDecimal("1000.00"))
                    .closingBalance(new BigDecimal("1485.00"))
                    .build();

            String changed = "Earlier history reconciled outside the system";

            // A changed acknowledgement is what the corrected header is rechecked with, and it is stored.
            service.updateMapping(
                    id,
                    BankImportMappingRequest.builder()
                            .columnMapping(columns)
                            .statement(widened)
                            .gapAcknowledgement(changed)
                            .build());
            verify(lookup).checkHeader(eq(ACCOUNT), any(), eq(changed));
            assertThat(service.get(id).getGapAcknowledgement()).isEqualTo(changed);
            assertThat(importStore.get(id).getStatementStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));

            // Absent with no corrected header keeps it.
            service.updateMapping(
                    id,
                    BankImportMappingRequest.builder().columnMapping(columns).build());
            assertThat(service.get(id).getGapAcknowledgement()).isEqualTo(changed);
        }

        @Test
        void theWrongSignConventionDoesNotTieAndSwitchingItDoes() {
            // §8.2: a bank that shows withdrawals positive.
            UUID id = service.create(
                            upload(
                                    "date,description,amount\n2026-09-02,WITHDRAWAL,40.00\n2026-09-03,DEPOSIT,-100.00",
                                    "1000",
                                    "1060",
                                    ACK),
                            null,
                            null,
                            null)
                    .getImportId();
            assertThat(service.get(id).getPreview().getTies()).isFalse();

            BankImportResponse inverted = service.updateMapping(
                    id,
                    BankImportMappingRequest.builder()
                            .columnMapping(Map.of("date", "date", "description", "description", "amount", "amount"))
                            .signConvention("SIGNED_AMOUNT_INVERTED")
                            .build());
            assertThat(inverted.getPreview().getTies()).isTrue();
            assertThat(rowStore)
                    .extracting(BankImportRow::getSignedAmount)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(new BigDecimal("-40"), new BigDecimal("100"));
        }

        @Test
        void aCorrectionKeepsTheRawValuesAndMakesTheRowCorrected() {
            UUID id = service.create(
                            upload("date,description,amount\n2026-13-45,DEP,500.00", "0", "500", ACK), null, null, null)
                    .getImportId();
            BankImportRow rejected = rowStore.getFirst();
            assertThat(rejected.getRowStatus()).isEqualTo(BankImportRowStatus.REJECTED);

            BankImportRowResponse corrected = service.updateRow(
                    id,
                    rejected.getRowId(),
                    BankImportRowUpdateRequest.builder()
                            .correctedValues(Map.of("date", "2026-09-14"))
                            .build());

            assertThat(corrected.getRowStatus()).isEqualTo(BankImportRowStatus.CORRECTED);
            assertThat(corrected.getDate()).isEqualTo(LocalDate.of(2026, 9, 14));
            assertThat(corrected.getRawValues()).containsEntry("date", "2026-13-45");
            assertThat(corrected.getCorrectedValues()).containsEntry("date", "2026-09-14");
            assertThat(corrected.getRejectionCode()).isNull();
            assertThat(importStore.get(id).getRejectedCount()).isZero();
        }

        @Test
        void aCorrectionDropsAnEarlierDistinctDecisionSoANewCollisionIsFlaggedAgain() {
            UUID id = service.create(
                            upload(
                                    "date,description,amount\n2026-09-02,DEP,500.00\n2026-09-02,DEP,500.00\n"
                                            + "2026-09-15,FEE,-15.00",
                                    "1000",
                                    "1985",
                                    ACK),
                            null,
                            null,
                            null)
                    .getImportId();
            List<BankImportRow> rows = rowRepository.findByImportIdOrderByRowNumberAsc(id);
            assertThat(rows)
                    .extracting(BankImportRow::getRowStatus)
                    .containsExactly(
                            BankImportRowStatus.POSSIBLE_DUPLICATE,
                            BankImportRowStatus.POSSIBLE_DUPLICATE,
                            BankImportRowStatus.PARSED);
            BankImportRow first = rows.get(0);

            service.updateRow(
                    id,
                    first.getRowId(),
                    BankImportRowUpdateRequest.builder()
                            .duplicateDecision("DISTINCT")
                            .build());
            assertThat(first.getRowStatus()).isEqualTo(BankImportRowStatus.PARSED);

            // Corrected to the values of row 3: the decision taken on the old values no longer covers it.
            BankImportRowResponse corrected = service.updateRow(
                    id,
                    first.getRowId(),
                    BankImportRowUpdateRequest.builder()
                            .correctedValues(
                                    Map.of("date", "2026-09-15", "description", "FEE", "signedAmount", "-15.00"))
                            .build());

            assertThat(corrected.getRowStatus()).isEqualTo(BankImportRowStatus.POSSIBLE_DUPLICATE);
            assertThat(corrected.getDuplicateDecision()).isNull();
            assertThat(corrected.getDuplicateOfRowNumber()).isEqualTo(3);
        }

        @Test
        void aRowUpdateNeedsExactlyOneActionAndAReasonedSkip() {
            UUID id = validUpload().getImportId();
            UUID rowId = rowStore.getFirst().getRowId();
            assertThatThrownBy(() -> service.updateRow(id, rowId, new BankImportRowUpdateRequest()))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.VALIDATION_ERROR));
            assertThatThrownBy(() -> service.updateRow(
                            id,
                            rowId,
                            BankImportRowUpdateRequest.builder()
                                    .skip(true)
                                    .reason("short")
                                    .build()))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));

            BankImportRowResponse skipped = service.updateRow(
                    id,
                    rowId,
                    BankImportRowUpdateRequest.builder()
                            .skip(true)
                            .reason("already on August")
                            .build());
            assertThat(skipped.getRowStatus()).isEqualTo(BankImportRowStatus.SKIPPED);
            assertThat(skipped.getSkipReason()).isEqualTo("already on August");
        }

        @Test
        void aCorrectionDropsAnEarlierDistinctDecisionSoTheNewFingerprintIsReviewedAgain() {
            // Every fingerprint collides: the row is flagged, confirmed distinct, then corrected.
            when(lookup.collidingFingerprints(eq(ACCOUNT), anyCollection())).thenAnswer(inv -> {
                Map<String, UUID> colliding = new HashMap<>();
                for (Object fingerprint : inv.<java.util.Collection<?>>getArgument(1)) {
                    colliding.put((String) fingerprint, UUID.fromString("01980000-0000-7000-8000-000000000777"));
                }
                return colliding;
            });
            UUID id = validUpload().getImportId();
            BankImportRow row = rowStore.getFirst();
            assertThat(row.getRowStatus()).isEqualTo(BankImportRowStatus.POSSIBLE_DUPLICATE);
            service.updateRow(
                    id,
                    row.getRowId(),
                    BankImportRowUpdateRequest.builder()
                            .duplicateDecision("DISTINCT")
                            .build());
            assertThat(row.getRowStatus()).isEqualTo(BankImportRowStatus.PARSED);

            BankImportRowResponse corrected = service.updateRow(
                    id,
                    row.getRowId(),
                    BankImportRowUpdateRequest.builder()
                            .correctedValues(Map.of("description", "ACH DEPOSIT PAYROLL"))
                            .build());

            assertThat(corrected.getRowStatus()).isEqualTo(BankImportRowStatus.POSSIBLE_DUPLICATE);
            assertThat(row.getDuplicateDecision()).isNull();
        }

        @Test
        void aDuplicateDecisionOnlyAppliesToAPossibleDuplicate() {
            UUID id = validUpload().getImportId();
            UUID rowId = rowStore.getFirst().getRowId();
            assertThatThrownBy(() -> service.updateRow(
                            id,
                            rowId,
                            BankImportRowUpdateRequest.builder()
                                    .duplicateDecision("DISTINCT")
                                    .build()))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.fieldErrors()).containsKey("duplicateDecision"));
        }
    }

    @Nested
    @DisplayName("commit (§4.4)")
    class Commit {

        @Test
        void aCommitHandsOneFileImportBatchToTheIntakeAndMarksEveryRow() {
            UUID id = validUpload().getImportId();
            UUID statementId = UUID.fromString("01980000-0000-7000-8000-000000000500");
            acceptReturns(statementId, 2, 0);

            BankImportCommitResponse result = service.commit(id, null);

            ArgumentCaptor<BankTransactionsObservedV1> batch =
                    ArgumentCaptor.forClass(BankTransactionsObservedV1.class);
            ArgumentCaptor<IntakeContext> ctx = ArgumentCaptor.forClass(IntakeContext.class);
            verify(intake).accept(batch.capture(), ctx.capture());
            assertThat(batch.getValue().sourceKind()).isEqualTo(BankTransactionsObservedV1.SourceKind.FILE_IMPORT);
            assertThat(batch.getValue().connectorCode()).isEqualTo("csv-v1");
            assertThat(batch.getValue().feedAccountId()).isNull();
            assertThat(batch.getValue().feedAccountRef()).isNull();
            assertThat(batch.getValue().currency()).isEqualTo("USD");
            assertThat(batch.getValue().statement().openingBalance()).isEqualByComparingTo("1000.00");
            assertThat(batch.getValue().transactions())
                    .extracting(BankTransactionsObservedV1.BankTransactionObserved::sourceRowNumber)
                    .containsExactly(1, 2);
            assertThat(batch.getValue().transactions().getFirst().signedAmount())
                    .isEqualByComparingTo("500");
            assertThat(ctx.getValue().sourceRef()).isEqualTo(id);
            assertThat(ctx.getValue().gapAcknowledgement()).isEqualTo(ACK);

            assertThat(result.getStatementId()).isEqualTo(statementId);
            assertThat(result.getStatementIds()).containsExactly(statementId);
            assertThat(result.getBankTransactionCount()).isEqualTo(2);
            assertThat(rowStore).allMatch(r -> r.getRowStatus() == BankImportRowStatus.COMMITTED);
            assertThat(rowStore.getFirst().getBankTransactionId()).isNotNull();
            assertThat(importStore.get(id).getStatus()).isEqualTo(BankImportStatus.COMMITTED);
            verify(audit)
                    .record(eq(id), eq(BankImportAuditRecorder.BANK_IMPORT_COMMIT), any(), eq(ACK), isNull(), any());
        }

        @Test
        void startReconciliationStartsOneOfTheCommittedStatementAndReturnsIt() {
            UUID id = validUpload().getImportId();
            UUID statementId = UUID.fromString("01980000-0000-7000-8000-000000000500");
            UUID reconciliationId = UUID.fromString("01980000-0000-7000-8000-000000000600");
            acceptReturns(statementId, 2, 0);
            UUID requestId = UUID.nameUUIDFromBytes(
                    ("BANK_IMPORT_RECONCILIATION:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            when(reconciliationStarter.start(ACCOUNT, statementId, requestId)).thenReturn(reconciliationId);

            BankImportCommitResponse result = service.commit(
                    id,
                    BankImportCommitRequest.builder().startReconciliation(true).build());

            assertThat(result.getReconciliationId()).isEqualTo(reconciliationId);
            assertThat(importStore.get(id).getReconciliationId()).isEqualTo(reconciliationId);
            assertThat(service.commit(id, null).getReconciliationId())
                    .as("a repeated commit answers the same reconciliation")
                    .isEqualTo(reconciliationId);
            verify(reconciliationStarter, times(1)).start(any(), any(), any());
        }

        @Test
        void aCommitWithoutTheFlagStartsNothing() {
            UUID id = validUpload().getImportId();
            acceptReturns(UUID.fromString("01980000-0000-7000-8000-000000000500"), 2, 0);
            assertThat(service.commit(id, null).getReconciliationId()).isNull();
            verify(reconciliationStarter, never()).start(any(), any(), any());
        }

        @Test
        void aSecondCommitAnswersTheSameResultWithoutTheIntake() {
            UUID id = validUpload().getImportId();
            acceptReturns(UUID.fromString("01980000-0000-7000-8000-000000000500"), 2, 0);
            BankImportCommitResponse first = service.commit(id, null);

            BankImportCommitResponse second = service.commit(id, null);

            assertThat(second).isEqualTo(first);
            verify(intake, times(1)).accept(any(), any());
        }

        @Test
        void rejectedRowsAndAnE1FailureAre422NotCommittable() {
            UUID id = service.create(
                            upload(
                                    "date,description,amount\n2026-13-45,BAD,1.00\n2026-09-02,DEP,985.00",
                                    "9000",
                                    "10000",
                                    ACK),
                            null,
                            null,
                            null)
                    .getImportId();

            assertThatThrownBy(() -> service.commit(id, null)).isInstanceOfSatisfying(BankRecException.class, e -> {
                assertThat(e.code()).isEqualTo(BankRecErrorCode.IMPORT_NOT_COMMITTABLE);
                assertThat(e.fieldErrors())
                        .containsKey("rows[1]")
                        .containsEntry("activityTotal", "opening + activity = 9985.00, closing = 10000.00");
            });
            verify(intake, never()).accept(any(), any());
            // The preconditions answer before the header is checked again.
            verify(lookup, times(1)).checkHeader(any(), any(), any());
        }

        @Test
        void anUploadedImportIsNotCommittable() {
            UUID id = service.create(
                            upload("Posted,Payee,Amt\n2026-09-02,DEP,500.00", "0", "500", ACK), null, null, null)
                    .getImportId();
            assertThatThrownBy(() -> service.commit(id, null)).isInstanceOfSatisfying(BankRecException.class, e -> {
                assertThat(e.code()).isEqualTo(BankRecErrorCode.IMPORT_NOT_COMMITTABLE);
                assertThat(e.fieldErrors()).containsKey("columnMapping");
            });
        }

        @Test
        void theHeaderChecksRunAgainAtCommitAndAnswerTheirOwnCode() {
            UUID id = validUpload().getImportId();
            // A statement committed since upload now makes this one contiguous (§4.2).
            when(lookup.checkHeader(eq(ACCOUNT), any(), eq(ACK)))
                    .thenThrow(new BankRecException(
                            BankRecErrorCode.STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE, "now contiguous"));

            assertThatThrownBy(() -> service.commit(id, null))
                    .satisfies(e -> assertThat(codeOf(e))
                            .isEqualTo(BankRecErrorCode.STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE));
            verify(intake, never()).accept(any(), any());
        }

        @Test
        void distinctDecisionsReachTheIntakeAndDuplicateDecisionsSkipTheRow() {
            // §8.2: two identical $5.00 fees in one file, then a third.
            UUID id = service.create(
                            upload(
                                    "date,description,amount\n2026-09-02,FEE,-5.00\n2026-09-02,FEE,-5.00\n"
                                            + "2026-09-02,FEE,-5.00",
                                    "20",
                                    "10",
                                    ACK),
                            null,
                            null,
                            null)
                    .getImportId();
            assertThat(importStore.get(id).getPossibleDuplicateCount()).isEqualTo(3);
            acceptReturns(UUID.fromString("01980000-0000-7000-8000-000000000500"), 2, 0);

            service.commit(
                    id,
                    BankImportCommitRequest.builder()
                            .duplicateDecisions(List.of(
                                    new BankImportCommitRequest.DuplicateDecision(1, "DISTINCT"),
                                    new BankImportCommitRequest.DuplicateDecision(2, "DISTINCT"),
                                    new BankImportCommitRequest.DuplicateDecision(3, "DUPLICATE")))
                            .build());

            ArgumentCaptor<BankTransactionsObservedV1> batch =
                    ArgumentCaptor.forClass(BankTransactionsObservedV1.class);
            ArgumentCaptor<IntakeContext> ctx = ArgumentCaptor.forClass(IntakeContext.class);
            verify(intake).accept(batch.capture(), ctx.capture());
            assertThat(batch.getValue().transactions()).hasSize(2);
            assertThat(ctx.getValue().confirmedDistinctRows()).isEqualTo(Set.of(1, 2));
            assertThat(rowStore.get(2).getRowStatus()).isEqualTo(BankImportRowStatus.SKIPPED);
        }

        @Test
        void aSplitFileCommitsOneStatementPerSegmentWithTheAcknowledgementOnTheFirstOnly() {
            BankImportCreateRequest request =
                    upload("date,description,amount\n2026-09-05,A,100\n2026-09-25,B,50", "1000", "1150", ACK);
            request.setSplitAt(List.of(new BankImportSplitPoint(LocalDate.of(2026, 9, 15), new BigDecimal("1100"))));
            UUID id = service.create(request, null, null, null).getImportId();
            UUID first = UUID.fromString("01980000-0000-7000-8000-000000000501");
            UUID second = UUID.fromString("01980000-0000-7000-8000-000000000502");
            when(intake.accept(any(), any()))
                    .thenReturn(
                            new IntakeResult(first, List.of(UUID.randomUUID()), 1, 0, 0, null, true),
                            new IntakeResult(second, List.of(UUID.randomUUID()), 1, 0, 0, null, false));

            BankImportCommitResponse result = service.commit(id, null);

            ArgumentCaptor<BankTransactionsObservedV1> batch =
                    ArgumentCaptor.forClass(BankTransactionsObservedV1.class);
            ArgumentCaptor<IntakeContext> ctx = ArgumentCaptor.forClass(IntakeContext.class);
            verify(intake, times(2)).accept(batch.capture(), ctx.capture());
            assertThat(batch.getAllValues().get(0).statement().endDate()).isEqualTo(LocalDate.of(2026, 9, 15));
            assertThat(batch.getAllValues().get(0).statement().closingBalance()).isEqualByComparingTo("1100");
            assertThat(batch.getAllValues().get(1).statement().startDate()).isEqualTo(LocalDate.of(2026, 9, 16));
            assertThat(batch.getAllValues().get(1).statement().openingBalance()).isEqualByComparingTo("1100");
            assertThat(ctx.getAllValues().get(0).gapAcknowledgement()).isEqualTo(ACK);
            assertThat(ctx.getAllValues().get(1).gapAcknowledgement()).isNull();
            assertThat(result.getStatementIds()).containsExactly(first, second);
            assertThat(result.getStatementId()).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("download (§6.4)")
    class Download {

        @Test
        void theRetainedBytesAreReturnedAndAudited() {
            UUID id = validUpload().getImportId();
            BankImportService.ImportFile file = service.download(id);
            assertThat(file.content()).isEqualTo(CSV.getBytes(StandardCharsets.UTF_8));
            assertThat(file.fileName()).isEqualTo("september.csv");
            assertThat(file.contentType()).isEqualTo("text/csv");
            verify(audit)
                    .record(
                            eq(id),
                            eq(BankImportAuditRecorder.BANK_IMPORT_FILE_READ),
                            any(),
                            isNull(),
                            isNull(),
                            any());
        }

        @Test
        void aPurgedFileIs404NamingTheRetentionDate() {
            UUID id = validUpload().getImportId();
            fileStore.clear();
            assertThatThrownBy(() -> service.download(id)).isInstanceOfSatisfying(BankRecException.class, e -> {
                assertThat(e.code()).isEqualTo(BankRecErrorCode.BANK_IMPORT_FILE_NOT_FOUND);
                assertThat(e.fieldErrors()).containsKey("retentionUntil");
            });
        }
    }
}
