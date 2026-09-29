package com.positivity.accounting.internal.bankfeed.file.service;

import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCreateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportDiscardRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportListResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportMappingRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowListResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowUpdateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportSplitPoint;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportStatementHeader;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImport;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImportFile;
import com.positivity.accounting.internal.bankfeed.file.entity.BankImportRow;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import com.positivity.accounting.internal.bankfeed.file.parser.ColumnMapping;
import com.positivity.accounting.internal.bankfeed.file.parser.ParsedFile;
import com.positivity.accounting.internal.bankfeed.file.parser.ParserOptions;
import com.positivity.accounting.internal.bankfeed.file.parser.RejectionCode;
import com.positivity.accounting.internal.bankfeed.file.parser.StatementFileParser;
import com.positivity.accounting.internal.bankfeed.file.parser.StatementFileParsers;
import com.positivity.accounting.internal.bankfeed.file.parser.StatementValues;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportFileRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRowRepository;
import com.positivity.accounting.internal.bankfeed.file.service.ImportEvaluator.Segment;
import com.positivity.accounting.internal.bankfeed.file.service.ImportEvaluator.SegmentTotal;
import com.positivity.accounting.internal.bankfeed.file.service.ImportEvaluator.SplitPoint;
import com.positivity.accounting.internal.bankrec.intake.BankIntakeLookup;
import com.positivity.accounting.internal.bankrec.intake.BankIntakeLookup.AccountDisplay;
import com.positivity.accounting.internal.bankrec.intake.BankIntakeLookup.BankAccountTerms;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.BankTransactionIntake;
import com.positivity.accounting.internal.bankrec.intake.ConcurrentCommitException;
import com.positivity.accounting.internal.bankrec.intake.IntakeContext;
import com.positivity.accounting.internal.bankrec.intake.IntakeResult;
import com.positivity.accounting.internal.bankrec.intake.Justification;
import com.positivity.accounting.internal.bankrec.intake.TransactionNormalizer;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.BankTransactionObserved;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.Change;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.SettlementState;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.StatementHeader;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statement-file import lifecycle (SPEC-manual-bank-reconciliation §3.3, §3.8, §4.2–§4.5, §5.7,
 * §6.1, §6.3, §6.4; story S3, #2302).
 *
 * <p>Nothing here is accounting truth until {@link #commit}: the file is staged in {@code bank_import},
 * its bytes in {@code bank_import_file} and its rows in {@code bank_import_row}. The commit builds one
 * {@link BankTransactionsObservedV1} per statement segment and calls {@link BankTransactionIntake#accept}
 * in this transaction, so the statement, its transactions, the baseline move and the committed fact
 * land together or not at all. The adapter reaches the core only through the intake port and its
 * {@link BankIntakeLookup}.
 */
@Slf4j
@Service
@Transactional
public class BankImportServiceImpl implements BankImportService {

    private static final String SYSTEM = "SYSTEM";
    private static final String DEFAULT_CONTENT_TYPE = "text/csv";
    private static final String REQUEST_CONSTRAINT = "bank_import_request_uk";
    private static final String COMMITTED_FILE_CONSTRAINT = "bank_import_committed_file_uk";
    private static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> CORRECTABLE =
            Set.of("date", "signedAmount", "description", "reference", "checkNumber", "sourceTransactionId");

    private final BankImportRepository imports;
    private final BankImportRowRepository rowRepository;
    private final BankImportFileRepository files;
    private final StatementFileParsers parsers;
    private final BankIntakeLookup lookup;
    private final BankTransactionIntake intake;
    private final BankImportAuditRecorder audit;
    private final Clock clock;
    private final int retentionDays;
    private final long maxFileBytes;

    public BankImportServiceImpl(
            BankImportRepository imports,
            BankImportRowRepository rowRepository,
            BankImportFileRepository files,
            StatementFileParsers parsers,
            BankIntakeLookup lookup,
            BankTransactionIntake intake,
            BankImportAuditRecorder audit,
            Clock clock,
            @Value("${pos.accounting.bankrec.import.file-retention-days:2555}") int retentionDays,
            @Value("${pos.accounting.bankrec.import.max-file-bytes:10485760}") long maxFileBytes) {
        this.imports = imports;
        this.rowRepository = rowRepository;
        this.files = files;
        this.parsers = parsers;
        this.lookup = lookup;
        this.intake = intake;
        this.audit = audit;
        this.clock = clock;
        this.retentionDays = retentionDays;
        this.maxFileBytes = maxFileBytes;
    }

    // ---- upload --------------------------------------------------------------------------------

    @Override
    public @NonNull BankImportResponse create(
            @NonNull BankImportCreateRequest request,
            byte @Nullable [] fileBytes,
            @Nullable String fileName,
            @Nullable String contentType) {
        byte[] content = fileBytes != null ? fileBytes : decodeContent(request.getContent());
        validateCreate(request, content);
        ParserOptions options = ParserOptions.of(
                request.getEncoding(),
                request.getDelimiter(),
                request.getDateFormat(),
                request.getDecimalFormat(),
                request.getSignConvention());
        StatementFileParser parser = parsers.require(request.getFormatCode());
        ColumnMapping requested = request.getColumnMapping() == null
                ? null
                : ColumnMapping.fromJson(request.getColumnMapping(), "columnMapping");
        BankImportStatementHeader header = request.getStatement();
        List<SplitPoint> split = splitPoints(request.getSplitAt(), header);
        String sha256 = sha256(content);
        String requestHash = requestHash(request, sha256, options, requested, split);

        var earlier = imports.findByRequestId(request.getRequestId());
        if (earlier.isPresent()) {
            if (!requestHash.equals(earlier.get().getRequestHash())) {
                throw new BankRecException(
                        BankRecErrorCode.IDEMPOTENCY_CONFLICT,
                        "requestId " + request.getRequestId() + " was already used with a different payload");
            }
            BankImport original = earlier.get();
            return view(original, rowRepository.findByImportIdOrderByRowNumberAsc(original.getImportId()), true)
                    .replayed(true)
                    .build();
        }

        // Account (D5), currency (D18), the whole-file duplicate (§4.3), then the header against the
        // COMMITTED statements (§4.2) — all before the rows are read, so the preparer learns early.
        UUID glAccountId = request.getGlAccountId();
        BankAccountTerms terms = lookup.requireAccount(glAccountId);
        String currency = request.getCurrency() == null
                ? terms.currency()
                : request.getCurrency().trim().toUpperCase(Locale.ROOT);
        requireCurrency(currency, terms);
        requireFileNotCommitted(glAccountId, sha256);
        lookup.checkHeader(glAccountId, statementHeader(header), request.getGapAcknowledgement());

        ColumnMapping mapping = requested != null ? requested : savedMapping(terms);
        ParsedFile parsed = parser.parse(content, options, mapping);

        String actor = currentActor();
        BankImport created = new BankImport();
        created.setRequestId(request.getRequestId());
        created.setRequestHash(requestHash);
        created.setGlAccountId(glAccountId);
        created.setCurrency(currency);
        created.setFormatCode(parser.formatCode());
        created.setFileName(fileName(request.getFileName(), fileName));
        created.setContentType(contentType(request.getContentType(), contentType));
        created.setFileSize((long) content.length);
        created.setFileSha256(sha256);
        applyHeader(created, header);
        created.setSplitAt(toJson(split));
        created.setGapAcknowledgement(
                request.getGapAcknowledgement() == null
                        ? null
                        : request.getGapAcknowledgement().trim());
        applyOptions(created, options, parsed);
        created.setCreatedBy(actor);
        created.setRetentionUntil(LocalDate.now(clock).plusDays(retentionDays));
        created.setStatus(parsed.mappingResolved() ? BankImportStatus.VALIDATED : BankImportStatus.UPLOADED);
        created.setUpdatedAt(Instant.now(clock));
        BankImport saved;
        try {
            saved = imports.saveAndFlush(created);
        } catch (DataIntegrityViolationException refused) {
            if (constraintDetail(refused).contains(REQUEST_CONSTRAINT)) {
                // Retried once in a fresh transaction by RetryingBankImportService: replay or conflict.
                throw new ConcurrentCommitException(
                        BankRecErrorCode.IDEMPOTENCY_CONFLICT, "The requestId was used by a concurrent request");
            }
            throw refused;
        }

        BankImportFile file = new BankImportFile(saved.getImportId());
        file.setFileBytes(content);
        file.setRetentionUntil(saved.getRetentionUntil());
        files.save(file);

        List<BankImportRow> rows = ImportEvaluator.fromParse(saved.getImportId(), parsed.rows());
        if (parsed.mappingResolved()) {
            evaluate(saved, rows);
        }
        rowRepository.saveAll(rows);
        applyCounts(saved, rows);

        audit.record(
                saved.getImportId(),
                BankImportAuditRecorder.BANK_IMPORT_CREATE,
                actor,
                saved.getGapAcknowledgement(),
                null,
                "glAccountId=" + glAccountId + ", file=" + saved.getFileName() + ", sha256=" + sha256 + ", window="
                        + saved.getStatementStartDate() + ".." + saved.getStatementEndDate() + ", rows="
                        + rows.size() + ", status=" + saved.getStatus());
        log.info(
                "Bank import {} uploaded for account {} ({} rows, status {})",
                saved.getImportId(),
                glAccountId,
                rows.size(),
                saved.getStatus());
        return view(saved, rows, true).build();
    }

    // ---- reads ---------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public @NonNull BankImportListResponse list(
            @Nullable UUID glAccountId, @Nullable BankImportStatus status, int page, int size) {
        Specification<BankImport> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (glAccountId != null) {
                predicates.add(cb.equal(root.get("glAccountId"), glAccountId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<BankImport> result =
                imports.findAll(spec, page(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "importId")));
        Map<UUID, AccountDisplay> accounts = lookup.accountDisplay(
                result.getContent().stream().map(BankImport::getGlAccountId).toList());
        List<BankImportResponse> content = result.getContent().stream()
                .map(i -> describe(i, accounts.get(i.getGlAccountId())).build())
                .toList();
        return new BankImportListResponse(
                content, result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull BankImportResponse get(@NonNull UUID importId) {
        BankImport found = requireImport(importId);
        return view(found, rowRepository.findByImportIdOrderByRowNumberAsc(importId), true)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull BankImportRowListResponse rows(
            @NonNull UUID importId, @Nullable BankImportRowStatus status, int page, int size) {
        BankImport found = requireImport(importId);
        Pageable pageable = page(page, size, Sort.by("rowNumber"));
        Page<BankImportRow> result = status == null
                ? rowRepository.findByImportId(importId, pageable)
                : rowRepository.findByImportIdAndRowStatus(importId, status, pageable);
        return new BankImportRowListResponse(
                found.getSourceColumns() == null ? List.of() : found.getSourceColumns(),
                result.getContent().stream().map(BankImportServiceImpl::rowView).toList(),
                result.getTotalElements(),
                result.getNumber(),
                result.getSize(),
                result.getTotalPages());
    }

    // ---- mapping -------------------------------------------------------------------------------

    @Override
    public @NonNull BankImportResponse updateMapping(
            @NonNull UUID importId, @NonNull BankImportMappingRequest request) {
        BankImport found = requireMutable(importId, request.getVersion());
        if (request.getColumnMapping() == null) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "columnMapping is required", "columnMapping", "is required");
        }
        ColumnMapping mapping = ColumnMapping.fromJson(request.getColumnMapping(), "columnMapping");
        ParserOptions options = ParserOptions.of(
                firstNonNull(request.getEncoding(), found.getEncoding()),
                firstNonNull(request.getDelimiter(), found.getDelimiter()),
                firstNonNull(request.getDateFormat(), found.getDateFormat()),
                firstNonNull(request.getDecimalFormat(), found.getDecimalFormat()),
                firstNonNull(request.getSignConvention(), found.getSignConvention()));

        BankImportStatementHeader header = request.getStatement() != null ? request.getStatement() : headerOf(found);
        if (request.getStatement() != null) {
            Map<String, String> errors = new LinkedHashMap<>();
            validateHeader(errors, header);
            throwIfAny(errors, "The statement header is invalid");
        }
        List<SplitPoint> split = request.getSplitAt() != null
                ? splitPoints(request.getSplitAt(), header)
                : splitPoints(fromJson(found.getSplitAt()), header);
        // The acknowledgement travels with the header: absent keeps the stored one, blank clears it, so a
        // correction that closes the gap can drop it and one that opens a gap can supply it.
        String gapAcknowledgement = request.getGapAcknowledgement() == null
                ? found.getGapAcknowledgement()
                : blankToNull(request.getGapAcknowledgement().trim());
        if (gapAcknowledgement != null && gapAcknowledgement.length() > 1000) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "gapAcknowledgement is too long",
                    "gapAcknowledgement",
                    "at most 1000 characters");
        }
        if (request.getStatement() != null || request.getGapAcknowledgement() != null) {
            // A corrected or widened header, or a changed acknowledgement, re-runs the header checks (§4.5:
            // a widening may overlap) with the acknowledgement that is then stored.
            lookup.checkHeader(found.getGlAccountId(), statementHeader(header), gapAcknowledgement);
            applyHeader(found, header);
            found.setGapAcknowledgement(gapAcknowledgement);
        }
        found.setSplitAt(toJson(split));

        StatementFileParser parser = parsers.require(found.getFormatCode());
        ParsedFile parsed = parser.parse(requireFile(found).getFileBytes(), options, mapping);

        // Every row is parsed again from the retained bytes: corrections, skips and decisions go (§4.4).
        List<BankImportRow> old = rowRepository.findByImportIdOrderByRowNumberAsc(importId);
        rowRepository.deleteAll(old);
        rowRepository.flush();
        List<BankImportRow> rows = ImportEvaluator.fromParse(importId, parsed.rows());
        if (parsed.mappingResolved()) {
            evaluate(found, rows);
        }
        rowRepository.saveAll(rows);

        String actor = currentActor();
        Map<String, Object> previousMapping = found.getColumnMapping();
        applyOptions(found, options, parsed);
        // Each mapping request states its own intent; an earlier opt-in does not outlive it, so commit
        // saves the mapping on a new profile only when the latest request asked for that.
        boolean saveAsAccountDefault = Boolean.TRUE.equals(request.getSaveAsAccountDefault());
        found.setSaveMappingAsDefault(saveAsAccountDefault);
        if (saveAsAccountDefault) {
            lookup.saveDefaultColumnMapping(found.getGlAccountId(), mapping.toJson(), actor);
        }
        found.setStatus(parsed.mappingResolved() ? BankImportStatus.VALIDATED : BankImportStatus.UPLOADED);
        applyCounts(found, rows);
        found.setUpdatedAt(Instant.now(clock));
        BankImport saved = imports.saveAndFlush(found);

        audit.record(
                importId,
                BankImportAuditRecorder.BANK_IMPORT_MAPPING_SET,
                actor,
                request.getGapAcknowledgement() == null ? null : saved.getGapAcknowledgement(),
                previousMapping == null ? null : "columnMapping=" + previousMapping,
                "columnMapping=" + mapping.toJson() + ", signConvention=" + options.signConvention()
                        + ", window=" + saved.getStatementStartDate() + ".." + saved.getStatementEndDate()
                        + ", saveAsAccountDefault=" + saveAsAccountDefault
                        + ", status=" + saved.getStatus());
        return view(saved, rows, true).build();
    }

    // ---- rows ----------------------------------------------------------------------------------

    @Override
    public @NonNull BankImportRowResponse updateRow(
            @NonNull UUID importId, @NonNull UUID rowId, @NonNull BankImportRowUpdateRequest request) {
        BankImport found = requireMutable(importId, request.getVersion());
        BankImportRow row = rowRepository
                .findByRowIdAndImportId(rowId, importId)
                .orElseThrow(() -> new BankRecException(
                        BankRecErrorCode.BANK_IMPORT_ROW_NOT_FOUND, "Import row not found: " + rowId));
        int actions = (request.getCorrectedValues() != null ? 1 : 0)
                + (Boolean.TRUE.equals(request.getSkip()) ? 1 : 0)
                + (request.getDuplicateDecision() != null ? 1 : 0);
        if (actions != 1) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "Exactly one of correctedValues, skip = true or duplicateDecision is required",
                    "correctedValues",
                    "exactly one of correctedValues, skip or duplicateDecision");
        }
        String actor = currentActor();
        Instant now = Instant.now(clock);
        String action;
        String reason = null;
        if (Boolean.TRUE.equals(request.getSkip())) {
            reason = Justification.required(request.getReason(), "reason");
            skip(row, reason, actor, now);
            action = "SKIP";
        } else if (request.getDuplicateDecision() != null) {
            action = decide(row, request.getDuplicateDecision(), request.getReason(), actor, now, "duplicateDecision");
            reason = row.getSkipReason();
        } else {
            correct(found, row, request.getCorrectedValues(), actor, now);
            action = "CORRECT";
        }

        List<BankImportRow> rows = rowRepository.findByImportIdOrderByRowNumberAsc(importId);
        replace(rows, row);
        if (found.getStatus() == BankImportStatus.VALIDATED) {
            evaluate(found, rows);
        }
        rowRepository.saveAll(rows);
        applyCounts(found, rows);
        found.setUpdatedAt(now);
        imports.saveAndFlush(found);

        audit.record(
                importId,
                BankImportAuditRecorder.BANK_IMPORT_ROW_CORRECT,
                actor,
                reason,
                null,
                "rowId=" + rowId + ", rowNumber=" + row.getRowNumber() + ", action=" + action
                        + (row.getCorrectedValues() == null ? "" : ", correctedValues=" + row.getCorrectedValues())
                        + ", rowStatus=" + row.getRowStatus());
        return rowView(row);
    }

    private static void skip(BankImportRow row, String reason, String actor, Instant now) {
        row.setRowStatus(BankImportRowStatus.SKIPPED);
        row.setSkipReason(reason);
        row.setCorrectedBy(actor);
        row.setCorrectedAt(now);
    }

    /** A duplicate decision on a {@code POSSIBLE_DUPLICATE} row (§3.8); returns the audit action. */
    private static String decide(
            BankImportRow row, String decision, @Nullable String reason, String actor, Instant now, String field) {
        String normalized = decision.trim().toUpperCase(Locale.ROOT);
        if (!ImportEvaluator.DISTINCT.equals(normalized) && !ImportEvaluator.DUPLICATE.equals(normalized)) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "Unknown duplicate decision " + decision,
                    field,
                    "DISTINCT or DUPLICATE");
        }
        if (row.getRowStatus() != BankImportRowStatus.POSSIBLE_DUPLICATE) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "Row " + row.getRowNumber() + " is " + row.getRowStatus() + ", not a POSSIBLE_DUPLICATE",
                    field,
                    "only on a POSSIBLE_DUPLICATE row");
        }
        row.setDuplicateDecision(normalized);
        row.setCorrectedBy(actor);
        row.setCorrectedAt(now);
        if (ImportEvaluator.DUPLICATE.equals(normalized)) {
            row.setRowStatus(BankImportRowStatus.SKIPPED);
            row.setSkipReason(
                    reason == null
                            ? "Confirmed duplicate of an earlier transaction"
                            : Justification.required(reason, "reason"));
            return "DUPLICATE";
        }
        // Confirmed distinct: back to PARSED (or CORRECTED); evaluate() leaves a DISTINCT row unflagged.
        row.setRowStatus(row.getCorrectedValues() != null ? BankImportRowStatus.CORRECTED : BankImportRowStatus.PARSED);
        return "DISTINCT";
    }

    /** Applies corrected values over the row's effective values; {@code rawValues} are never touched. */
    private static void correct(
            BankImport found, BankImportRow row, Map<String, Object> corrections, String actor, Instant now) {
        if (row.getRowStatus() == BankImportRowStatus.SKIPPED) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "Row " + row.getRowNumber() + " is skipped",
                    "correctedValues",
                    "a skipped row cannot be corrected");
        }
        if (corrections.isEmpty()) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "correctedValues is empty", "correctedValues", "is empty");
        }
        ParserOptions options = ParserOptions.of(
                found.getEncoding(), found.getDelimiter(), found.getDateFormat(), found.getDecimalFormat(), null);
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : corrections.entrySet()) {
            String key = entry.getKey();
            String field = "correctedValues." + key;
            Object value = entry.getValue();
            if (!CORRECTABLE.contains(key)) {
                errors.put(field, "not a correctable value; one of " + new java.util.TreeSet<>(CORRECTABLE));
                continue;
            }
            String text = value == null ? null : value.toString().trim();
            switch (key) {
                case "date" -> {
                    LocalDate date = text == null ? null : parseCorrectedDate(text, options);
                    if (date == null) {
                        errors.put(field, "a date (ISO yyyy-MM-dd or the import's date format)");
                    } else {
                        row.setTransactionDate(date);
                    }
                }
                case "signedAmount" -> {
                    BigDecimal amount = text == null ? null : StatementValues.parseAmount(text, options);
                    if (amount == null) {
                        errors.put(field, "a number, positive = cash in");
                    } else if (amount.signum() == 0) {
                        errors.put(field, "must not be zero");
                    } else if (amount.stripTrailingZeros().scale() > TransactionNormalizer.AMOUNT_SCALE) {
                        errors.put(field, "at most " + TransactionNormalizer.AMOUNT_SCALE + " decimal places");
                    } else {
                        row.setSignedAmount(TransactionNormalizer.scaleAmount(amount));
                    }
                }
                case "description" -> {
                    if (text == null || text.isEmpty() || text.length() > 500) {
                        errors.put(field, "1 to 500 characters");
                    } else {
                        row.setDescription(text);
                    }
                }
                case "reference" -> setOptional(errors, field, text, 255, row::setReference);
                case "checkNumber" -> setOptional(errors, field, text, 32, row::setCheckNumber);
                default -> setOptional(errors, field, text, 128, row::setSourceTransactionId);
            }
        }
        throwIfAny(errors, "The corrected values are invalid");

        Map<String, Object> merged = row.getCorrectedValues() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(row.getCorrectedValues());
        corrections.forEach((k, v) -> merged.put(k, v == null ? null : v.toString()));
        row.setCorrectedValues(merged);
        row.setCorrectedBy(actor);
        row.setCorrectedAt(now);
        // The corrected values are a new row as far as R1 is concerned: a duplicate decision taken on
        // the old values no longer stands, so evaluate() flags a collision of the new ones again.
        row.setDuplicateDecision(null);
        if (row.getTransactionDate() == null || row.getSignedAmount() == null || row.getDescription() == null) {
            ImportEvaluator.reject(
                    row,
                    RejectionCode.REQUIRED_COLUMN_MISSING,
                    "Row " + row.getRowNumber() + ": still missing " + String.join(", ", missing(row))
                            + " after the correction");
        } else {
            row.setRowStatus(BankImportRowStatus.CORRECTED);
            row.setRejectionCode(null);
            row.setRejectionDetail(null);
        }
    }

    private static List<String> missing(BankImportRow row) {
        List<String> missing = new ArrayList<>();
        if (row.getTransactionDate() == null) {
            missing.add("date");
        }
        if (row.getSignedAmount() == null) {
            missing.add("signedAmount");
        }
        if (row.getDescription() == null) {
            missing.add("description");
        }
        return missing;
    }

    private static @Nullable LocalDate parseCorrectedDate(String text, ParserOptions options) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException notIso) {
            return StatementValues.parseDate(text, options);
        }
    }

    private static void setOptional(
            Map<String, String> errors,
            String field,
            @Nullable String text,
            int max,
            java.util.function.Consumer<String> setter) {
        if (text != null && text.length() > max) {
            errors.put(field, "at most " + max + " characters");
        } else {
            setter.accept(text == null || text.isEmpty() ? null : text);
        }
    }

    private static void replace(List<BankImportRow> rows, BankImportRow changed) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getRowId().equals(changed.getRowId())) {
                rows.set(i, changed);
            }
        }
    }

    // ---- commit --------------------------------------------------------------------------------

    @Override
    public @NonNull BankImportCommitResponse commit(@NonNull UUID importId, @Nullable BankImportCommitRequest request) {
        BankImport found = requireImport(importId);
        if (found.getStatus() == BankImportStatus.COMMITTED) {
            // Idempotent on the import (§4.4): a second commit answers the first one's result.
            return committed(found);
        }
        if (found.getStatus() == BankImportStatus.DISCARDED) {
            throw discarded(found);
        }
        requireVersion(found, request == null ? null : request.getVersion());

        String actor = currentActor();
        Instant now = Instant.now(clock);
        List<BankImportRow> rows = rowRepository.findByImportIdOrderByRowNumberAsc(importId);
        if (request != null && request.getDuplicateDecisions() != null) {
            applyDecisions(rows, request.getDuplicateDecisions(), actor, now);
        }
        if (found.getStatus() == BankImportStatus.UPLOADED) {
            throw BankRecException.field(
                    BankRecErrorCode.IMPORT_NOT_COMMITTABLE,
                    "The file's columns are not mapped",
                    "columnMapping",
                    "column mapping required; PUT /mapping first");
        }

        UUID glAccountId = found.getGlAccountId();
        BankAccountTerms terms = lookup.requireAccount(glAccountId);
        requireCurrency(found.getCurrency(), terms);
        // Stored transactions can change between upload and commit (another import, a feed), so R1 and
        // the window are evaluated again before the preconditions are read.
        evaluate(found, rows);
        List<Segment> segments = segments(found);
        Map<String, String> blockers = ImportEvaluator.blockers(rows, segments, terms);
        if (!blockers.isEmpty()) {
            throw new BankRecException(
                    BankRecErrorCode.IMPORT_NOT_COMMITTABLE,
                    "The import cannot be committed: " + blockers.size() + " problem(s)",
                    blockers);
        }
        requireFileNotCommitted(glAccountId, found.getFileSha256());
        // The previous COMMITTED statement can change after upload (§4.2): the acknowledgement rules
        // run again and answer their own codes.
        lookup.checkHeader(glAccountId, statementHeader(headerOf(found)), found.getGapAcknowledgement());

        Set<Integer> distinct = new HashSet<>();
        for (BankImportRow row : rows) {
            if (ImportEvaluator.DISTINCT.equals(row.getDuplicateDecision())
                    && ImportEvaluator.COMMITTABLE.contains(row.getRowStatus())) {
                distinct.add(row.getRowNumber());
            }
        }
        String connectorCode = parsers.require(found.getFormatCode()).connectorCode();
        List<UUID> statementIds = new ArrayList<>();
        int transactionCount = 0;
        int possibleDuplicates = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            List<BankImportRow> segmentRows = rows.stream()
                    .filter(r -> ImportEvaluator.COMMITTABLE.contains(r.getRowStatus()))
                    .filter(r -> segment.contains(r.getTransactionDate()))
                    .toList();
            BankTransactionsObservedV1 batch = batch(found, segment, segmentRows, connectorCode, now);
            IntakeResult result = intake.accept(
                    batch,
                    new IntakeContext(
                            glAccountId,
                            actor,
                            i == 0 ? found.getGapAcknowledgement() : null,
                            importId,
                            null,
                            null,
                            found.isSaveMappingAsDefault() ? found.getColumnMapping() : null,
                            distinct));
            statementIds.add(result.statementId());
            transactionCount += result.bankTransactionCount();
            possibleDuplicates += result.possibleDuplicateCount();
            for (int r = 0; r < segmentRows.size(); r++) {
                BankImportRow row = segmentRows.get(r);
                row.setBankTransactionId(result.bankTransactionIds().get(r));
                row.setRowStatus(BankImportRowStatus.COMMITTED);
            }
        }
        rowRepository.saveAll(rows);

        applyCounts(found, rows);
        found.setPossibleDuplicateCount(possibleDuplicates);
        found.setStatementIds(statementIds.stream().map(UUID::toString).toList());
        found.setStatementId(statementIds.getLast());
        found.setStatus(BankImportStatus.COMMITTED);
        found.setCommittedAt(now);
        found.setCommittedBy(actor);
        found.setUpdatedAt(now);
        BankImport saved;
        try {
            saved = imports.saveAndFlush(found);
        } catch (DataIntegrityViolationException refused) {
            if (constraintDetail(refused).contains(COMMITTED_FILE_CONSTRAINT)) {
                // Retried once by RetryingBankImportService, where the service check names the winner.
                throw new ConcurrentCommitException(
                        BankRecErrorCode.IMPORT_FILE_ALREADY_COMMITTED,
                        "The same file was committed for this account by a concurrent import");
            }
            throw refused;
        }

        audit.record(
                importId,
                BankImportAuditRecorder.BANK_IMPORT_COMMIT,
                actor,
                found.getGapAcknowledgement(),
                null,
                "statementId=" + saved.getStatementId() + ", statementIds=" + statementIds + ", bankTransactionCount="
                        + transactionCount + ", possibleDuplicateCount=" + possibleDuplicates + ", skippedCount="
                        + saved.getSkippedCount());
        log.info(
                "Bank import {} committed as statement(s) {} ({} transactions, {} possible duplicates)",
                importId,
                statementIds,
                transactionCount,
                possibleDuplicates);
        return committed(saved);
    }

    private void applyDecisions(
            List<BankImportRow> rows,
            List<BankImportCommitRequest.DuplicateDecision> decisions,
            String actor,
            Instant now) {
        Map<Integer, BankImportRow> byNumber = new LinkedHashMap<>();
        rows.forEach(r -> byNumber.put(r.getRowNumber(), r));
        for (int i = 0; i < decisions.size(); i++) {
            BankImportCommitRequest.DuplicateDecision decision = decisions.get(i);
            String field = "duplicateDecisions[" + i + "]";
            if (decision == null || decision.getRowNumber() == null || decision.getDecision() == null) {
                throw BankRecException.field(
                        BankRecErrorCode.VALIDATION_ERROR,
                        field + " needs rowNumber and decision",
                        field,
                        "rowNumber and decision are required");
            }
            BankImportRow row = byNumber.get(decision.getRowNumber());
            if (row == null) {
                throw BankRecException.field(
                        BankRecErrorCode.VALIDATION_ERROR,
                        "No row " + decision.getRowNumber(),
                        field,
                        "unknown rowNumber");
            }
            decide(row, decision.getDecision(), null, actor, now, field);
        }
    }

    private static BankTransactionsObservedV1 batch(
            BankImport found, Segment segment, List<BankImportRow> rows, String connectorCode, Instant now) {
        if (rows.isEmpty()) {
            // Unreachable after blockers(); the contract refuses an empty batch.
            throw new BankRecException(
                    BankRecErrorCode.IMPORT_NOT_COMMITTABLE,
                    "No rows dated " + segment.startDate() + ".." + segment.endDate());
        }
        List<BankTransactionObserved> observed = new ArrayList<>(rows.size());
        for (BankImportRow row : rows) {
            observed.add(new BankTransactionObserved(
                    row.getSourceTransactionId(),
                    row.getRowNumber(),
                    Change.ADDED,
                    SettlementState.POSTED,
                    row.getTransactionDate(),
                    null,
                    row.getSignedAmount(),
                    null,
                    row.getDescription(),
                    null,
                    row.getReference(),
                    row.getCheckNumber(),
                    null,
                    null,
                    null));
        }
        // Files have no feed account: the envelope aggregate is the import id (§2.2).
        return new BankTransactionsObservedV1(
                BankTransactionsObservedV1.SourceKind.FILE_IMPORT,
                connectorCode,
                null,
                null,
                null,
                found.getCurrency(),
                now,
                null,
                new StatementHeader(
                        found.getStatementRef(),
                        segment.startDate(),
                        segment.endDate(),
                        segment.openingBalance(),
                        segment.closingBalance()),
                observed);
    }

    private static BankImportCommitResponse committed(BankImport found) {
        return BankImportCommitResponse.builder()
                .importId(found.getImportId())
                .statementId(found.getStatementId())
                .statementIds(statementIds(found))
                .bankTransactionCount(found.getAcceptedCount())
                .possibleDuplicateCount(found.getPossibleDuplicateCount())
                .reconciliationId(found.getReconciliationId())
                .version(found.getVersion())
                .build();
    }

    // ---- discard and download ------------------------------------------------------------------

    @Override
    public @NonNull BankImportResponse discard(@NonNull UUID importId, @NonNull BankImportDiscardRequest request) {
        BankImport found = requireMutable(importId, request.getVersion());
        String reason = Justification.required(request.getReason(), "reason");
        String actor = currentActor();
        Instant now = Instant.now(clock);
        BankImportStatus before = found.getStatus();
        found.setStatus(BankImportStatus.DISCARDED);
        found.setDiscardedAt(now);
        found.setDiscardedBy(actor);
        found.setDiscardReason(reason);
        found.setUpdatedAt(now);
        BankImport saved = imports.saveAndFlush(found);
        audit.record(
                importId,
                BankImportAuditRecorder.BANK_IMPORT_DISCARD,
                actor,
                reason,
                before.name(),
                BankImportStatus.DISCARDED.name());
        return view(saved, rowRepository.findByImportIdOrderByRowNumberAsc(importId), false)
                .build();
    }

    @Override
    public @NonNull ImportFile download(@NonNull UUID importId) {
        BankImport found = requireImport(importId);
        BankImportFile file = requireFile(found);
        audit.record(
                importId,
                BankImportAuditRecorder.BANK_IMPORT_FILE_READ,
                currentActor(),
                null,
                null,
                "fileName=" + found.getFileName() + ", sha256=" + found.getFileSha256() + ", bytes="
                        + file.getFileBytes().length);
        return new ImportFile(
                file.getFileBytes(),
                found.getFileName() == null ? "statement-" + importId + ".csv" : found.getFileName(),
                found.getContentType() == null ? DEFAULT_CONTENT_TYPE : found.getContentType());
    }

    private BankImportFile requireFile(BankImport found) {
        return files.findById(found.getImportId())
                .orElseThrow(() -> BankRecException.field(
                        BankRecErrorCode.BANK_IMPORT_FILE_NOT_FOUND,
                        "The raw file of import " + found.getImportId() + " is no longer retained",
                        "retentionUntil",
                        String.valueOf(found.getRetentionUntil())));
    }

    // ---- lifecycle guards ----------------------------------------------------------------------

    private BankImport requireImport(UUID importId) {
        return imports.findById(importId)
                .orElseThrow(() -> new BankRecException(
                        BankRecErrorCode.BANK_IMPORT_NOT_FOUND, "Bank import not found: " + importId));
    }

    /** An import a mapping, row or discard change may touch: not terminal, at the caller's version. */
    private BankImport requireMutable(UUID importId, @Nullable Long version) {
        BankImport found = requireImport(importId);
        if (found.getStatus() == BankImportStatus.COMMITTED) {
            throw BankRecException.field(
                    BankRecErrorCode.IMPORT_ALREADY_COMMITTED,
                    "Import " + importId + " is COMMITTED; it can no longer change",
                    "statementId",
                    String.valueOf(found.getStatementId()));
        }
        if (found.getStatus() == BankImportStatus.DISCARDED) {
            throw discarded(found);
        }
        requireVersion(found, version);
        return found;
    }

    private static BankRecException discarded(BankImport found) {
        return new BankRecException(
                BankRecErrorCode.IMPORT_DISCARDED, "Import " + found.getImportId() + " is DISCARDED");
    }

    private static void requireVersion(BankImport found, @Nullable Long version) {
        if (version != null && !version.equals(found.getVersion())) {
            throw BankRecException.field(
                    BankRecErrorCode.OPTIMISTIC_LOCK,
                    "Import " + found.getImportId() + " was changed by another request; reload it and retry",
                    "version",
                    "current version is " + found.getVersion());
        }
    }

    private void requireFileNotCommitted(UUID glAccountId, String sha256) {
        imports.findFirstByGlAccountIdAndFileSha256AndStatus(glAccountId, sha256, BankImportStatus.COMMITTED)
                .ifPresent(earlier -> {
                    Map<String, String> ids = new LinkedHashMap<>();
                    ids.put("importId", earlier.getImportId().toString());
                    ids.put("statementId", String.valueOf(earlier.getStatementId()));
                    throw new BankRecException(
                            BankRecErrorCode.IMPORT_FILE_ALREADY_COMMITTED,
                            "This file was already committed for the account by import " + earlier.getImportId(),
                            ids);
                });
    }

    private static void requireCurrency(String currency, BankAccountTerms terms) {
        if (!terms.currency().equals(currency)) {
            throw BankRecException.field(
                    BankRecErrorCode.CURRENCY_NOT_SUPPORTED,
                    "Currency " + currency + " is not the account's currency " + terms.currency(),
                    "currency",
                    "expected " + terms.currency());
        }
    }

    // ---- evaluation helpers --------------------------------------------------------------------

    private void evaluate(BankImport found, List<BankImportRow> rows) {
        UUID glAccountId = found.getGlAccountId();
        ImportEvaluator.evaluate(
                rows,
                glAccountId,
                found.getStatementStartDate(),
                found.getStatementEndDate(),
                fingerprints -> lookup.collidingFingerprints(glAccountId, fingerprints));
    }

    private static void applyCounts(BankImport found, List<BankImportRow> rows) {
        ImportEvaluator.Counts counts = ImportEvaluator.counts(rows);
        found.setRowCount(counts.rowCount());
        found.setAcceptedCount(counts.accepted());
        found.setRejectedCount(counts.rejected());
        found.setPossibleDuplicateCount(counts.possibleDuplicates());
        found.setSkippedCount(counts.skipped());
        found.setOutOfWindowCount(counts.outOfWindow());
    }

    private static List<Segment> segments(BankImport found) {
        return ImportEvaluator.segments(
                found.getStatementStartDate(),
                found.getStatementEndDate(),
                found.getOpeningBalance(),
                found.getClosingBalance(),
                fromJson(found.getSplitAt()).stream()
                        .map(p -> new SplitPoint(p.getDate(), p.getClosingBalance()))
                        .toList());
    }

    private static @Nullable ColumnMapping savedMapping(BankAccountTerms terms) {
        if (terms.defaultColumnMapping() == null || terms.defaultColumnMapping().isEmpty()) {
            return null;
        }
        try {
            return ColumnMapping.fromJson(terms.defaultColumnMapping(), "defaultColumnMapping");
        } catch (BankRecException unusable) {
            // A saved mapping this parser cannot read falls back to the defaults rather than refusing the file.
            return null;
        }
    }

    private static void applyOptions(BankImport target, ParserOptions options, ParsedFile parsed) {
        target.setColumnMapping(parsed.mapping().toJson());
        target.setSignConvention(options.signConvention().name());
        target.setDateFormat(options.datePattern());
        target.setDecimalFormat(options.decimalFormat().name());
        target.setEncoding(options.charset().name());
        target.setDelimiter(options.delimiterCode());
        target.setSourceColumns(parsed.columns());
        target.setHeaderRow(parsed.headerRow());
    }

    private static void applyHeader(BankImport target, BankImportStatementHeader header) {
        target.setStatementStartDate(header.getStartDate());
        target.setStatementEndDate(header.getEndDate());
        target.setOpeningBalance(header.getOpeningBalance());
        target.setClosingBalance(header.getClosingBalance());
        target.setStatementRef(blankToNull(header.getStatementRef()));
    }

    private static BankImportStatementHeader headerOf(BankImport found) {
        return new BankImportStatementHeader(
                found.getStatementStartDate(),
                found.getStatementEndDate(),
                found.getOpeningBalance(),
                found.getClosingBalance(),
                found.getStatementRef());
    }

    private static StatementHeader statementHeader(BankImportStatementHeader header) {
        return new StatementHeader(
                blankToNull(header.getStatementRef()),
                header.getStartDate(),
                header.getEndDate(),
                header.getOpeningBalance(),
                header.getClosingBalance());
    }

    // ---- request shape -------------------------------------------------------------------------

    private byte[] decodeContent(@Nullable String content) {
        if (content == null || content.isBlank()) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "content (base64) or a multipart file part is required",
                    "content",
                    "is required");
        }
        try {
            return Base64.getMimeDecoder().decode(content.trim());
        } catch (IllegalArgumentException notBase64) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "content is not base64", "content", "must be base64");
        }
    }

    private void validateCreate(BankImportCreateRequest request, byte[] content) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.getGlAccountId() == null) {
            errors.put("glAccountId", "is required");
        }
        if (request.getRequestId() == null) {
            errors.put("requestId", "is required");
        } else if (request.getRequestId().version() != 7) {
            errors.put("requestId", "must be a UUIDv7");
        }
        if (request.getFormatCode() == null || request.getFormatCode().isBlank()) {
            errors.put("formatCode", "is required");
        }
        if (content.length == 0) {
            errors.put("content", "the file is empty");
        } else if (content.length > maxFileBytes) {
            errors.put("content", "at most " + maxFileBytes + " bytes");
        }
        if (request.getFileName() != null && request.getFileName().length() > 255) {
            errors.put("fileName", "at most 255 characters");
        }
        if (request.getContentType() != null && request.getContentType().length() > 100) {
            errors.put("contentType", "at most 100 characters");
        }
        if (request.getCurrency() != null && !isIsoCurrency(request.getCurrency())) {
            errors.put("currency", "must be an ISO 4217 code");
        }
        if (request.getStatement() == null) {
            errors.put("statement", "is required");
        } else {
            validateHeader(errors, request.getStatement());
        }
        if (request.getGapAcknowledgement() != null
                && request.getGapAcknowledgement().length() > 1000) {
            errors.put("gapAcknowledgement", "at most 1000 characters");
        }
        throwIfAny(errors, "The import request is invalid");
    }

    private static void validateHeader(Map<String, String> errors, BankImportStatementHeader header) {
        if (header.getStartDate() == null) {
            errors.put("statement.startDate", "is required");
        }
        if (header.getEndDate() == null) {
            errors.put("statement.endDate", "is required");
        }
        if (header.getOpeningBalance() == null) {
            errors.put("statement.openingBalance", "is required");
        }
        if (header.getClosingBalance() == null) {
            errors.put("statement.closingBalance", "is required");
        }
        if (header.getStartDate() != null
                && header.getEndDate() != null
                && header.getStartDate().isAfter(header.getEndDate())) {
            errors.put("statement.startDate", "must not be after statement.endDate");
        }
        if (header.getStatementRef() != null && header.getStatementRef().length() > 64) {
            errors.put("statement.statementRef", "at most 64 characters");
        }
    }

    /** {@code splitAt}: each date inside the window before its end, strictly increasing, with a closing balance. */
    private static List<SplitPoint> splitPoints(
            @Nullable List<BankImportSplitPoint> splitAt, @Nullable BankImportStatementHeader header) {
        if (splitAt == null || splitAt.isEmpty() || header == null) {
            return List.of();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        List<SplitPoint> points = new ArrayList<>();
        LocalDate previous = null;
        for (int i = 0; i < splitAt.size(); i++) {
            BankImportSplitPoint point = splitAt.get(i);
            String field = "splitAt[" + i + "]";
            if (point == null || point.getDate() == null || point.getClosingBalance() == null) {
                errors.put(field, "date and closingBalance are required");
                continue;
            }
            if (point.getDate().isBefore(header.getStartDate())
                    || !point.getDate().isBefore(header.getEndDate())) {
                errors.put(
                        field + ".date",
                        "must lie in " + header.getStartDate() + ".."
                                + header.getEndDate().minusDays(1));
            } else if (previous != null && !point.getDate().isAfter(previous)) {
                errors.put(field + ".date", "must be after the previous split date");
            }
            previous = point.getDate();
            points.add(new SplitPoint(point.getDate(), point.getClosingBalance()));
        }
        throwIfAny(errors, "The split points are invalid");
        return points;
    }

    private static void throwIfAny(Map<String, String> errors, String message) {
        if (!errors.isEmpty()) {
            throw new BankRecException(BankRecErrorCode.VALIDATION_ERROR, message, errors);
        }
    }

    private static boolean isIsoCurrency(String code) {
        String trimmed = code.trim();
        if (trimmed.length() != 3) {
            return false;
        }
        try {
            Currency.getInstance(trimmed.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    private static PageRequest page(int page, int size, Sort sort) {
        if (page < 0) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "page must be >= 0", "page", "must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "size must be between 1 and " + MAX_PAGE_SIZE,
                    "size",
                    "between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, sort);
    }

    // ---- views ---------------------------------------------------------------------------------

    private BankImportResponse.BankImportResponseBuilder view(
            BankImport found, List<BankImportRow> rows, boolean withPreview) {
        AccountDisplay account =
                lookup.accountDisplay(List.of(found.getGlAccountId())).get(found.getGlAccountId());
        BankImportResponse.BankImportResponseBuilder builder = describe(found, account);
        if (withPreview && found.getStatus() != BankImportStatus.UPLOADED) {
            builder.preview(preview(found, rows));
        }
        return builder;
    }

    private static BankImportResponse.Preview preview(BankImport found, List<BankImportRow> rows) {
        String currency = found.getCurrency();
        BankAccountTerms terms = new BankAccountTerms(
                found.getGlAccountId(),
                "",
                "",
                currency,
                Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits()),
                null,
                false);
        ImportEvaluator.Preview preview = ImportEvaluator.preview(rows, segments(found), terms);
        List<BankImportResponse.SegmentTotal> totals = preview.segments().stream()
                .map(BankImportServiceImpl::segmentView)
                .toList();
        return BankImportResponse.Preview.builder()
                .firstRows(preview.firstRows().stream()
                        .map(r -> BankImportResponse.PreviewRow.builder()
                                .rowNumber(r.rowNumber())
                                .date(r.date())
                                .description(r.description())
                                .signedAmount(r.signedAmount())
                                .rowStatus(r.rowStatus())
                                .runningBalance(r.runningBalance())
                                .build())
                        .toList())
                .segments(totals)
                .ties(preview.segments().stream().allMatch(SegmentTotal::ties))
                .build();
    }

    private static BankImportResponse.SegmentTotal segmentView(SegmentTotal total) {
        return BankImportResponse.SegmentTotal.builder()
                .startDate(total.segment().startDate())
                .endDate(total.segment().endDate())
                .openingBalance(total.segment().openingBalance())
                .closingBalance(total.segment().closingBalance())
                .activityTotal(total.activityTotal())
                .expectedClosing(total.expectedClosing())
                .difference(total.difference())
                .ties(total.ties())
                .transactionCount(total.transactionCount())
                .build();
    }

    private static BankImportResponse.BankImportResponseBuilder describe(
            BankImport found, @Nullable AccountDisplay account) {
        return BankImportResponse.builder()
                .importId(found.getImportId())
                .requestId(found.getRequestId())
                .glAccountId(found.getGlAccountId())
                .glAccountCode(account == null ? null : account.accountCode())
                .glAccountName(account == null ? null : account.accountName())
                .currency(found.getCurrency())
                .formatCode(found.getFormatCode())
                .fileName(found.getFileName())
                .contentType(found.getContentType())
                .fileSize(found.getFileSize())
                .fileSha256(found.getFileSha256())
                .status(found.getStatus())
                .mappingRequired(found.getStatus() == BankImportStatus.UPLOADED)
                .columns(found.getSourceColumns() == null ? List.of() : found.getSourceColumns())
                .headerRow(found.getHeaderRow())
                .statement(headerOf(found))
                .splitAt(fromJson(found.getSplitAt()))
                .columnMapping(found.getColumnMapping())
                .signConvention(found.getSignConvention())
                .dateFormat(found.getDateFormat())
                .decimalFormat(found.getDecimalFormat())
                .encoding(found.getEncoding())
                .delimiter(found.getDelimiter())
                .saveMappingAsDefault(found.isSaveMappingAsDefault())
                .gapAcknowledgement(found.getGapAcknowledgement())
                .rowCount(found.getRowCount())
                .acceptedCount(found.getAcceptedCount())
                .rejectedCount(found.getRejectedCount())
                .possibleDuplicateCount(found.getPossibleDuplicateCount())
                .skippedCount(found.getSkippedCount())
                .outOfWindowCount(found.getOutOfWindowCount())
                .statementId(found.getStatementId())
                .statementIds(statementIds(found))
                .reconciliationId(found.getReconciliationId())
                .retentionUntil(found.getRetentionUntil())
                .filePurged(found.getFilePurgedAt() != null)
                .createdAt(found.getCreatedAt())
                .createdBy(found.getCreatedBy())
                .committedAt(found.getCommittedAt())
                .committedBy(found.getCommittedBy())
                .discardedAt(found.getDiscardedAt())
                .discardedBy(found.getDiscardedBy())
                .discardReason(found.getDiscardReason())
                .version(found.getVersion())
                .replayed(false);
    }

    private static BankImportRowResponse rowView(BankImportRow row) {
        return BankImportRowResponse.builder()
                .rowId(row.getRowId())
                .importId(row.getImportId())
                .rowNumber(row.getRowNumber())
                .rawValues(row.getRawValues())
                .date(row.getTransactionDate())
                .signedAmount(row.getSignedAmount())
                .description(row.getDescription())
                .reference(row.getReference())
                .checkNumber(row.getCheckNumber())
                .sourceTransactionId(row.getSourceTransactionId())
                .rowStatus(row.getRowStatus())
                .rejectionCode(row.getRejectionCode())
                .rejectionDetail(row.getRejectionDetail())
                .fingerprint(row.getFingerprint())
                .duplicateOfBankTransactionId(row.getDuplicateOfBankTransactionId())
                .duplicateOfRowNumber(row.getDuplicateOfRowNumber())
                .duplicateDecision(row.getDuplicateDecision())
                .correctedValues(row.getCorrectedValues())
                .correctedBy(row.getCorrectedBy())
                .correctedAt(row.getCorrectedAt())
                .skipReason(row.getSkipReason())
                .bankTransactionId(row.getBankTransactionId())
                .build();
    }

    private static List<UUID> statementIds(BankImport found) {
        return found.getStatementIds() == null
                ? List.of()
                : found.getStatementIds().stream().map(UUID::fromString).toList();
    }

    // ---- JSON forms of split points ------------------------------------------------------------

    private static @Nullable List<Map<String, Object>> toJson(List<SplitPoint> points) {
        if (points.isEmpty()) {
            return null;
        }
        return points.stream()
                .map(p -> {
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("date", p.date().toString());
                    json.put("closingBalance", p.closingBalance().toPlainString());
                    return json;
                })
                .toList();
    }

    private static List<BankImportSplitPoint> fromJson(@Nullable List<Map<String, Object>> json) {
        if (json == null) {
            return List.of();
        }
        return json.stream()
                .map(m -> new BankImportSplitPoint(
                        LocalDate.parse(String.valueOf(m.get("date"))),
                        new BigDecimal(String.valueOf(m.get("closingBalance")))))
                .toList();
    }

    // ---- small helpers -------------------------------------------------------------------------

    /**
     * SHA-256 of the create command's payload, to tell a replay from a reuse (§6.3). Every field is
     * length-prefixed ({@code <length>:<text>}, {@code ~} for absent), so free text that contains a
     * delimiter — a gap acknowledgement, a statement reference, a header name in the column mapping —
     * can never shift a field boundary and collide with a different payload (as #2301's manual hash).
     */
    static String requestHash(
            BankImportCreateRequest request,
            String sha256,
            ParserOptions options,
            @Nullable ColumnMapping mapping,
            List<SplitPoint> split) {
        BankImportStatementHeader header = request.getStatement();
        StringBuilder canonical = new StringBuilder();
        field(canonical, request.getGlAccountId());
        field(canonical, request.getFormatCode().trim().toUpperCase(Locale.ROOT));
        field(canonical, sha256);
        field(
                canonical,
                request.getCurrency() == null
                        ? null
                        : request.getCurrency().trim().toUpperCase(Locale.ROOT));
        field(canonical, header.getStartDate());
        field(canonical, header.getEndDate());
        field(canonical, plain(header.getOpeningBalance()));
        field(canonical, plain(header.getClosingBalance()));
        field(canonical, blankToNull(header.getStatementRef()));
        if (mapping == null) {
            field(canonical, null);
        } else {
            Map<String, Object> sorted = new TreeMap<>(mapping.toJson());
            field(canonical, sorted.size());
            sorted.forEach((key, value) -> {
                field(canonical, key);
                // A position and a header name that reads the same are different mappings.
                field(canonical, (value instanceof Number ? "#" : "$") + value);
            });
        }
        field(canonical, options.charset().name());
        field(canonical, options.delimiterCode());
        field(canonical, options.datePattern());
        field(canonical, options.decimalFormat().name());
        field(canonical, options.signConvention().name());
        field(
                canonical,
                request.getGapAcknowledgement() == null
                        ? null
                        : request.getGapAcknowledgement().trim());
        field(canonical, split.size());
        for (SplitPoint point : split) {
            field(canonical, point.date());
            field(canonical, plain(point.closingBalance()));
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void field(StringBuilder canonical, @Nullable Object value) {
        if (value == null) {
            canonical.append('~');
            return;
        }
        String text = value.toString();
        canonical.append(text.length()).append(':').append(text);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    static @NonNull String sha256(byte @NonNull [] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static String constraintDetail(Throwable refused) {
        StringBuilder detail = new StringBuilder();
        for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
            detail.append(cause.getMessage()).append('\n');
            if (cause.getCause() == cause) {
                break;
            }
        }
        return detail.toString();
    }

    private static @Nullable String fileName(@Nullable String requested, @Nullable String uploaded) {
        String name = requested != null && !requested.isBlank() ? requested : uploaded;
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String contentType(@Nullable String requested, @Nullable String uploaded) {
        String type = requested != null && !requested.isBlank() ? requested : uploaded;
        if (type == null || type.isBlank() || type.length() > 100) {
            return DEFAULT_CONTENT_TYPE;
        }
        return type;
    }

    private static @Nullable String firstNonNull(@Nullable String a, @Nullable String b) {
        return a != null ? a : b;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}
