/**
 * The reconciliation core's intake port (SPEC-manual-bank-reconciliation §2.1, §2.2; decision D1).
 *
 * <p>Placeholder created by story S1 (#2300) so the core/adapter ArchUnit walls bind from the start:
 * {@code BankTransactionIntake.accept(BankTransactionsObservedV1, IntakeContext)} arrives with story S2,
 * and together with {@code ..bankrec.dto..} it is the only part of the core a {@code ..bankfeed..}
 * adapter may reach.
 */
package com.positivity.accounting.internal.bankrec.intake;
