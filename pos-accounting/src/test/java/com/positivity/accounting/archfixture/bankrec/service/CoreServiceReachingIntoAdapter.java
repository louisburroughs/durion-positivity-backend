package com.positivity.accounting.archfixture.bankrec.service;

import com.positivity.accounting.archfixture.bankfeed.file.FixtureStatementParser;

/** Violation: a core service importing a type from the file adapter (fixture, #2300). */
public class CoreServiceReachingIntoAdapter {

    public int importBytes(String content) {
        return new FixtureStatementParser().parse(content);
    }
}
