package com.positivity.archunit.fixture.accountingwall.bankrec.service;

import com.positivity.archunit.fixture.accountingwall.bankfeed.file.FixtureFileParser;

/**
 * Deliberate violation for DomainWallsTest (#2300): a reconciliation-core service importing a type from
 * the file adapter. Test source only, so no production-class import sees it.
 */
public class FixtureCoreService {

    public int importStatement(String content) {
        return new FixtureFileParser().parse(content);
    }
}
