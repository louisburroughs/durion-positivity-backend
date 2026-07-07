package com.positivity.bulkloader.internal.parser;

import java.util.Locale;
import org.jspecify.annotations.NonNull;

final class FileNames {

    private FileNames() {}

    static String extension(@NonNull String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
