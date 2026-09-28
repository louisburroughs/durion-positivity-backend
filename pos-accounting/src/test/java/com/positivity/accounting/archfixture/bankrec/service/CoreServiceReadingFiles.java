package com.positivity.accounting.archfixture.bankrec.service;

import java.nio.file.Path;

/** Violation: a core service reading statement bytes from a file (fixture, #2300). */
public class CoreServiceReadingFiles {

    public String fileName(Path path) {
        return String.valueOf(path.getFileName());
    }
}
