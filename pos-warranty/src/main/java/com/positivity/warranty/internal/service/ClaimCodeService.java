package com.positivity.warranty.internal.service;

import org.jspecify.annotations.NonNull;

/**
 * Allocates human-readable claim codes {@code WC-{yyyy}-{seq}} (PRD §4) — e.g.
 * {@code WC-2026-000123}. The 6-digit zero-padded sequence resets each calendar year;
 * UUIDv7 stays the primary key, the claim code is the unique business identifier that
 * customers write down and that appears on vendor paperwork.
 */
public interface ClaimCodeService {

    /**
     * Atomically allocate the next claim code for the current year (UTC clock). The per-year
     * counter row is locked pessimistically, so concurrent intakes serialize and codes are
     * unique. Allocation joins the caller's transaction (or opens its own): if the surrounding
     * claim-creation transaction rolls back, the increment rolls back with it and the number is
     * released.
     */
    @NonNull
    String nextClaimCode();
}
