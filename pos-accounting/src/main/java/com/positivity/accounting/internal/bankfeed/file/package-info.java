/**
 * The phase-1 statement-file adapter (SPEC-manual-bank-reconciliation §2.1; decision D1).
 *
 * <p>It owns {@code bank_import} staging — upload, parse, column mapping, preview, row correction,
 * commit — and the statement-format parsers. Its commit builds a {@code BankTransactionsObservedV1} and
 * calls the core's intake port in-process. It may reach the core only through {@code ..bankrec.intake..}
 * and {@code ..bankrec.dto..}, and it is the only place in the module where a statement-format library
 * may be imported (module {@code ArchitectureTest}, {@code pos-archunit} {@code DomainWallsTest}).
 *
 * <p>Story S1 (#2300) creates the package with the import entities and repositories as shells; the
 * behaviour arrives with story S3.
 */
package com.positivity.accounting.internal.bankfeed.file;
