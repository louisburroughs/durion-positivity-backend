package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PettyExpenseCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryDeactivateRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryListResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryRemapRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryUpdateRequest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryChange;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryChangeType;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryStatus;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryChangeRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link PettyExpenseCategoryService} (#2511). Idempotent on {@code requestId}: a replay with the same body
 * returns the category as it stands with {@code replayed} set; another body is 409 {@code
 * IDEMPOTENCY_CONFLICT}. "Today" is the tenant's accounting-calendar date (#2558); an unset zone fails closed.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PettyExpenseCategoryServiceImpl implements PettyExpenseCategoryService {

    static final String POSTING_CATEGORY = AccountingTemplate.PettyExpenseCategory.POSTING_CATEGORY;
    static final String KEY_PREFIX = AccountingTemplate.PettyExpenseCategory.KEY_PREFIX;

    /** No "Other" (AW18): a category nobody can audit. */
    static final String NOT_ALLOWED_CODE = "OTHER";

    static final String SOURCE_SYSTEM = "ACCOUNTING";

    private final PettyExpenseCategoryRepository categories;
    private final PettyExpenseCategoryChangeRepository changes;
    private final PostingCategoryRepository postingCategories;
    private final MappingKeyRepository mappingKeys;
    private final GLMappingRepository glMappings;
    private final GLAccountRepository glAccounts;
    private final PettyExpenseCategoryFacts facts;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final Clock clock;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    /** Whether a key belongs to a petty-expense category, so only this service may write it. */
    public static boolean isManagedKey(@Nullable String categoryName, @Nullable String keyName) {
        return POSTING_CATEGORY.equals(categoryName)
                && keyName != null
                && keyName.trim().startsWith(KEY_PREFIX);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PettyExpenseCategoryListResponse list() {
        LocalDate today = zoneResolver.today();
        Map<UUID, List<PettyExpenseCategoryChange>> history = changes.findAllByOrderByChangedAtAscChangeIdAsc().stream()
                .collect(Collectors.groupingBy(PettyExpenseCategoryChange::getPettyExpenseCategoryId));
        return new PettyExpenseCategoryListResponse(categories.findAllByOrderByCodeAsc().stream()
                .map(category ->
                        view(category, history.getOrDefault(category.getPettyExpenseCategoryId(), List.of()), today))
                .toList());
    }

    @Override
    public @NonNull PettyExpenseCategoryResponse create(@NonNull PettyExpenseCategoryCreateRequest request) {
        request.requireValid();
        String hash = new RegisterFloatServiceImpl.Hash()
                .field("CREATE")
                .field(request.code())
                .field(request.label().trim())
                .field(trimToNull(request.examples()))
                .field(request.glAccountId())
                .field(request.justification().trim())
                .digest();
        Optional<PettyExpenseCategoryResponse> replay = replay(request.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (NOT_ALLOWED_CODE.equals(request.code())) {
            throw new CashSetupException(
                    CashSetupException.Code.PETTY_EXPENSE_CATEGORY_NOT_ALLOWED,
                    "There is no \"Other\" petty-expense category: name what the cash was spent on");
        }
        if (categories.existsByCode(request.code())) {
            throw new CashSetupException(
                    CashSetupException.Code.PETTY_EXPENSE_CATEGORY_EXISTS,
                    "Petty-expense category " + request.code() + " already exists");
        }
        LocalDate today = zoneResolver.today();
        GLAccount account = requireExpenseAccount(request.glAccountId(), today);
        PostingCategory postingCategory = postingCategories
                .findByCategoryName(POSTING_CATEGORY)
                .orElseThrow(() ->
                        new GLMappingNotConfiguredException("Posting category not configured: " + POSTING_CATEGORY));
        String actor = RegisterFloatServiceImpl.currentActor();
        String keyName = KEY_PREFIX + request.code();
        if (mappingKeys
                .findByPostingCategory_PostingCategoryIdAndKeyName(postingCategory.getPostingCategoryId(), keyName)
                .isPresent()) {
            throw new CashSetupException(
                    CashSetupException.Code.PETTY_EXPENSE_CATEGORY_EXISTS,
                    "Mapping key " + keyName + " already exists under " + POSTING_CATEGORY);
        }
        MappingKey key = new MappingKey();
        key.setPostingCategory(postingCategory);
        key.setKeyName(keyName);
        key.setDescription(request.label().trim());
        key.setIsActive(true);
        key.setCreatedBy(actor);
        key.setModifiedBy(actor);
        key = mappingKeys.save(key);
        addMapping(postingCategory, key, account, today, actor);

        PettyExpenseCategory category = new PettyExpenseCategory();
        category.setMappingKeyId(key.getMappingKeyId());
        category.setCode(request.code());
        category.setLabel(request.label().trim());
        category.setExamples(trimToNull(request.examples()));
        category.setStatus(PettyExpenseCategoryStatus.ACTIVE);
        category.setCreatedBy(actor);
        category.setModifiedBy(actor);
        category = categories.saveAndFlush(category);
        PettyExpenseCategoryChange change = record(
                category,
                PettyExpenseCategoryChangeType.CREATE,
                null,
                category.getLabel() + " posting to " + account.getAccountCode() + " from " + today,
                request.justification(),
                actor,
                request.requestId(),
                hash);
        return remember(change, view(category, today));
    }

    @Override
    public @NonNull PettyExpenseCategoryResponse update(
            @NonNull String code, @NonNull PettyExpenseCategoryUpdateRequest request) {
        request.requireValid();
        String hash = new RegisterFloatServiceImpl.Hash()
                .field("RELABEL")
                .field(code)
                .field(request.label().trim())
                .field(trimToNull(request.examples()))
                .field(request.version())
                .field(request.justification().trim())
                .digest();
        Optional<PettyExpenseCategoryResponse> replay = replay(request.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        PettyExpenseCategory category = require(code);
        if (request.version() != null && request.version() != category.getVersion()) {
            throw new CashSetupException(
                    CashSetupException.Code.VERSION_CONFLICT,
                    "Petty-expense category " + code + " is at version " + category.getVersion() + ", not "
                            + request.version() + "; read it again");
        }
        String actor = RegisterFloatServiceImpl.currentActor();
        String before = describeLabel(category.getLabel(), category.getExamples());
        category.setLabel(request.label().trim());
        category.setExamples(trimToNull(request.examples()));
        category.setModifiedBy(actor);
        mappingKeys.findById(category.getMappingKeyId()).ifPresent(key -> {
            // The key's description mirrors the label (§7.1 "Seed").
            key.setDescription(category.getLabel());
            key.setModifiedBy(actor);
            mappingKeys.save(key);
        });
        PettyExpenseCategory saved = persistChange(category);
        PettyExpenseCategoryChange change = record(
                saved,
                PettyExpenseCategoryChangeType.RELABEL,
                before,
                describeLabel(saved.getLabel(), saved.getExamples()),
                request.justification(),
                actor,
                request.requestId(),
                hash);
        return remember(change, view(saved, zoneResolver.today()));
    }

    @Override
    public @NonNull PettyExpenseCategoryResponse deactivate(
            @NonNull String code, @NonNull PettyExpenseCategoryDeactivateRequest request) {
        request.requireValid();
        String hash = new RegisterFloatServiceImpl.Hash()
                .field("DEACTIVATE")
                .field(code)
                .field(request.justification().trim())
                .digest();
        Optional<PettyExpenseCategoryResponse> replay = replay(request.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        PettyExpenseCategory category = require(code);
        if (category.getStatus() == PettyExpenseCategoryStatus.INACTIVE) {
            throw new CashSetupException(
                    CashSetupException.Code.PETTY_EXPENSE_CATEGORY_INACTIVE,
                    "Petty-expense category " + code + " is already inactive");
        }
        String actor = RegisterFloatServiceImpl.currentActor();
        category.setStatus(PettyExpenseCategoryStatus.INACTIVE);
        category.setModifiedBy(actor);
        // The key and its mapping are kept: a movement recorded before deactivation still posts (§4.6 "never
        // retroactive"). Resolution by category and key does not read the key's active flag.
        PettyExpenseCategory saved = persistChange(category);
        PettyExpenseCategoryChange change = record(
                saved,
                PettyExpenseCategoryChangeType.DEACTIVATE,
                PettyExpenseCategoryStatus.ACTIVE.name(),
                PettyExpenseCategoryStatus.INACTIVE.name(),
                request.justification(),
                actor,
                request.requestId(),
                hash);
        return remember(change, view(saved, zoneResolver.today()));
    }

    @Override
    public @NonNull PettyExpenseCategoryResponse remap(
            @NonNull String code, @NonNull PettyExpenseCategoryRemapRequest request) {
        request.requireValid();
        String hash = new RegisterFloatServiceImpl.Hash()
                .field("REMAP")
                .field(code)
                .field(request.glAccountId())
                .field(request.effectiveFrom())
                .field(request.justification().trim())
                .digest();
        Optional<PettyExpenseCategoryResponse> replay = replay(request.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        PettyExpenseCategory category = require(code);
        LocalDate from = request.effectiveFrom();
        // Never retroactive (§4.6): an account change takes effect today or later in the tenant's accounting
        // calendar; an unset zone fails closed (#2558).
        LocalDate today = zoneResolver.today();
        if (from.isBefore(today)) {
            throw new CashSetupException(
                    CashSetupException.Code.PETTY_EXPENSE_ACCOUNT_CHANGE_BACKDATED,
                    "An account change takes effect today (" + today + ") or later, not " + from
                            + ": movements already recorded keep the account they were recorded against");
        }
        GLAccount account = requireExpenseAccount(request.glAccountId(), from);
        LocalDateTime start = from.atStartOfDay();
        List<GLMapping> mappings = undimensioned(category.getMappingKeyId());
        // The EXISTING non-overlap rule: nothing may start on or after the new mapping's start, and the mapping
        // covering that date ends there (the day before is its last day).
        if (mappings.stream().anyMatch(m -> !m.getEffectiveStartDate().isBefore(start))) {
            throw new CashSetupException(
                    CashSetupException.Code.PETTY_EXPENSE_MAPPING_OVERLAP,
                    "Petty-expense category " + code + " already has an account from " + from + " or later");
        }
        GLMapping current = mappings.stream()
                .filter(m -> m.isEffectiveOn(start))
                .findFirst()
                .orElse(null);
        String actor = RegisterFloatServiceImpl.currentActor();
        String before = current == null ? null : current.getGlAccount().getAccountCode();
        if (current != null) {
            current.setEffectiveEndDate(start);
            glMappings.save(current);
        }
        MappingKey key = mappingKeys.findById(category.getMappingKeyId()).orElseThrow();
        addMapping(key.getPostingCategory(), key, account, from, actor);
        category.setModifiedBy(actor);
        PettyExpenseCategory saved = persistChange(category);
        PettyExpenseCategoryChange change = record(
                saved,
                PettyExpenseCategoryChangeType.REMAP,
                before,
                account.getAccountCode() + " from " + from,
                request.justification(),
                actor,
                request.requestId(),
                hash);
        return remember(change, view(saved, zoneResolver.today()));
    }

    // ---- writes ---------------------------------------------------------------------------------------------

    private void addMapping(
            PostingCategory postingCategory, MappingKey key, GLAccount account, LocalDate from, String actor) {
        GLMapping mapping = new GLMapping();
        mapping.setSourceSystem(SOURCE_SYSTEM);
        mapping.setExternalCode(key.getKeyName());
        mapping.setPostingCategory(postingCategory);
        mapping.setMappingKey(key);
        mapping.setGlAccount(account);
        mapping.setEffectiveStartDate(from.atStartOfDay());
        mapping.setCreatedBy(actor);
        glMappings.save(mapping);
    }

    private PettyExpenseCategoryChange record(
            PettyExpenseCategory category,
            PettyExpenseCategoryChangeType type,
            @Nullable String oldValue,
            String newValue,
            String justification,
            String actor,
            UUID requestId,
            String hash) {
        PettyExpenseCategoryChange change = new PettyExpenseCategoryChange();
        change.setPettyExpenseCategoryId(category.getPettyExpenseCategoryId());
        change.setCode(category.getCode());
        change.setChangeType(type);
        change.setOldValue(oldValue);
        change.setNewValue(newValue);
        change.setActor(actor);
        change.setJustification(justification.trim());
        change.setRequestId(requestId);
        change.setRequestHash(hash);
        change.setChangedAt(Instant.now(clock));
        try {
            // The request unique is the backstop for one requestId racing itself.
            changes.saveAndFlush(change);
        } catch (DataIntegrityViolationException e) {
            throw new CashSetupException(
                    CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                    "requestId " + requestId + " was concurrently used by another command");
        }
        facts.changed(category, actor);
        log.info("Petty-expense category {} {} by {}", category.getCode(), type, actor);
        return change;
    }

    /** Keeps the command's response with its history row, so a replay returns exactly the first result. */
    private PettyExpenseCategoryResponse remember(
            PettyExpenseCategoryChange change, PettyExpenseCategoryResponse response) {
        change.setResponseJson(objectMapper.writeValueAsString(response));
        changes.saveAndFlush(change);
        return response;
    }

    // ---- reads ----------------------------------------------------------------------------------------------

    private Optional<PettyExpenseCategoryResponse> replay(UUID requestId, String hash) {
        return changes.findByRequestId(requestId).map(original -> {
            if (!Objects.equals(original.getRequestHash(), hash)) {
                throw new CashSetupException(
                        CashSetupException.Code.IDEMPOTENCY_CONFLICT,
                        "requestId " + requestId + " was already used with a different payload");
            }
            return objectMapper
                    .readValue(original.getResponseJson(), PettyExpenseCategoryResponse.class)
                    .asReplay();
        });
    }

    /**
     * Saves the category and raises its version whether or not a column of the row changed (an account change
     * touches only the GL mappings), so every fact a command queues carries a version the consumer has not seen
     * (ADR-0044 §3). The row stays locked to the end of the transaction.
     */
    private PettyExpenseCategory persistChange(PettyExpenseCategory category) {
        PettyExpenseCategory saved = categories.saveAndFlush(category);
        entityManager.lock(saved, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        return saved;
    }

    private PettyExpenseCategory require(String code) {
        return categories.findByCode(code).orElseThrow(notFound(code));
    }

    private static Supplier<CashSetupException> notFound(String code) {
        return () -> new CashSetupException(
                CashSetupException.Code.PETTY_EXPENSE_CATEGORY_NOT_FOUND, "No petty-expense category " + code);
    }

    /** An active EXPENSE account, else 422 PETTY_EXPENSE_ACCOUNT_NOT_ELIGIBLE. */
    private GLAccount requireExpenseAccount(UUID glAccountId, LocalDate on) {
        GLAccount account = glAccounts
                .findById(glAccountId)
                .orElseThrow(() -> notEligible("GL account " + glAccountId + " does not exist"));
        if (account.getAccountType() != AccountType.EXPENSE) {
            throw notEligible("Account " + account.getAccountCode() + " is not an expense account");
        }
        LocalDateTime at = on.atStartOfDay();
        boolean active = (account.getActivationDate() == null
                        || !account.getActivationDate().isAfter(at))
                && (account.getDeactivationDate() == null
                        || account.getDeactivationDate().isAfter(at));
        if (!active) {
            throw notEligible("Expense account " + account.getAccountCode() + " is not active on " + on);
        }
        return account;
    }

    private static CashSetupException notEligible(String message) {
        return new CashSetupException(CashSetupException.Code.PETTY_EXPENSE_ACCOUNT_NOT_ELIGIBLE, message);
    }

    private List<GLMapping> undimensioned(UUID mappingKeyId) {
        return glMappings.findByMappingKey_MappingKeyId(mappingKeyId).stream()
                .filter(m -> m.getDimensions() == null || m.getDimensions().isEmpty())
                .sorted(Comparator.comparing(GLMapping::getEffectiveStartDate))
                .toList();
    }

    private PettyExpenseCategoryResponse view(PettyExpenseCategory category, LocalDate today) {
        List<PettyExpenseCategoryChange> history = changes.findAllByOrderByChangedAtAscChangeIdAsc().stream()
                .filter(change -> change.getPettyExpenseCategoryId().equals(category.getPettyExpenseCategoryId()))
                .toList();
        return view(category, history, today);
    }

    private PettyExpenseCategoryResponse view(
            PettyExpenseCategory category, List<PettyExpenseCategoryChange> history, LocalDate today) {
        LocalDateTime at = today.atStartOfDay();
        List<GLMapping> mappings = undimensioned(category.getMappingKeyId());
        PettyExpenseCategoryResponse.Account current = mappings.stream()
                .filter(m -> m.isEffectiveOn(at))
                .findFirst()
                .map(PettyExpenseCategoryServiceImpl::account)
                .orElse(null);
        PettyExpenseCategoryResponse.Account later = mappings.stream()
                .filter(m -> m.getEffectiveStartDate().isAfter(at))
                .findFirst()
                .map(PettyExpenseCategoryServiceImpl::account)
                .orElse(null);
        return new PettyExpenseCategoryResponse(
                category.getCode(),
                category.getLabel(),
                category.getExamples(),
                category.getStatus(),
                category.getVersion(),
                current,
                later,
                history.stream()
                        .map(change -> new PettyExpenseCategoryResponse.HistoryItem(
                                change.getChangedAt(),
                                change.getActor(),
                                change.getChangeType(),
                                change.getOldValue(),
                                change.getNewValue(),
                                change.getJustification()))
                        .toList(),
                false);
    }

    private static PettyExpenseCategoryResponse.Account account(GLMapping mapping) {
        GLAccount account = mapping.getGlAccount();
        return new PettyExpenseCategoryResponse.Account(
                account.getGlAccountId(),
                account.getAccountCode(),
                account.getAccountName(),
                mapping.getEffectiveStartDate().toLocalDate());
    }

    private static String describeLabel(String label, @Nullable String examples) {
        return examples == null ? label : label + " (" + examples + ")";
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
