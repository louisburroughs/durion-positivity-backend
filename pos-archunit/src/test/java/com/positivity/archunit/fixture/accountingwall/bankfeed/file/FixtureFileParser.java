package com.positivity.archunit.fixture.accountingwall.bankfeed.file;

/** Stand-in for pos-accounting's file-adapter parser (fixture for DomainWallsTest, #2300). */
public class FixtureFileParser {

    public int parse(String content) {
        return content.length();
    }
}
