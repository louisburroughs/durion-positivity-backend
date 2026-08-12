/**
 * Permission-gated admin REST controllers over the vendor profile store (ADR-0050,
 * durion-positivity-backend#1222). Thin by mandate: validate, map, delegate to
 * {@link com.positivity.supplier.service.SupplierProfileAdminService} — the contract records
 * in {@code service.model} ARE the request/response bodies. Error translation lives in
 * {@code internal.exception.SupplierExceptionHandler} (ADR-0017); routes are browser-facing
 * via the gateway {@code /supplier/**} route (ADR-0011/0014).
 */
package com.positivity.supplier.internal.controller;
