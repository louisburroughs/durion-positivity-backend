package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.sandbox.SandboxResult;

/** How a child JVM run ended, as the parent sees it. */
sealed interface SandboxOutcome {

    /** The child exited 0 and wrote a result the parent could read. */
    record Completed(SandboxResult result) implements SandboxOutcome {}

    /** The deadline passed and the child (with its descendants) was killed. */
    record TimedOut() implements SandboxOutcome {}

    /** The child ran out of heap and exited with code 3. */
    record OutOfMemory() implements SandboxOutcome {}

    /** Any other failure: another exit code, no result, an oversized or unparseable result. */
    record Failed() implements SandboxOutcome {}
}
