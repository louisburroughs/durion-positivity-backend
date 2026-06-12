package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.client.StorageLocationTopologyClient;
import com.positivity.inventory.internal.client.StorageLocationTopologyClient.StorageLocationNode;
import com.positivity.inventory.internal.dto.rollup.RollupQuantities;
import com.positivity.inventory.internal.dto.rollup.SiteInventoryRollupResponse;
import com.positivity.inventory.internal.dto.rollup.StorageLocationRollupNode;
import com.positivity.inventory.service.SiteInventoryRollupService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Computes the site inventory rollup tree: fetches the storage-location
 * topology from pos-location (outside any transaction), loads bulk per-location
 * quantities in one read-only transaction, then sums own quantities up the tree
 * depth-first post-order.
 *
 * <p>Orphan handling (v1, CAP-218 #658): bulk queries are restricted to the
 * site's known location ids, so ledger rows at unknown locations never enter
 * the tree; no dedicated orphan scan is performed.
 */
@Service
public class SiteInventoryRollupServiceImpl implements SiteInventoryRollupService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SiteInventoryRollupServiceImpl.class);

    private final StorageLocationTopologyClient storageLocationTopologyClient;
    private final SiteInventoryQuantityLoader quantityLoader;

    public SiteInventoryRollupServiceImpl(
            StorageLocationTopologyClient storageLocationTopologyClient, SiteInventoryQuantityLoader quantityLoader) {
        this.storageLocationTopologyClient = storageLocationTopologyClient;
        this.quantityLoader = quantityLoader;
    }

    @Override
    @NonNull
    public SiteInventoryRollupResponse getSiteInventoryRollup(
            @NonNull UUID siteId, @Nullable String sku, @Nullable Integer depth, boolean includeEmpty) {
        List<StorageLocationNode> topology = storageLocationTopologyClient.fetchSiteTopology(siteId);
        if (topology.isEmpty()) {
            return new SiteInventoryRollupResponse(siteId, RollupQuantities.ZERO, List.of());
        }

        List<UUID> knownIds = topology.stream().map(StorageLocationNode::id).toList();
        SiteInventoryQuantityLoader.QuantitySnapshot quantities = quantityLoader.load(knownIds, sku);

        // Build mutable tree keyed by id; nodes whose parent id is absent from the
        // site's set attach to root (defensive against partial topology data).
        Map<UUID, MutableNode> byId = new HashMap<>();
        for (StorageLocationNode node : topology) {
            byId.put(
                    node.id(),
                    new MutableNode(
                            node,
                            RollupQuantities.of(
                                    quantities.onHand().getOrDefault(node.id(), 0L),
                                    quantities.allocated().getOrDefault(node.id(), 0L))));
        }
        List<MutableNode> roots = new ArrayList<>();
        for (MutableNode node : byId.values()) {
            UUID parentId = node.source.parentStorageLocationId();
            MutableNode parent = parentId == null ? null : byId.get(parentId);
            if (parent == null || parent == node) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }

        // PR #661 review finding 8: raw topology rows could theoretically contain
        // a parent cycle (A<->B) leaving nodes unreachable from any root; their
        // quantities would silently vanish from totals. Detect and warn.
        int reachable = countReachable(roots);
        if (reachable < byId.size()) {
            log.warn(
                    "Site {} topology has {} storage locations unreachable from any root (possible parent cycle);"
                            + " their quantities are excluded from the rollup",
                    siteId,
                    byId.size() - reachable);
        }

        roots.forEach(MutableNode::computeRolledUp);

        RollupQuantities totals =
                roots.stream().map(node -> node.rolledUp).reduce(RollupQuantities.ZERO, RollupQuantities::plus);

        int maxDepth = depth == null ? Integer.MAX_VALUE : Math.max(1, depth);
        List<StorageLocationRollupNode> nodes = roots.stream()
                .filter(node -> includeEmpty || !node.rolledUp.isEmpty())
                .sorted(Comparator.comparing(node -> node.source.name(), Comparator.nullsLast(String::compareTo)))
                .map(node -> node.toDto(1, maxDepth, includeEmpty))
                .toList();

        return new SiteInventoryRollupResponse(siteId, totals, nodes);
    }

    private static int countReachable(List<MutableNode> roots) {
        int count = 0;
        java.util.Deque<MutableNode> stack = new java.util.ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            MutableNode node = stack.pop();
            count++;
            stack.addAll(node.children);
        }
        return count;
    }

    private static final class MutableNode {
        private final StorageLocationNode source;
        private final RollupQuantities own;
        private final List<MutableNode> children = new ArrayList<>();
        private RollupQuantities rolledUp;

        private MutableNode(StorageLocationNode source, RollupQuantities own) {
            this.source = source;
            this.own = own;
        }

        private void computeRolledUp() {
            RollupQuantities sum = own;
            for (MutableNode child : children) {
                child.computeRolledUp();
                sum = sum.plus(child.rolledUp);
            }
            rolledUp = sum;
        }

        private StorageLocationRollupNode toDto(int currentDepth, int maxDepth, boolean includeEmpty) {
            List<StorageLocationRollupNode> childDtos = currentDepth >= maxDepth
                    ? List.of()
                    : children.stream()
                            .filter(child -> includeEmpty || !child.rolledUp.isEmpty())
                            .sorted(Comparator.comparing(
                                    child -> child.source.name(), Comparator.nullsLast(String::compareTo)))
                            .map(child -> child.toDto(currentDepth + 1, maxDepth, includeEmpty))
                            .toList();
            return new StorageLocationRollupNode(
                    source.id(), source.name(), source.type(), source.status(), own, rolledUp, childDtos);
        }
    }
}
