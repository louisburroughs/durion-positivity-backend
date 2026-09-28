/**
 * Deliberate architecture violations for {@code BankrecWallsFixtureTest} (#2300). These are test
 * sources, so the module {@code ArchitectureTest} (which imports main classes only) never sees them;
 * the fixture test imports them on its own and asserts each bank reconciliation wall rejects them.
 * Nothing here is a Spring bean.
 */
package com.positivity.accounting.archfixture;
