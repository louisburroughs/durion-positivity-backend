/**
 * Purchase-order transmission: intent minting, dispatch, ambiguity reconciliation, manual
 * resolution, and fulfilment status polling (CAP-320 #1226, #1318; ADR-0052).
 *
 * <h2>The one rule everything here serves</h2>
 *
 * A purchase order must never be transmitted twice. Duplicate physical orders are the top
 * operational risk of supplier EDI — trucks deliver tyres twice and someone has to pay for them —
 * so every design choice in this package is downstream of preventing that:
 *
 * <ul>
 *   <li>The document id is <em>derived</em> from an immutable intent id, so a retry cannot invent
 *       a new one and a new intent cannot reuse an old one.
 *   <li>{@code DISPATCHING} is committed before the send, so a crash mid-flight is distinguishable
 *       from a send that never happened.
 *   <li>Nothing automatically re-sends after a body may have been transmitted. The only moves are
 *       asking the vendor (order status) and asking a human.
 *   <li>There is no re-send operation in the service contract or the controller, so the option is
 *       not merely discouraged — it does not exist.
 * </ul>
 *
 * <h2>And what it is all for</h2>
 *
 * Once an order is safely placed, the same status feed that reconciles ambiguity carries the
 * delivery schedules and despatches receiving needs (#1318). That is why polling continues long
 * past confirmation: confirmation is the beginning of the delivery story, not the end of it.
 */
package com.positivity.supplier.internal.order.service;
