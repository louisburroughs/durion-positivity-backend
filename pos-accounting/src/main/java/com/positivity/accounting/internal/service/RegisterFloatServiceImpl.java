package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.RegisterFloatChangeRequest;
import com.positivity.accounting.internal.dto.RegisterFloatGoLiveRequest;
import com.positivity.accounting.internal.dto.RegisterFloatResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.RegisterFloat;
import com.positivity.accounting.internal.entity.RegisterFloatChange;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.RegisterFloatChangeRepository;
import com.positivity.accounting.internal.repository.RegisterFloatRepository;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The register float commands (#2511; SPEC-accounting-workspace §4.6 "Float"; AW16, AW17).
 *
 * <p>Every leg resolves through the {@code REGISTER_FLOAT} posting category at the entry date ({@code
 * REGISTER_FLOAT} → 1080, {@code OPENING_BALANCE_EQUITY} → 3900); the bank side of a change is the account
 * the caller chose. The float row is locked for the length of a command, so two commands on one register
 * serialize. Idempotent on {@code requestId}: a replay with the same body returns the first result, another
 * body is 409 {@code IDEMPOTENCY_CONFLICT}. The actor comes from the security context (ADR-0018).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RegisterFloatServiceImpl implements RegisterFloatService {

    /** The posting category, and the journal entry source type, of every float entry. */
    public static final String POSTING_CATEGORY = "REGISTER_FLOAT";

    static final String FLOAT_KEY = "REGISTER_FLOAT";
    static final String OPENING_BALANCE_EQUITY_KEY = "OPENING_BALANCE_EQUITY";

    /** {@code AccountingAuditLog.entityType}; the entity id is the register's float row. */
    static final String AUDIT_ENTITY_TYPE = "REGISTER_FLOAT";

    static final String AUDIT_GO_LIVE = "REGISTER_FLOAT_GO_LIVE";
    static final String AUDIT_CHANGE = "REGISTER_FLOAT_CHANGE";

    private static final int MAX_REGISTER_ID = 100;
    private static final String SYSTEM = "SYSTEM";
    private static final List<RegisterFloatChangeKind> POSTING_KINDS =
            List.of(RegisterFloatChangeKind.GO_LIVE, RegisterFloatChangeKind.CHANGE);

    private final RegisterFloatRepository floats;
    private final RegisterFloatChangeRepository changes;
    private final GLAccountRepository glAccounts;
    private final GLMappingResolver glMappingResolver;
    private final JournalEntryService journalEntryService;
    private final AccountingAuditLogRepository auditLogs;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final LedgerCurrency ledgerCurrency;
    private final BankAccountCurrencies bankAccountCurrencies;
    private final RegisterFloatFacts facts;

    @Override
    public @NonNull Outcome establishGoLive(@NonNull String registerId, @NonNull RegisterFloatGoLiveRequest request) {
        request.requireValid();
        String register = requireRegisterId(registerId);
        String hash = new Hash()
                .field("GO_LIVE")
                .field(register)
                .field(request.locationId())
                .field(request.amount())
                .field(request.goLiveDate())
                .field(request.justification().trim())
                .digest();
        RegisterFloatChange replayed =
                changes.findByRequestId(request.requestId()).orElse(null);
        if (replayed != null) {
            return replay(replayed, hash);
        }
        requireMinorUnit(request.amount());

        RegisterFloat registerFloat = lockOrCreate(register, request.locationId());
        // A concurrent duplicate of this request waited on the lock: it answers with the first result.
        RegisterFloatChange committed =
                changes.findByRequestId(request.requestId()).orElse(null);
        if (committed != null) {
            return replay(committed, hash);
        }
        requireRegisterLocation(registerFloat, request.locationId());
        // Once per register (AW17): a standing go-live, or any standing float history, refuses a go-live.
        if (registerFloat.getGoLiveJournalEntryId() != null
                || !changes.findByRegisterFloatIdAndKindInAndReversalJournalEntryIdIsNull(
                                registerFloat.getRegisterFloatId(), POSTING_KINDS)
                        .isEmpty()) {
            throw new CashSetupException(
                    CashSetupException.Code.FLOAT_ALREADY_ESTABLISHED,
                    "Register " + register + " already has a float; correct it by reversing its float entries and"
                            + " running go-live again");
        }

        LocalDate date = request.goLiveDate();
        UUID floatAccount = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, FLOAT_KEY, date.atStartOfDay());
        UUID equityAccount =
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY, OPENING_BALANCE_EQUITY_KEY, date.atStartOfDay());
        UUID postingKey = UUIDv7Generator.generate();
        String description = "Go-live float for register " + register;
        // No override: the go-live is dated in an open period (§4.6), whatever the caller holds.
        JournalEntryResponse posted = post(
                postingKey,
                date,
                description,
                List.of(
                        floatLine(floatAccount, request.amount(), BigDecimal.ZERO, description, register, request),
                        line(equityAccount, BigDecimal.ZERO, request.amount(), description)),
                null);

        BigDecimal previous = registerFloat.getAmount();
        registerFloat.setAmount(request.amount());
        registerFloat.setGoLiveJournalEntryId(posted.getJournalEntryId());
        RegisterFloatChange change = change(
                registerFloat,
                RegisterFloatChangeKind.GO_LIVE,
                previous,
                null,
                posted,
                date,
                request.justification().trim(),
                null,
                request.requestId(),
                hash);
        return finish(registerFloat, change, previous, posted, AUDIT_GO_LIVE);
    }

    @Override
    public @NonNull Outcome changeFloat(@NonNull String registerId, @NonNull RegisterFloatChangeRequest request) {
        request.requireValid();
        String register = requireRegisterId(registerId);
        String override = blankToNull(request.overrideJustification());
        String hash = new Hash()
                .field("CHANGE")
                .field(register)
                .field(request.locationId())
                .field(request.amount())
                .field(request.bankGlAccountId())
                .field(request.effectiveDate())
                .field(request.justification().trim())
                .field(override)
                .digest();
        RegisterFloatChange replayed =
                changes.findByRequestId(request.requestId()).orElse(null);
        if (replayed != null) {
            return replay(replayed, hash);
        }
        requireMinorUnit(request.amount());
        // A default date is today in the tenant's accounting time zone; an unset zone fails closed (#2558).
        LocalDate date = request.effectiveDate() != null ? request.effectiveDate() : zoneResolver.today();

        RegisterFloat registerFloat = lockOrCreate(register, request.locationId());
        // A concurrent duplicate of this request waited on the lock: it answers with the first result.
        RegisterFloatChange committed =
                changes.findByRequestId(request.requestId()).orElse(null);
        if (committed != null) {
            return replay(committed, hash);
        }
        requireRegisterLocation(registerFloat, request.locationId());
        BigDecimal previous = registerFloat.getAmount();
        BigDecimal difference = request.amount().subtract(previous);
        if (difference.signum() == 0) {
            throw new CashSetupException(
                    CashSetupException.Code.FLOAT_AMOUNT_UNCHANGED,
                    "Register " + register + " already has a float of "
                            + previous.stripTrailingZeros().toPlainString());
        }
        GLAccount bank = requireEligibleBankAccount(request.bankGlAccountId(), date);

        UUID floatAccount = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, FLOAT_KEY, date.atStartOfDay());
        UUID postingKey = UUIDv7Generator.generate();
        String description = "Change float for register " + register;
        BigDecimal abs = difference.abs();
        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        if (difference.signum() > 0) {
            lines.add(floatLine(floatAccount, abs, BigDecimal.ZERO, description, register, request.locationId()));
            lines.add(line(bank.getGlAccountId(), BigDecimal.ZERO, abs, description));
        } else {
            lines.add(line(bank.getGlAccountId(), abs, BigDecimal.ZERO, description));
            lines.add(floatLine(floatAccount, BigDecimal.ZERO, abs, description, register, request.locationId()));
        }
        JournalEntryResponse posted = post(postingKey, date, description, lines, override);

        registerFloat.setAmount(request.amount());
        RegisterFloatChange change = change(
                registerFloat,
                RegisterFloatChangeKind.CHANGE,
                previous,
                bank.getGlAccountId(),
                posted,
                date,
                request.justification().trim(),
                override,
                request.requestId(),
                hash);
        return finish(registerFloat, change, previous, posted, AUDIT_CHANGE);
    }

    // ---- posting --------------------------------------------------------------------------------------------

    private JournalEntryResponse post(
            UUID postingKey,
            LocalDate date,
            String description,
            List<JournalEntryCreateRequest.JournalEntryLineRequest> lines,
            @Nullable String overrideJustification) {
        JournalEntryResponse created = journalEntryService.createJournalEntry(JournalEntryCreateRequest.builder()
                .transactionDate(date.atStartOfDay())
                .sourceEventId(sourceEventId(postingKey))
                .sourceEventType(POSTING_CATEGORY)
                .description(description)
                .lines(lines)
                .build());
        return journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);
    }

    /** The source event of a float command's journal entry, derived from the command's own posting key. */
    public static @NonNull UUID sourceEventId(@NonNull UUID postingKey) {
        return UUID.nameUUIDFromBytes((POSTING_CATEGORY + ":" + postingKey).getBytes(StandardCharsets.UTF_8));
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest floatLine(
            UUID account,
            BigDecimal debit,
            BigDecimal credit,
            String description,
            String registerId,
            RegisterFloatGoLiveRequest request) {
        return floatLine(account, debit, credit, description, registerId, request.locationId());
    }

    /** The 1080 line carries the register and its location (§4.6: location dimension per register). */
    private static JournalEntryCreateRequest.JournalEntryLineRequest floatLine(
            UUID account, BigDecimal debit, BigDecimal credit, String description, String registerId, UUID locationId) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(debit)
                .creditAmount(credit)
                .description(description)
                .dimensions(Map.of("registerId", registerId, "locationId", locationId.toString()))
                .build();
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest line(
            UUID account, BigDecimal debit, BigDecimal credit, String description) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(debit)
                .creditAmount(credit)
                .description(description)
                .build();
    }

    // ---- rows -----------------------------------------------------------------------------------------------

    private RegisterFloat lockOrCreate(String registerId, UUID locationId) {
        return floats.lockByRegisterId(registerId).orElseGet(() -> {
            RegisterFloat created = new RegisterFloat();
            created.setRegisterId(registerId);
            created.setLocationId(locationId);
            created.setAmount(BigDecimal.ZERO);
            try {
                // The (tenant, register) unique is the backstop for two first commands racing.
                floats.saveAndFlush(created);
            } catch (DataIntegrityViolationException e) {
                throw new CashSetupException(
                        CashSetupException.Code.VERSION_CONFLICT,
                        "Register " + registerId + " was changed by a concurrent command; retry");
            }
            return floats.lockByRegisterId(registerId).orElseThrow();
        });
    }

    /**
     * A register belongs to the location of its first float command. The controller's location-scope gate judges
     * the request's {@code locationId}, so a command naming another location must not reach (or move) this
     * register: 422 FLOAT_REGISTER_LOCATION_MISMATCH, nothing posted. The register's own location is for the log,
     * never the response: the caller may be outside its scope.
     */
    private static void requireRegisterLocation(RegisterFloat registerFloat, UUID locationId) {
        if (!registerFloat.getLocationId().equals(locationId)) {
            log.warn(
                    "Float command for register {} named location {}; the register belongs to location {}",
                    registerFloat.getRegisterId(),
                    locationId,
                    registerFloat.getLocationId());
            throw new CashSetupException(
                    CashSetupException.Code.FLOAT_REGISTER_LOCATION_MISMATCH,
                    "Register " + registerFloat.getRegisterId() + " belongs to another location");
        }
    }

    private RegisterFloatChange change(
            RegisterFloat registerFloat,
            RegisterFloatChangeKind kind,
            BigDecimal previous,
            @Nullable UUID bankAccount,
            JournalEntryResponse posted,
            LocalDate date,
            String justification,
            @Nullable String override,
            UUID requestId,
            String hash) {
        RegisterFloatChange change = new RegisterFloatChange();
        change.setRegisterFloatId(registerFloat.getRegisterFloatId());
        change.setRegisterId(registerFloat.getRegisterId());
        change.setLocationId(registerFloat.getLocationId());
        change.setKind(kind);
        change.setPreviousAmount(previous);
        change.setNewAmount(registerFloat.getAmount());
        change.setBankGlAccountId(bankAccount);
        change.setJournalEntryId(posted.getJournalEntryId());
        change.setEffectiveDate(date);
        change.setJustification(justification);
        change.setOverrideJustification(override);
        change.setActor(currentActor());
        change.setRequestId(requestId);
        change.setRequestHash(hash);
        return change;
    }

    private Outcome finish(
            RegisterFloat registerFloat,
            RegisterFloatChange change,
            BigDecimal previous,
            JournalEntryResponse posted,
            String auditOperation) {
        RegisterFloat saved = floats.saveAndFlush(registerFloat);
        try {
            // The request unique is the backstop for one requestId racing itself.
            changes.saveAndFlush(change);
        } catch (DataIntegrityViolationException e) {
            throw new CashSetupException(
                    CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                    "requestId " + change.getRequestId() + " was concurrently used by another command");
        }
        AccountingAuditLog audit = new AccountingAuditLog();
        audit.setEntityType(AUDIT_ENTITY_TYPE);
        audit.setEntityId(saved.getRegisterFloatId());
        audit.setOperation(auditOperation);
        audit.setUserId(change.getActor());
        audit.setJustification(change.getJustification());
        audit.setOldValue("amount=" + previous.toPlainString());
        audit.setNewValue("registerId=" + saved.getRegisterId() + ";locationId=" + saved.getLocationId() + ";amount="
                + saved.getAmount().toPlainString() + ";bankGlAccountId=" + change.getBankGlAccountId()
                + ";requestId=" + change.getRequestId() + ";journalEntryId=" + posted.getJournalEntryId()
                + ";effectiveDate=" + change.getEffectiveDate() + ";override="
                + (change.getOverrideJustification() != null));
        auditLogs.save(audit);
        facts.changed(saved, RegisterFloatFacts.factOf(saved, change, previous), change.getActor());
        log.info(
                "Register {} float {} -> {} ({}, JE {})",
                saved.getRegisterId(),
                previous,
                saved.getAmount(),
                change.getKind(),
                posted.getJournalEntryId());
        return new Outcome(response(change, posted.getEntryNumber(), false), false);
    }

    private Outcome replay(RegisterFloatChange original, String hash) {
        if (!Objects.equals(original.getRequestHash(), hash)) {
            throw new CashSetupException(
                    CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                    "requestId " + original.getRequestId() + " was already used with a different payload");
        }
        String number = journalEntryService
                .getJournalEntry(original.getJournalEntryId())
                .getEntryNumber();
        return new Outcome(response(original, number, true), true);
    }

    private static RegisterFloatResponse response(RegisterFloatChange change, @Nullable String number, boolean replay) {
        return new RegisterFloatResponse(
                change.getRegisterId(),
                change.getLocationId(),
                change.getKind(),
                change.getPreviousAmount(),
                change.getNewAmount(),
                change.getEffectiveDate(),
                change.getJournalEntryId(),
                number,
                replay);
    }

    // ---- rules ----------------------------------------------------------------------------------------------

    /** An active BANK_CASH account in functional currency (ADR-0067), else 422 FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE. */
    private GLAccount requireEligibleBankAccount(UUID glAccountId, LocalDate date) {
        GLAccount account = glAccounts
                .findById(glAccountId)
                .orElseThrow(() -> notEligible("GL account " + glAccountId + " does not exist"));
        if (account.getAccountSubtype() != AccountSubtype.BANK_CASH) {
            throw notEligible("Account " + account.getAccountCode() + " is not a bank account");
        }
        LocalDateTime at = date.atStartOfDay();
        boolean active = (account.getActivationDate() == null
                        || !account.getActivationDate().isAfter(at))
                && (account.getDeactivationDate() == null
                        || account.getDeactivationDate().isAfter(at));
        if (!active) {
            throw notEligible("Bank account " + account.getAccountCode() + " is not active on " + date);
        }
        bankAccountCurrencies
                .currencyOf(glAccountId)
                .filter(ledgerCurrency::isForeign)
                .ifPresent(currency -> {
                    throw notEligible("Bank account " + account.getAccountCode() + " is in " + currency
                            + ", not the functional currency " + ledgerCurrency.code());
                });
        return account;
    }

    private void requireMinorUnit(BigDecimal amount) {
        int digits = Math.max(0, Currency.getInstance(ledgerCurrency.code()).getDefaultFractionDigits());
        if (amount.stripTrailingZeros().scale() > digits) {
            throw new InvalidRequestParameterException(
                    "amount has more decimal places than " + ledgerCurrency.code() + " allows (" + digits + ")");
        }
    }

    private static String requireRegisterId(String registerId) {
        String trimmed = registerId == null ? "" : registerId.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REGISTER_ID) {
            throw new InvalidRequestParameterException(
                    "registerId is required and must not exceed " + MAX_REGISTER_ID + " characters");
        }
        return trimmed;
    }

    private static CashSetupException notEligible(String message) {
        return new CashSetupException(CashSetupException.Code.FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE, message);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }

    /** SHA-256 over the fields that steer a command, so a reused requestId with another body is a conflict. */
    static final class Hash {
        private final StringBuilder text = new StringBuilder();

        Hash field(@Nullable Object value) {
            String rendered = value instanceof BigDecimal amount
                    ? amount.stripTrailingZeros().toPlainString()
                    : String.valueOf(value);
            text.append(value == null ? "\u0000" : rendered).append('\u001f');
            return this;
        }

        String digest() {
            try {
                return HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
            }
        }
    }
}
