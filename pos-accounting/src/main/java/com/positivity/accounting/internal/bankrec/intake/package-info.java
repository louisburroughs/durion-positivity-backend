/**
 * The reconciliation core's intake port (SPEC-manual-bank-reconciliation §2.1, §2.2; decision D1;
 * story S2, #2301): {@link com.positivity.accounting.internal.bankrec.intake.BankTransactionIntake}
 * with its context, result, refusal codes, normalization and fingerprinting.
 *
 * <p>Together with {@code ..bankrec.dto..} this package is the only part of the core a {@code
 * ..bankfeed..} adapter may reach, and it depends only on the rest of {@code ..bankrec..}, the
 * {@code pos-domain-events} contract and the platform libraries (module {@code ArchitectureTest}).
 */
package com.positivity.accounting.internal.bankrec.intake;
