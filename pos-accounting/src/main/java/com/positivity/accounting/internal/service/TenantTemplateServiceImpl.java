package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.EnableTemplateAddOnRequest;
import com.positivity.accounting.internal.dto.TenantTemplateStatusResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.entity.AccountingTemplateEntry;
import com.positivity.accounting.internal.entity.AccountingTemplateState;
import com.positivity.accounting.internal.enums.TemplateEntryOutcome;
import com.positivity.accounting.internal.enums.TenantTemplateState;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.repository.AccountingTemplateEntryRepository;
import com.positivity.accounting.internal.repository.AccountingTemplateStateRepository;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TenantTemplateService} for the tenant bound to the request. The tenant is never taken from
 * the request itself.
 *
 * <p>A request thread never switches tenant, so nothing here reads the platform tenant: it works
 * from the template the startup sweep or the {@code tenant.created} listener already read
 * ({@link AccountingTemplateReader#loaded()}). When no read has happened in this process, the
 * status cannot tell {@code PENDING} from {@code UP_TO_DATE} and reports what was last applied, and
 * a newly chosen add-on is recorded and reaches the tenant at the next start.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantTemplateServiceImpl implements TenantTemplateService {

    /** {@code AccountingAuditLog.operation} of an add-on choice. */
    public static final String AUDIT_OPERATION_ADD_ON = "TENANT_TEMPLATE_ADD_ON_ENABLE";

    private static final String AUDIT_ENTITY_TYPE = "ACCOUNTING_CONFIGURATION";
    private static final String SYSTEM = "SYSTEM";
    private static final List<TemplateEntryOutcome> OPEN =
            List.of(TemplateEntryOutcome.CONFLICT, TemplateEntryOutcome.WITHHELD);

    private final AccountingTemplateStateRepository states;
    private final AccountingTemplateEntryRepository entryRecords;
    private final AccountingConfigurationRepository configuration;
    private final AccountingAuditLogRepository auditLogs;
    private final AccountingTemplateStateLock stateLock;
    private final AccountingTemplateReader templateReader;
    private final AccountingTenantProvisioner provisioner;
    private final RetreadPlantAddOnSource retreadPlantAddOn;

    @Override
    @Transactional(readOnly = true)
    public TenantTemplateStatusResponse status() {
        return currentStatus(TenantContext.require());
    }

    @Override
    @Transactional
    public TenantTemplateStatusResponse enableRetreadPlantAddOn(@NonNull EnableTemplateAddOnRequest request) {
        UUID tenantId = TenantContext.require();
        // The tenant's state row first: it serialises this with every template run for the tenant,
        // and two callers choosing at once queue up here instead of racing to insert the choice.
        stateLock.acquire();
        if (retreadPlantAddOn.appliesTo(tenantId)) {
            log.info(
                    "Retread-plant add-on already on for tenant {}; requestId={} changes nothing",
                    tenantId,
                    request.requestId());
            return currentStatus(tenantId);
        }

        AccountingConfiguration choice = configuration
                .findByConfigKey(RetreadPlantAddOnSource.CONFIG_KEY)
                .orElseGet(() -> {
                    AccountingConfiguration created = new AccountingConfiguration();
                    created.setConfigKey(RetreadPlantAddOnSource.CONFIG_KEY);
                    return created;
                });
        String previous = choice.getConfigValue();
        choice.setConfigValue(RetreadPlantAddOnSource.ON);
        AccountingConfiguration saved = configuration.save(choice);

        String actor = SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
        AccountingAuditLog audit = new AccountingAuditLog();
        audit.setEntityType(AUDIT_ENTITY_TYPE);
        audit.setEntityId(saved.getConfigId());
        audit.setOperation(AUDIT_OPERATION_ADD_ON);
        audit.setUserId(actor);
        audit.setJustification(request.justification());
        audit.setOldValue(previous);
        audit.setNewValue(RetreadPlantAddOnSource.CONFIG_KEY + "=" + RetreadPlantAddOnSource.ON + " requestId="
                + request.requestId());
        audit.setTraceId(MDC.get("traceId"));
        auditLogs.save(audit);
        log.info(
                "Retread-plant add-on turned on for tenant {} by {} (requestId={})",
                tenantId,
                actor,
                request.requestId());

        Optional<AccountingTemplate> snapshot = templateReader.loaded();
        if (snapshot.isPresent()) {
            provisioner.reconcile(tenantId, snapshot.get());
        } else {
            log.warn(
                    "Retread-plant add-on recorded for tenant {} but the accounting template has not been read in"
                            + " this process; the tenant receives the add-on at the next start",
                    tenantId);
        }
        return currentStatus(tenantId);
    }

    private TenantTemplateStatusResponse currentStatus(UUID tenantId) {
        boolean addOn = retreadPlantAddOn.appliesTo(tenantId);
        Optional<AccountingTemplateState> state =
                states.findCurrent().filter(row -> row.getTemplateFingerprint() != null);
        if (state.isEmpty()) {
            return new TenantTemplateStatusResponse(
                    TenantTemplateState.NOT_PROVISIONED,
                    null,
                    new TenantTemplateStatusResponse.Counts(0, 0, 0, 0, 0),
                    addOn,
                    List.of());
        }
        AccountingTemplateState applied = state.get();
        List<TenantTemplateStatusResponse.AttentionItem> attention =
                entryRecords.findByOutcomeInOrderByEntryKeyAsc(OPEN).stream()
                        .map(TenantTemplateServiceImpl::attentionItem)
                        .toList();
        TenantTemplateState status;
        if (!attention.isEmpty()) {
            status = TenantTemplateState.NEEDS_ATTENTION;
        } else if (templateIsNewer(tenantId, applied)) {
            status = TenantTemplateState.PENDING;
        } else {
            status = TenantTemplateState.UP_TO_DATE;
        }
        return new TenantTemplateStatusResponse(
                status,
                applied.getLastAppliedAt(),
                new TenantTemplateStatusResponse.Counts(
                        applied.getCreatedCount(),
                        applied.getAdoptedCount(),
                        applied.getRefreshedCount(),
                        applied.getConflictCount(),
                        applied.getWithheldCount()),
                addOn,
                attention);
    }

    private boolean templateIsNewer(UUID tenantId, AccountingTemplateState applied) {
        return templateReader
                .loaded()
                .map(snapshot -> provisioner.templateFor(tenantId, snapshot).fingerprint())
                .filter(fingerprint -> !fingerprint.equals(applied.getTemplateFingerprint()))
                .isPresent();
    }

    private static TenantTemplateStatusResponse.AttentionItem attentionItem(AccountingTemplateEntry entry) {
        return new TenantTemplateStatusResponse.AttentionItem(
                entry.getEntryKey(),
                entry.getKind(),
                entry.getReason(),
                entry.getTemplateValue(),
                entry.getTenantValue());
    }
}
