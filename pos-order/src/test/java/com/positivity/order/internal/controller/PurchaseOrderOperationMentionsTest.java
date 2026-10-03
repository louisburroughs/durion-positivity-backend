package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every purchase-order operation a {@link PurchaseOrderController} description names is one the
 * controller actually exposes (#2422).
 *
 * <p>The descriptions are tool guidance: "use this, not that" is read by the assistant and by SDK
 * users as a list of real operations. After the CAP-320 split (#1334) {@code approvePurchaseOrder}
 * still pointed at a {@code receivePurchaseOrder} that does not exist ({@link
 * com.positivity.order.internal.service.PurchaseOrderService} says why); receiving is pos-inventory's
 * {@code createGoodsReceipt}.
 */
@DisplayName("PurchaseOrderController descriptions name only real purchase-order operations (#2422)")
class PurchaseOrderOperationMentionsTest {

    private static final Pattern OPERATION_MENTION = Pattern.compile("\\b[a-z]+PurchaseOrders?\\b");

    @Test
    @DisplayName("every *PurchaseOrder(s) operation named in a description is an operationId of this controller")
    void descriptionsNameOnlyExposedOperations() {
        Set<String> operationIds = new TreeSet<>();
        Map<String, String> descriptions = new TreeMap<>();
        for (Method method : PurchaseOrderController.class.getDeclaredMethods()) {
            Operation operation = method.getAnnotation(Operation.class);
            if (operation != null) {
                operationIds.add(operation.operationId());
                descriptions.put(operation.operationId(), operation.description());
            }
        }
        assertThat(operationIds)
                .as("no @Operation found on PurchaseOrderController")
                .isNotEmpty();

        Map<String, Set<String>> unknown = new TreeMap<>();
        descriptions.forEach((operationId, description) -> {
            Matcher matcher = OPERATION_MENTION.matcher(description);
            while (matcher.find()) {
                if (!operationIds.contains(matcher.group())) {
                    unknown.computeIfAbsent(operationId, key -> new TreeSet<>()).add(matcher.group());
                }
            }
        });
        assertThat(unknown)
                .as("descriptions naming a purchase-order operation the controller does not expose")
                .isEmpty();
    }

    @Test
    @DisplayName("approvePurchaseOrder points receiving at pos-inventory's createGoodsReceipt")
    void approvePointsReceivingAtGoodsReceipts() {
        String description = Arrays.stream(PurchaseOrderController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Operation.class))
                .filter(operation -> operation != null && "approvePurchaseOrder".equals(operation.operationId()))
                .findFirst()
                .orElseThrow()
                .description();
        assertThat(description).contains("createGoodsReceipt").doesNotContain("receivePurchaseOrder");
    }
}
