package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.BankStatementListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankStatementResponse;
import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.BankTransactionIntake;
import com.positivity.accounting.internal.bankrec.intake.IntakeContext;
import com.positivity.accounting.internal.bankrec.intake.IntakeResult;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
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
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual statement entry and the statement reads (SPEC-manual-bank-reconciliation §4.3 item 3, §6.1,
 * §6.3; story S2, #2301). A manual statement is a {@link BankTransactionsObservedV1} with {@code
 * sourceKind = MANUAL_ENTRY} handed to the intake port, so E1, E2, U1, U2, the acknowledgement rules
 * and the baseline apply exactly as they do to a file.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BankStatementServiceImpl implements BankStatementService {

    private static final String SYSTEM = "SYSTEM";

    private final BankTransactionIntake intake;
    private final BankCashAccounts bankCashAccounts;
    private final FunctionalCurrency functionalCurrency;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final BankAccountProfileRepository profiles;
    private final BankReconciliationRepository reconciliations;
    private final BankRecAuditRecorder audit;
    private final Clock clock;

    @Override
    public @NonNull BankStatementResponse createManualStatement(@NonNull BankStatementCreateRequest request) {
        validateShape(request);
        String requestHash = hash(request);

        Optional<BankStatement> earlier = statements.findByRequestId(request.getRequestId());
        if (earlier.isPresent()) {
            if (!requestHash.equals(earlier.get().getRequestHash())) {
                throw new BankRecException(
                        BankRecErrorCode.IDEMPOTENCY_CONFLICT,
                        "requestId " + request.getRequestId() + " was already used with a different payload");
            }
            return describe(earlier.get(), null).replayed(true).build();
        }

        String actor = currentActor();
        String currency = request.getCurrency() != null
                ? request.getCurrency().trim().toUpperCase(Locale.ROOT)
                : profiles.findById(request.getGlAccountId())
                        .map(BankAccountProfile::getCurrency)
                        .orElseGet(functionalCurrency::code);
        BankTransactionsObservedV1 batch = toBatch(request, currency);

        IntakeResult result = intake.accept(
                batch,
                new IntakeContext(
                        request.getGlAccountId(),
                        actor,
                        request.getGapAcknowledgement(),
                        null,
                        request.getRequestId(),
                        requestHash,
                        null));
        UUID statementId = result.statementId();
        BankStatement statement = statements
                .findById(statementId)
                .orElseThrow(() -> new IllegalStateException("committed statement not found: " + statementId));

        audit.record(
                BankRecAuditRecorder.BANK_STATEMENT,
                statementId,
                BankRecAuditRecorder.BANK_STATEMENT_CREATE,
                actor,
                statement.getGapAcknowledgement(),
                null,
                "glAccountId=" + statement.getGlAccountId() + ", window=" + statement.getStartDate() + ".."
                        + statement.getEndDate() + ", opening="
                        + statement.getOpeningBalance().toPlainString()
                        + ", closing=" + statement.getClosingBalance().toPlainString() + ", transactions="
                        + result.bankTransactionCount());
        log.info("Manual bank statement {} committed on account {}", statementId, statement.getGlAccountId());
        return describe(statement, result).build();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull BankStatementListResponse listStatements(
            @Nullable UUID glAccountId, @Nullable LocalDate from, @Nullable LocalDate to, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "from must not be after to", "from", "must not be after to");
        }
        Specification<BankStatement> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (glAccountId != null) {
                predicates.add(cb.equal(root.get("glAccountId"), glAccountId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("endDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("startDate"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<BankStatement> result =
                statements.findAll(spec, BankRecPaging.page(page, size, Sort.by("startDate", "statementId")));
        Map<UUID, BankCashAccount> accounts = bankCashAccounts.displayValues(
                result.getContent().stream().map(BankStatement::getGlAccountId).toList());
        List<BankStatementResponse> rows = result.getContent().stream()
                .map(s -> withCounts(BankRecViews.statement(s, accounts.get(s.getGlAccountId())), s, null)
                        .build())
                .toList();
        return new BankStatementListResponse(
                rows, result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull BankStatementResponse getStatement(@NonNull UUID statementId) {
        BankStatement statement = statements
                .findById(statementId)
                .orElseThrow(() -> new BankRecException(
                        BankRecErrorCode.BANK_STATEMENT_NOT_FOUND, "Bank statement not found: " + statementId));
        return describe(statement, null).build();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private BankStatementResponse.BankStatementResponseBuilder describe(
            BankStatement statement, @Nullable IntakeResult result) {
        BankCashAccount account = bankCashAccounts
                .displayValues(List.of(statement.getGlAccountId()))
                .get(statement.getGlAccountId());
        List<BankStatementResponse.ReconciliationLink> links =
                reconciliations.findByStatementIdOrderByStatementStartDateAsc(statement.getStatementId()).stream()
                        .map(r -> new BankStatementResponse.ReconciliationLink(
                                r.getReconciliationId(), r.getStatus().name()))
                        .toList();
        return withCounts(BankRecViews.statement(statement, account), statement, result)
                .reconciliations(links);
    }

    private BankStatementResponse.BankStatementResponseBuilder withCounts(
            BankStatementResponse.BankStatementResponseBuilder builder,
            BankStatement statement,
            @Nullable IntakeResult result) {
        UUID id = statement.getStatementId();
        return builder.bankTransactionCount(transactions.countByStatementId(id))
                .possibleDuplicateCount(
                        transactions.countByStatementIdAndStatus(id, BankTransactionStatus.POSSIBLE_DUPLICATE))
                .modifiedCount(result == null ? null : (long) result.modifiedCount());
    }

    /** Request shape (400 VALIDATION_ERROR naming every offending field). */
    private void validateShape(BankStatementCreateRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.getGlAccountId() == null) {
            errors.put("glAccountId", "is required");
        }
        if (request.getRequestId() == null) {
            errors.put("requestId", "is required");
        } else if (request.getRequestId().version() != 7) {
            errors.put("requestId", "must be a UUIDv7");
        }
        if (request.getCurrency() != null && !isIsoCurrency(request.getCurrency())) {
            errors.put("currency", "must be an ISO 4217 code");
        }
        BankStatementCreateRequest.Header header = request.getStatement();
        if (header == null) {
            errors.put("statement", "is required");
        } else {
            requireNonNull(errors, "statement.startDate", header.getStartDate());
            requireNonNull(errors, "statement.endDate", header.getEndDate());
            requireNonNull(errors, "statement.openingBalance", header.getOpeningBalance());
            requireNonNull(errors, "statement.closingBalance", header.getClosingBalance());
            if (header.getStartDate() != null
                    && header.getEndDate() != null
                    && header.getStartDate().isAfter(header.getEndDate())) {
                errors.put("statement.startDate", "must not be after statement.endDate");
            }
            if (header.getStatementRef() != null && header.getStatementRef().length() > 64) {
                errors.put("statement.statementRef", "at most 64 characters");
            }
        }
        List<BankStatementCreateRequest.Transaction> rows = request.getTransactions();
        if (rows == null || rows.isEmpty()) {
            errors.put("transactions", "at least one transaction is required");
        } else {
            for (int i = 0; i < rows.size(); i++) {
                validateRow(errors, "transactions[" + i + "]", rows.get(i));
            }
        }
        if (request.getGapAcknowledgement() != null
                && request.getGapAcknowledgement().length() > 1000) {
            errors.put("gapAcknowledgement", "at most 1000 characters");
        }
        if (!errors.isEmpty()) {
            throw new BankRecException(BankRecErrorCode.VALIDATION_ERROR, "The statement request is invalid", errors);
        }
    }

    private static void validateRow(
            Map<String, String> errors, String field, BankStatementCreateRequest.@Nullable Transaction row) {
        if (row == null) {
            errors.put(field, "is required");
            return;
        }
        if (row.getDate() == null) {
            errors.put(field + ".date", "is required");
        }
        if (row.getDescription() == null || row.getDescription().isBlank()) {
            errors.put(field + ".description", "is required");
        } else if (row.getDescription().length() > 500) {
            errors.put(field + ".description", "at most 500 characters");
        }
        if (row.getReference() != null && row.getReference().length() > 255) {
            errors.put(field + ".reference", "at most 255 characters");
        }
        if (row.getCheckNumber() != null && row.getCheckNumber().length() > 32) {
            errors.put(field + ".checkNumber", "at most 32 characters");
        }
        int amounts = (row.getSignedAmount() != null ? 1 : 0)
                + (row.getDebit() != null ? 1 : 0)
                + (row.getCredit() != null ? 1 : 0);
        if (amounts != 1) {
            errors.put(field, "exactly one of signedAmount, debit or credit is required");
            return;
        }
        BigDecimal amount = signedAmount(row);
        if (amount.signum() == 0) {
            errors.put(field, "the amount must not be zero");
        } else if ((row.getDebit() != null && row.getDebit().signum() < 0)
                || (row.getCredit() != null && row.getCredit().signum() < 0)) {
            errors.put(field, "debit and credit are positive numbers");
        } else if (amount.stripTrailingZeros().scale() > 4) {
            errors.put(field, "at most 4 decimal places");
        }
    }

    private static void requireNonNull(Map<String, String> errors, String field, @Nullable Object value) {
        if (value == null) {
            errors.put(field, "is required");
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

    /** Credit = cash in (positive), debit = cash out (negative), as §4.4's DEBIT_CREDIT_COLUMNS. */
    private static BigDecimal signedAmount(BankStatementCreateRequest.Transaction row) {
        if (row.getSignedAmount() != null) {
            return row.getSignedAmount();
        }
        if (row.getCredit() != null) {
            return row.getCredit();
        }
        return row.getDebit().negate();
    }

    private BankTransactionsObservedV1 toBatch(BankStatementCreateRequest request, String currency) {
        BankStatementCreateRequest.Header header = request.getStatement();
        List<BankTransactionObserved> rows = new ArrayList<>();
        List<BankStatementCreateRequest.Transaction> input = request.getTransactions();
        for (int i = 0; i < input.size(); i++) {
            BankStatementCreateRequest.Transaction row = input.get(i);
            rows.add(new BankTransactionObserved(
                    null,
                    i + 1,
                    Change.ADDED,
                    SettlementState.POSTED,
                    row.getDate(),
                    null,
                    signedAmount(row),
                    null,
                    row.getDescription(),
                    null,
                    blankToNull(row.getReference()),
                    blankToNull(row.getCheckNumber()),
                    null,
                    null,
                    null));
        }
        return new BankTransactionsObservedV1(
                BankTransactionsObservedV1.SourceKind.MANUAL_ENTRY,
                null,
                null,
                null,
                null,
                currency,
                Instant.now(clock),
                null,
                new StatementHeader(
                        blankToNull(header.getStatementRef()),
                        header.getStartDate(),
                        header.getEndDate(),
                        header.getOpeningBalance(),
                        header.getClosingBalance()),
                rows);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** SHA-256 of the command's payload, scale-independent for amounts, to tell a replay from a reuse. */
    static @NonNull String hash(@NonNull BankStatementCreateRequest request) {
        StringBuilder canonical = new StringBuilder();
        BankStatementCreateRequest.Header header = request.getStatement();
        canonical
                .append(request.getGlAccountId())
                .append('|')
                .append(
                        request.getCurrency() == null
                                ? ""
                                : request.getCurrency().trim().toUpperCase(Locale.ROOT))
                .append('|')
                .append(
                        request.getGapAcknowledgement() == null
                                ? ""
                                : request.getGapAcknowledgement().trim())
                .append('|')
                .append(header.getStatementRef())
                .append('|')
                .append(header.getStartDate())
                .append('|')
                .append(header.getEndDate())
                .append('|')
                .append(plain(header.getOpeningBalance()))
                .append('|')
                .append(plain(header.getClosingBalance()));
        for (BankStatementCreateRequest.Transaction row : request.getTransactions()) {
            canonical
                    .append("\n")
                    .append(row.getDate())
                    .append('|')
                    .append(plain(signedAmount(row)))
                    .append('|')
                    .append(row.getDescription())
                    .append('|')
                    .append(row.getReference())
                    .append('|')
                    .append(row.getCheckNumber());
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}
