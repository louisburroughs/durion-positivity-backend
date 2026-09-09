package com.positivity.securityservice.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the role rows the Flyway seed scripts create, so the guards in this package can reason
 * about columns rather than about quoted tokens.
 *
 * <p>Since the migration history was flattened (2026-09-09) every role seed is a plain
 * {@code INSERT INTO roles (columns) VALUES (...)}: the versioned seed writes one row per statement
 * with the full column list, the repeatable seed writes one row per statement with a shorter list.
 * A token-level regex over those statements would harvest enum literals ({@code 'ALL'},
 * {@code 'OTHER'}) as role names, so this parses the column list and the tuple instead.
 */
final class RoleSeedSql {

    private static final Pattern INSERT =
            Pattern.compile("INSERT\\s+INTO\\s+roles\\s*\\(([^)]*)\\)\\s*VALUES", Pattern.CASE_INSENSITIVE);

    private RoleSeedSql() {}

    /** Every role row inserted by any {@code .sql} file in the directory, keyed by column name. */
    static List<Map<String, String>> rows(Path migrations) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (var files = Files.list(migrations)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                rows.addAll(parse(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return rows;
    }

    static List<Map<String, String>> parse(String sql) {
        List<Map<String, String>> rows = new ArrayList<>();
        Matcher insert = INSERT.matcher(sql);
        while (insert.find()) {
            List<String> columns = new ArrayList<>();
            for (String column : insert.group(1).split(",")) {
                columns.add(column.trim().toLowerCase(Locale.ROOT));
            }
            int pos = insert.end();
            while (true) {
                pos = skipWhitespace(sql, pos);
                if (pos >= sql.length() || sql.charAt(pos) != '(') {
                    break;
                }
                List<String> values = new ArrayList<>();
                pos = readTuple(sql, pos + 1, values);
                if (values.size() != columns.size()) {
                    throw new IllegalStateException("role seed row has " + values.size() + " values for "
                            + columns.size() + " columns: " + columns);
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    row.put(columns.get(i), values.get(i));
                }
                rows.add(row);
                pos = skipWhitespace(sql, pos);
                if (pos < sql.length() && sql.charAt(pos) == ',') {
                    pos++;
                } else {
                    break;
                }
            }
        }
        return rows;
    }

    /** Reads one {@code (...)} tuple starting after its opening parenthesis; returns the index after it. */
    private static int readTuple(String sql, int start, List<String> values) {
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        int i = start;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (quoted) {
                if (c == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append('\'');
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '\'') {
                quoted = true;
                current.append('\'');
            } else if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                if (depth == 0) {
                    values.add(normalise(current.toString()));
                    return i + 1;
                }
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                values.add(normalise(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
            i++;
        }
        throw new IllegalStateException("unterminated VALUES tuple in role seed");
    }

    /** A quoted literal loses its quotes and any trailing cast; {@code NULL} becomes null. */
    private static String normalise(String raw) {
        String value = raw.strip();
        if (value.startsWith("'")) {
            return value.substring(1);
        }
        return value.equalsIgnoreCase("NULL") ? null : value;
    }

    private static int skipWhitespace(String sql, int pos) {
        while (pos < sql.length() && Character.isWhitespace(sql.charAt(pos))) {
            pos++;
        }
        return pos;
    }
}
