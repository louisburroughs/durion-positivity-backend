package com.positivity.inventory.internal.config;

import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Says out loud, once per boot, whether the enabled putaway rules point at storage locations that
 * actually exist (issue #1543).
 *
 * <p><strong>It informs; it never fails startup.</strong> On alpha, the terminal {@code ANY} rule
 * carried a destination that was never a {@code storage_location} row, and because {@code ANY} is
 * the fallback tier that catches every SKU, every putaway execution on the environment failed —
 * discovered only by symptom, one refused execution at a time. An unresolvable terminal fallback is
 * a configuration state worth announcing at boot rather than at the first receipt, so this check
 * reports it at ERROR; an unresolvable non-{@code ANY} rule breaks only the lines it matches and is
 * reported at WARN.
 *
 * <p>Existence is judged against the {@code ext_storage_location} replica, the same source
 * execution's {@code StorageLocationValidationService} consults — so what this check calls broken
 * is exactly what {@code executePutaway} will refuse. A completely empty replica is the one state
 * this cannot distinguish from misconfiguration: on a freshly provisioned environment the
 * location facts may simply not have arrived yet, so that case gets a single WARN naming the
 * hydration gap instead of a false alarm per rule.
 */
@Component
public class PutawayRuleDestinationStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PutawayRuleDestinationStartupCheck.class);

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PutawayRuleRepository putawayRuleRepository;
    private final ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;

    public PutawayRuleDestinationStartupCheck(
            PutawayRuleRepository putawayRuleRepository,
            ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository) {
        this.putawayRuleRepository = putawayRuleRepository;
        this.extStorageLocationReplicaRepository = extStorageLocationReplicaRepository;
    }

    @Override
    @SuppressWarnings("java:S1181") // see catch block: an advisory log line must not be able to stop a boot
    public void run(ApplicationArguments args) {
        try {
            check();
        } catch (Throwable t) {
            // Deliberately Throwable, not Exception: this class promises never to block startup,
            // and the promise must hold even for an Error out of the persistence layer.
            log.warn("Putaway rule destination startup check did not complete: {}", t.toString());
        }
    }

    private void check() {
        List<PutawayRule> enabledRules = putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc();
        Set<UUID> destinationIds = enabledRules.stream()
                .map(PutawayRule::getDestinationLocationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (destinationIds.isEmpty()) {
            return;
        }

        if (extStorageLocationReplicaRepository.count() == 0) {
            log.warn(
                    "The ext_storage_location replica is empty, so the destinations of {} enabled putaway rule(s)"
                            + " cannot be verified. If this environment has storage locations, pos-location's facts"
                            + " have not been replicated yet and putaway execution will fail until they are.",
                    enabledRules.size());
            return;
        }

        Map<UUID, ExtStorageLocationReplica> replicasById =
                extStorageLocationReplicaRepository.findAllById(destinationIds).stream()
                        .collect(
                                Collectors.toMap(ExtStorageLocationReplica::getStorageLocationId, Function.identity()));

        for (PutawayRule rule : enabledRules) {
            UUID destinationId = rule.getDestinationLocationId();
            if (destinationId == null) {
                continue;
            }
            ExtStorageLocationReplica replica = replicasById.get(destinationId);
            if (replica == null) {
                reportMissing(rule, destinationId);
            } else if (!STATUS_ACTIVE.equalsIgnoreCase(replica.getStatus())) {
                log.warn(
                        "Enabled putaway rule {} ({}) targets storage location {} whose status is {}, not ACTIVE."
                                + " Putaway execution into it will be refused.",
                        rule.getRuleId(),
                        rule.getMatchType(),
                        destinationId,
                        replica.getStatus());
            }
        }
    }

    private void reportMissing(PutawayRule rule, UUID destinationId) {
        if (rule.getMatchType() == PutawayRuleMatchType.ANY) {
            // The terminal fallback catches every SKU no more specific rule claims, so an
            // unresolvable destination here fails every putaway execution, not one route.
            log.error(
                    "The enabled ANY putaway rule {} targets storage location {}, which does not exist in the"
                            + " ext_storage_location replica. ANY is the terminal fallback, so EVERY putaway"
                            + " execution will fail with 'Destination storage location does not exist' until the"
                            + " rule is retargeted at a real bin (issue #1543).",
                    rule.getRuleId(),
                    destinationId);
        } else {
            log.warn(
                    "Enabled putaway rule {} ({}) targets storage location {}, which does not exist in the"
                            + " ext_storage_location replica. Putaway execution for lines this rule matches will"
                            + " fail until it is retargeted at a real bin.",
                    rule.getRuleId(),
                    rule.getMatchType(),
                    destinationId);
        }
    }
}
