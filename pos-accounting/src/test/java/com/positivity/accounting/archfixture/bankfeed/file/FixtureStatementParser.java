package com.positivity.accounting.archfixture.bankfeed.file;

/** Stand-in for an adapter-side statement parser (fixture for BankrecWallsFixtureTest, #2300). */
public class FixtureStatementParser {

    public int parse(String content) {
        return content.length();
    }
}
