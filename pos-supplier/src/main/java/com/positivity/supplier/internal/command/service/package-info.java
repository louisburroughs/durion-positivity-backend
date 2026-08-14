/**
 * Inbound commands addressed to pos-supplier on {@code supplier.commands.v1} (ADR-0049 §3).
 *
 * <h2>Why this is its own slice</h2>
 *
 * The commands here are addressed to different parts of this module — a purchase-order request
 * belongs to {@code internal.order}, a PRICAT re-publication request to
 * {@code internal.pricecatalog} — but they arrive on one topic, and one topic can only safely have
 * one consumer group in this module: the {@code processed_events} idempotency log is keyed by event
 * id alone, so a second group would record ids the first group still needed to act on.
 *
 * <p>Putting the consumer in either domain slice would make that slice depend on the other. Putting
 * it in {@code internal.service} would point a shared package at both domains while both already
 * depend on it. So the topic gets a slice of its own that depends on the domains and is depended on
 * by nothing.
 *
 * <h2>What belongs here</h2>
 *
 * Envelope handling only: parse, deduplicate, dispatch, record. Every decision about what a command
 * means belongs to the domain slice that owns the fact — this package should stay thin enough that
 * a new command type is a branch and a delegate call.
 */
package com.positivity.supplier.internal.command.service;
