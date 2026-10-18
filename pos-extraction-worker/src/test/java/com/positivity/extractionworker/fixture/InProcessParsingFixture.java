package com.positivity.extractionworker.fixture;

import com.positivity.extractionworker.internal.sandbox.ParseMain;

/** A deliberate violation: web-side code calling the child's entry point in process. Never packaged. */
public final class InProcessParsingFixture {

    private InProcessParsingFixture() {}

    public static void parseHere(String callDir) {
        ParseMain.main(new String[] {"inspect", callDir});
    }
}
