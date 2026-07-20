package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.AddItemRequest;
import com.positivity.order.internal.dto.CreateCartRequest;
import com.positivity.order.internal.dto.LinkSourceRequest;
import com.positivity.order.internal.dto.SalesOrderLineResponse;
import com.positivity.order.internal.dto.SalesOrderResponse;
import com.positivity.order.internal.dto.UpdateItemRequest;
import com.positivity.order.internal.observability.BusinessSpanSupport;
import com.positivity.order.service.SalesOrderService;
import com.positivity.order.service.model.SalesOrderLineSummary;
import com.positivity.order.service.model.SalesOrderSummary;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
@Tag(name = "Sales Orders", description = "Sales order cart management")
public class SalesOrderController {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-order");
    private static final String DOMAIN = "order-management";
    private static final String TEAM = "order-eng";

    private final SalesOrderService salesOrderService;

    @Operation(
            summary = "Create a sales order cart",
            description = "Create a new sales order cart for a customer, terminal, and optional vehicle context.",
            tags = {"Sales Orders"})
    @PostMapping("/carts")
    @EmitEvent(id = "ORDER_CART_CREATE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> createCart(@Valid @RequestBody CreateCartRequest request) {
        Span span = TRACER.spanBuilder("Create Sales Order").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Create Sales Order");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            SalesOrderSummary created = salesOrderService.createCart(
                    request.getClerkId(), request.getTerminalId(), request.getCustomerId(), request.getVehicleId());
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Created sales order cart {}", created.orderId());
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Operation(
            summary = "Add an item to a sales order cart",
            description =
                    "Add a line item to an existing sales order cart using SKU, quantity, and optional pricing context.",
            tags = {"Sales Orders"})
    @PostMapping("/carts/{orderId}/items")
    @EmitEvent(id = "ORDER_CART_ITEM_ADD", apiVersion = "1")
    public ResponseEntity<SalesOrderLineResponse> addItem(
            @PathVariable UUID orderId, @Valid @RequestBody AddItemRequest request) {
        Span span = TRACER.spanBuilder("Add Cart Item").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Add Cart Item");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            SalesOrderLineSummary line = salesOrderService.addItem(
                    orderId,
                    request.getItemSku(),
                    request.getQuantity(),
                    request.getReasonCode(),
                    request.getManualPrice());
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Added cart item to order {}", orderId);
            return ResponseEntity.status(HttpStatus.CREATED).body(toLineResponse(line));
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Operation(
            summary = "Update a sales order cart item quantity",
            description = "Update the quantity for an existing sales order line item in a cart.",
            tags = {"Sales Orders"})
    @PutMapping("/carts/{orderId}/items/{lineId}")
    @EmitEvent(id = "ORDER_CART_ITEM_UPDATE", apiVersion = "1")
    public ResponseEntity<SalesOrderLineResponse> updateItemQuantity(
            @PathVariable UUID orderId, @PathVariable UUID lineId, @Valid @RequestBody UpdateItemRequest request) {
        Span span = TRACER.spanBuilder("Update Cart Item").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Update Cart Item");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            SalesOrderLineSummary line = salesOrderService.updateItemQuantity(orderId, lineId, request.getQuantity());
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Updated cart item {} on order {}", lineId, orderId);
            return ResponseEntity.ok(toLineResponse(line));
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Operation(
            summary = "Remove an item from a sales order cart",
            description = "Remove a line item from an existing sales order cart.",
            tags = {"Sales Orders"})
    @DeleteMapping("/carts/{orderId}/items/{lineId}")
    @EmitEvent(id = "ORDER_CART_ITEM_REMOVE", apiVersion = "1")
    public ResponseEntity<Void> removeItem(@PathVariable UUID orderId, @PathVariable UUID lineId) {
        Span span = TRACER.spanBuilder("Remove Cart Item").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Remove Cart Item");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            salesOrderService.removeItem(orderId, lineId);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Removed cart item {} from order {}", lineId, orderId);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Operation(
            summary = "Get a sales order by ID",
            description = "Retrieve a sales order cart and its current line items by identifier.",
            tags = {"Sales Orders"})
    @GetMapping("/carts/{orderId}")
    public ResponseEntity<SalesOrderResponse> getOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(toResponse(salesOrderService.getOrder(orderId)));
    }

    @Operation(
            summary = "Link a source to a sales order",
            description = "Associate an external source reference with an existing sales order cart.",
            tags = {"Sales Orders"})
    @PatchMapping("/carts/{orderId}/source")
    @EmitEvent(id = "ORDER_LINK_SOURCE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> linkSource(
            @PathVariable UUID orderId, @Valid @RequestBody LinkSourceRequest request) {
        SalesOrderSummary updated =
                salesOrderService.linkSource(orderId, request.getSourceType(), request.getSourceId());
        return ResponseEntity.ok(toResponse(updated));
    }

    private SalesOrderResponse toResponse(SalesOrderSummary summary) {
        List<SalesOrderLineResponse> lines =
                summary.lines().stream().map(this::toLineResponse).toList();
        return SalesOrderResponse.builder()
                .orderId(summary.orderId())
                .customerId(summary.customerId())
                .vehicleId(summary.vehicleId())
                .clerkId(summary.clerkId())
                .terminalId(summary.terminalId())
                .status(summary.status())
                .subtotal(summary.subtotal())
                .createdAt(summary.createdAt())
                .updatedAt(summary.updatedAt())
                .createdBy(summary.createdBy())
                .updatedBy(summary.updatedBy())
                .lines(lines)
                .build();
    }

    private SalesOrderLineResponse toLineResponse(SalesOrderLineSummary summary) {
        return SalesOrderLineResponse.builder()
                .orderLineId(summary.orderLineId())
                .itemSku(summary.itemSku())
                .itemDescription(summary.itemDescription())
                .quantity(summary.quantity())
                .unitPrice(summary.unitPrice())
                .fulfillmentStatus(summary.fulfillmentStatus())
                .priceSource(summary.priceSource())
                .reasonCode(summary.reasonCode())
                .sourceType(summary.sourceType())
                .sourceId(summary.sourceId())
                .sourceLineId(summary.sourceLineId())
                .build();
    }
}
