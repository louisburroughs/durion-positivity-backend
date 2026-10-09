package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.dto.InputTaxRecoveryResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryTaxRecoveryRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryTaxRecoveryResponse;
import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryTaxSetting;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryTaxSettingChange;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryTaxSettingChangeRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryTaxSettingRepository;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Input-tax recovery settings (CAP:550 S32d items 2 and 4; AW49, AW52): the settings read, a petty-expense
 * category's recovery, and the share in force when a movement was recorded.
 *
 * <p><b>Category recovery.</b> A category without a setting is not recoverable. A change writes the setting, a
 * history row in force from now, and queues {@code accounting.petty-expense-category.changed} with the new values.
 * It is refused with 422 {@code INPUT_TAX_RECOVERY_NOT_ENABLED} while no regime's recovery is on today: a share that
 * nothing can claim would only mislead. Idempotent on {@code requestId}: a replay with the same body returns the
 * first result; another body is 409 {@code IDEMPOTENCY_CONFLICT}. A stale {@code version} is 409 {@code
 * OPTIMISTIC_LOCK}. The actor comes from the security context (ADR-0018).
 *
 * <p><b>The share at posting</b> is the one in force when the movement was recorded, read from the history, never from
 * pos-order's copy (AW52).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class InputTaxRecoveryService {

    /** The posting category drawer recovery posts under (S15's petty expenses). */
    static final String REGISTER_CASH_MOVEMENT = AccountingTemplate.PettyExpenseCategory.POSTING_CATEGORY;

    /** The prefix of a regime's drawer recovery key: {@code INPUT_TAX_<regime>}. */
    public static final String INPUT_TAX_KEY_PREFIX = "INPUT_TAX_";

    private final InputTaxRecoveryFlags flags;
    private final TaxProfileClient taxProfiles;
    private final PettyExpenseCategoryRepository categories;
    private final PettyExpenseCategoryTaxSettingRepository settings;
    private final PettyExpenseCategoryTaxSettingChangeRepository settingChanges;
    private final GLMappingResolver glMappingResolver;
    private final GLAccountRepository glAccounts;
    private final PettyExpenseCategoryFacts facts;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** The version a category never set reads as; a stored setting always reads higher (#2664 review A5). */
    static final int NEVER_SET = 0;

    /**
     * The version a stored setting reads as: its row version plus one, so a first stored setting (row version 0) is
     * never mistaken for a category never set.
     */
    static int publicVersion(@NonNull PettyExpenseCategoryTaxSetting setting) {
        return (setting.getVersion() == null ? 0 : setting.getVersion()) + 1;
    }

    /** The mapping key a regime's recovered drawer tax posts to. */
    public static @NonNull String inputTaxKey(@NonNull String regime) {
        return INPUT_TAX_KEY_PREFIX + regime;
    }

    /** The settings read (item 2). The regimes come from the tenant's registrations, so a USD tenant sees none. */
    @Transactional(readOnly = true)
    public @NonNull InputTaxRecoveryResponse read() {
        LocalDate today = zoneResolver.today();
        Instant asOf = Instant.now(clock);
        List<InputTaxRecoveryResponse.Regime> regimes = regimes(today);
        Map<String, PettyExpenseCategoryTaxSetting> byCode = settings.findAllByOrderByCodeAsc().stream()
                .collect(Collectors.toMap(PettyExpenseCategoryTaxSetting::getCode, Function.identity()));
        List<InputTaxRecoveryResponse.Category> categoryRows = categories.findAllByOrderByCodeAsc().stream()
                .map(category -> {
                    PettyExpenseCategoryTaxSetting setting = byCode.get(category.getCode());
                    return new InputTaxRecoveryResponse.Category(
                            category.getCode(),
                            category.getLabel(),
                            setting != null && setting.isTaxRecoverable(),
                            setting == null ? null : setting.getRecoverablePercent(),
                            setting == null ? NEVER_SET : publicVersion(setting));
                })
                .toList();
        List<InputTaxRecoveryResponse.HistoryItem> history =
                settingChanges.findAllByOrderByEffectiveFromAscCreatedAtAsc().stream()
                        .map(change -> new InputTaxRecoveryResponse.HistoryItem(
                                change.getEffectiveFrom(),
                                change.getCode(),
                                change.getActor(),
                                change.getActorRole(),
                                change.getOldTaxRecoverable(),
                                change.getOldRecoverablePercent(),
                                change.isNewTaxRecoverable(),
                                change.getNewRecoverablePercent(),
                                change.getJustification()))
                        .toList();
        return new InputTaxRecoveryResponse(regimes, evidenceRules(regimes, today), categoryRows, history, asOf);
    }

    /** A petty-expense category's recovery (item 4). */
    public @NonNull PettyExpenseCategoryTaxRecoveryResponse setCategoryRecovery(
            @NonNull String code, @NonNull PettyExpenseCategoryTaxRecoveryRequest request) {
        request.requireValid();
        BigDecimal percent = request.taxRecoverable() ? request.recoverablePercent() : null;
        String hash = new RegisterFloatServiceImpl.Hash()
                .field("TAX_RECOVERY")
                .field(code)
                .field(request.taxRecoverable())
                .field(percent)
                .field(request.version())
                .field(request.justification().trim())
                .digest();
        Optional<PettyExpenseCategoryTaxRecoveryResponse> replay = replay(request, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        PettyExpenseCategory category = categories
                .findByCode(code)
                .orElseThrow(() -> new CashSetupException(
                        CashSetupException.Code.PETTY_EXPENSE_CATEGORY_NOT_FOUND, "No petty-expense category " + code));
        if (!flags.anyEnabled(zoneResolver.today())) {
            throw new CashSetupException(
                    CashSetupException.Code.INPUT_TAX_RECOVERY_NOT_ENABLED,
                    "No regime's input-tax recovery is on today, so a category's recovery cannot be set");
        }
        // #2664 review A5: the category row is locked before the setting is read, so two first settings serialise here
        // and the second meets the first's version (a clean 409 OPTIMISTIC_LOCK) instead of a unique-key violation. The
        // lock raises the category's version too, which the fact below carries (ADR-0044 §3).
        entityManager.lock(category, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        Optional<PettyExpenseCategoryTaxSetting> existing = settings.findByCode(code);
        int currentVersion =
                existing.map(InputTaxRecoveryService::publicVersion).orElse(NEVER_SET);
        if (request.version() != currentVersion) {
            throw new CashSetupException(
                    CashSetupException.Code.OPTIMISTIC_LOCK,
                    "The tax recovery of petty-expense category " + code + " is at version " + currentVersion + ", not "
                            + request.version() + "; read it again");
        }
        String actor = RegisterFloatServiceImpl.currentActor();
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        PettyExpenseCategoryTaxSetting setting = existing.orElseGet(() -> {
            PettyExpenseCategoryTaxSetting created = new PettyExpenseCategoryTaxSetting();
            created.setPettyExpenseCategoryId(category.getPettyExpenseCategoryId());
            created.setCode(code);
            created.setCreatedBy(actor);
            return created;
        });
        Boolean oldRecoverable =
                existing.map(PettyExpenseCategoryTaxSetting::isTaxRecoverable).orElse(null);
        BigDecimal oldPercent = existing.map(PettyExpenseCategoryTaxSetting::getRecoverablePercent)
                .orElse(null);
        setting.setTaxRecoverable(request.taxRecoverable());
        setting.setRecoverablePercent(percent);
        setting.setModifiedBy(actor);
        PettyExpenseCategoryTaxSetting saved = settings.saveAndFlush(setting);

        PettyExpenseCategoryTaxSettingChange change = new PettyExpenseCategoryTaxSettingChange();
        change.setPettyExpenseCategoryId(category.getPettyExpenseCategoryId());
        change.setCode(code);
        change.setEffectiveFrom(now);
        change.setOldTaxRecoverable(oldRecoverable);
        change.setOldRecoverablePercent(oldPercent);
        change.setNewTaxRecoverable(request.taxRecoverable());
        change.setNewRecoverablePercent(percent);
        change.setActor(actor);
        change.setActorRole(currentRole());
        change.setJustification(request.justification().trim());
        change.setRequestId(request.requestId());
        change.setRequestHash(hash);
        PettyExpenseCategoryTaxRecoveryResponse response = new PettyExpenseCategoryTaxRecoveryResponse(
                code, saved.isTaxRecoverable(), saved.getRecoverablePercent(), publicVersion(saved), now, false);
        change.setResponseJson(objectMapper.writeValueAsString(response));
        settingChanges.saveAndFlush(change);

        facts.changed(category, actor);
        log.info(
                "Petty-expense category tax recovery set | code={} | taxRecoverable={} | recoverablePercent={}"
                        + " | version={}",
                code,
                saved.isTaxRecoverable(),
                saved.getRecoverablePercent(),
                publicVersion(saved));
        return response;
    }

    /**
     * The share of {@code code}'s stated tax recovered for a movement recorded at {@code recordedAt}: the setting in
     * force then (AW52). Empty when the category was not recoverable then, or had never been set.
     */
    @Transactional(readOnly = true)
    public @NonNull Optional<BigDecimal> shareInForce(@NonNull String code, @NonNull Instant recordedAt) {
        return settingChanges
                .findFirstByCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDescCreatedAtDesc(code, recordedAt)
                .filter(PettyExpenseCategoryTaxSettingChange::isNewTaxRecoverable)
                .map(PettyExpenseCategoryTaxSettingChange::getNewRecoverablePercent);
    }

    private List<InputTaxRecoveryResponse.Regime> regimes(LocalDate today) {
        List<InputTaxRecoveryResponse.Regime> rows = new ArrayList<>();
        List<InputTaxRecoveryFlags.RegimeFlag> flagged;
        try {
            flagged = flags.regimes(today);
        } catch (TaxServiceUnavailableException e) {
            // The read never fails because of pos-tax: whether recovery is on is then unknown, never "off" (AW49).
            log.warn("Input-tax recovery read without the tax configuration: enabled is unknown");
            flagged = flags.regimesUnchecked(today);
            return flagged.stream().map(flag -> row(flag, null, today)).toList();
        }
        for (InputTaxRecoveryFlags.RegimeFlag flag : flagged) {
            rows.add(row(flag, flag.enabled(), today));
        }
        return List.copyOf(rows);
    }

    private InputTaxRecoveryResponse.Regime row(
            InputTaxRecoveryFlags.RegimeFlag flag, @Nullable Boolean enabled, LocalDate today) {
        ExtTaxRegistration registration = flag.registration();
        return new InputTaxRecoveryResponse.Regime(
                flag.countryCode(),
                flag.regime(),
                enabled,
                registration == null
                        ? null
                        : new InputTaxRecoveryResponse.Registration(
                                registration.getRegistrationNumber(), registration.getEffectiveFrom()),
                account(inputTaxKey(flag.regime()), today));
    }

    private InputTaxRecoveryResponse.@Nullable Account account(String keyName, LocalDate today) {
        try {
            return glAccounts
                    .findById(glMappingResolver.resolveGLAccount(REGISTER_CASH_MOVEMENT, keyName, today.atStartOfDay()))
                    .map(InputTaxRecoveryService::account)
                    .orElse(null);
        } catch (GLMappingNotConfiguredException | IllegalArgumentException e) {
            return null;
        }
    }

    private static InputTaxRecoveryResponse.Account account(GLAccount account) {
        return new InputTaxRecoveryResponse.Account(account.getAccountCode(), account.getAccountName());
    }

    private @Nullable List<InputTaxRecoveryResponse.CountryEvidenceRules> evidenceRules(
            List<InputTaxRecoveryResponse.Regime> regimes, LocalDate today) {
        Set<String> countries = new LinkedHashSet<>();
        regimes.forEach(regime -> countries.add(regime.countryCode()));
        List<InputTaxRecoveryResponse.CountryEvidenceRules> rules = new ArrayList<>();
        try {
            for (String country : countries) {
                TaxProfileClient.EvidenceRules read = taxProfiles.evidenceRules(country, today);
                rules.add(new InputTaxRecoveryResponse.CountryEvidenceRules(
                        country,
                        read.currency(),
                        read.rules().stream()
                                .map(rule -> new InputTaxRecoveryResponse.EvidenceRule(
                                        rule.rule(),
                                        rule.fromAmount(),
                                        rule.appliesTo() == null ? List.of() : List.copyOf(rule.appliesTo())))
                                .toList()));
            }
        } catch (TaxServiceUnavailableException e) {
            log.warn("Input-tax recovery read without the evidence rules: the tax service did not answer");
            return null;
        }
        return List.copyOf(rules);
    }

    private Optional<PettyExpenseCategoryTaxRecoveryResponse> replay(
            PettyExpenseCategoryTaxRecoveryRequest request, String hash) {
        return settingChanges.findByRequestId(request.requestId()).map(original -> {
            if (!hash.equals(original.getRequestHash())) {
                throw new CashSetupException(
                        CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                        "requestId " + request.requestId() + " was used with another body");
            }
            return objectMapper
                    .readValue(original.getResponseJson(), PettyExpenseCategoryTaxRecoveryResponse.class)
                    .asReplay();
        });
    }

    /** The role the caller acts in, from its authorities ({@code ROLE_*}), when it carries one. */
    private static @Nullable String currentRole() {
        if (!SecurityContextHelper.isAuthenticated()) {
            return null;
        }
        return SecurityContextHelper.getAuthorities().stream()
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted()
                .findFirst()
                .orElse(null);
    }
}
