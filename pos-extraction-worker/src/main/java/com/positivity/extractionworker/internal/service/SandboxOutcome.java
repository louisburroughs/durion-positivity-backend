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

    /**
     * Infrastructure failed, not the file: the child could not be started, its request could not be written,
     * its classpath is broken, or the HEIC decoder is missing. The caller is told to retry (503), and no
     * file is ever rejected for it (S26 ruling 2).
     */
    record Unavailable() implements SandboxOutcome {}

    /** Any other failure: another exit code, no result, an oversized or unparseable result. */
    record Failed() implements SandboxOutcome {}
}
