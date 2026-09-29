/**
 * The provider-neutral bank-feed contract (SPEC-manual-bank-reconciliation §2.2, §6.5; story S2,
 * #2301): what every bank-data source — the phase-1 file adapter and manual entry, a phase-2
 * connector — says to pos-accounting, and the two commands accounting sends back. Facts travel on
 * {@code bankfeed.events.v1}, commands on {@code bankfeed.commands.v1} (phase 2); in phase 1 the
 * transactions batch is handed to accounting's intake port in-process.
 *
 * <p>No type in this package names or depends on a provider, a provider SDK or a file format.
 */
package com.positivity.domainevents.bankfeed;
