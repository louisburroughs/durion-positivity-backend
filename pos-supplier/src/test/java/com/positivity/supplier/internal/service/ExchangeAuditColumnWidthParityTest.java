package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins every bounded audit column to the bound the writer actually applies.
 *
 * <h2>Why this test exists rather than a comment</h2>
 *
 * A writer bound has now silently diverged from a DDL width <strong>twice</strong>. The failure mode is
 * unusually bad, because all three of its links are individually reasonable:
 *
 * <ol>
 *   <li>{@code ExchangeAuditObserver} swallows audit write failures on purpose, so a broken audit sink
 *       cannot fail live vendor traffic (ADR-0050 §7).
 *   <li>So an oversized value produces {@code 22001 value too long}, an ERROR log, and a <em>successful</em>
 *       exchange.
 *   <li>And {@code spring.jpa.hibernate.ddl-auto: validate} does not compare column widths, so startup is
 *       clean.
 * </ol>
 *
 * The result is that every exchange for one binding, or one supplier, goes permanently unrecorded while
 * everything reports healthy. No test that writes plausible fixtures will ever catch it — the fixtures are
 * short. Only a comparison of the two declarations can.
 *
 * <p>So the assertion is deliberately structural: parse the {@code truncate(x, N)} calls out of
 * {@link ExchangeAuditWriter} and the column widths out of the migrations, and require them to agree. It
 * fails on a new bounded column with no truncate, on a truncate that outgrows its column, and on a migration
 * that narrows a column without the writer following — the three shapes this has actually taken.
 *
 * <p>The same move as pinning the enums to the {@code CHECK} constraints in
 * {@code SupplierContractKeyParityTest}: where the type system cannot connect two declarations, a test has to.
 */
class ExchangeAuditColumnWidthParityTest {

    private static final Path WRITER =
            Path.of("src/main/java/com/positivity/supplier/internal/service/ExchangeAuditWriter.java");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final String AUDITED_TABLE = "supplier_exchange_audit";

    /**
     * Entity property to column, for the fields the writer truncates. Kept explicit rather than derived from
     * naming, so adding a truncate without deciding which column it defends fails here.
     */
    private static final Map<String, String> TRUNCATED_PROPERTY_TO_COLUMN = Map.of(
            "supplierRef", "supplier_ref",
            "endpointUri", "endpoint_uri",
            "correlationId", "correlation_id",
            "failureDetail", "failure_detail");

    /**
     * Bounded columns the writer deliberately does NOT truncate, with the reason. Anything bounded in the DDL
     * that is neither truncated nor listed here fails the build — which is the point: the list of columns is
     * derived from the schema, so a new one cannot be invisible.
     */
    private static final Map<String, String> UNTRUNCATED_WITH_REASON = Map.of(
            "protocol_version",
                    "selects a codec, so truncation would misreport the norm used; bounded at the API by"
                            + " @Size(max = 64) on EndpointBindingRequest.version and widened to 64 by V5",
            "capability", "enum name; cannot exceed the column without a code change",
            "protocol_family", "enum name; cannot exceed the column without a code change",
            "http_method", "set by this service, not by a caller or vendor",
            "outcome", "ExchangeOutcome name, pinned to the CHECK constraint by SupplierContractKeyParityTest",
            "capture_level",
                    "PayloadCaptureLevel name, pinned to the CHECK constraint by SupplierContractKeyParityTest",
            "created_by", "audit actor from the security context or the system actor constant",
            "unmapped_fields",
                    "reserved by ADR-0051 §4 and written by nothing yet; CAP-318 must truncate it"
                            + " when codecs start populating it");

    /** {@code .property(truncate(..., 123))} — the bound the writer actually applies. */
    private static final Pattern TRUNCATE_CALL =
            Pattern.compile("\\.(\\w+)\\(\\s*truncate\\((?:[^()]|\\([^()]*\\))*,\\s*(\\d+)\\)\\)", Pattern.DOTALL);

    @Test
    @DisplayName("every truncate bound in the writer matches its column width")
    void everyTruncateBoundMatchesItsColumnWidth() throws IOException {
        Map<String, Integer> bounds = truncateBounds();
        Map<String, Integer> widths = declaredWidths();

        assertThat(bounds.keySet())
                .as("a truncate on a property with no mapped column means the map above was not updated;"
                        + " decide which column the bound is defending before adding it")
                .isSubsetOf(TRUNCATED_PROPERTY_TO_COLUMN.keySet());

        bounds.forEach((property, bound) -> {
            String column = TRUNCATED_PROPERTY_TO_COLUMN.get(property);
            assertThat(widths)
                    .as("%s must be a declared column of %s", column, AUDITED_TABLE)
                    .containsKey(column);
            assertThat(bound)
                    .as(
                            "ExchangeAuditWriter truncates %s to %d but %s is varchar(%d). A bound LARGER than"
                                    + " the column silently loses every audit row for the offending binding: the"
                                    + " observer swallows the 22001, the exchange succeeds, and ddl-auto=validate"
                                    + " does not check widths. A bound SMALLER than the column throws away data for"
                                    + " no reason.",
                            property, bound, column, widths.get(column))
                    .isEqualTo(widths.get(column));
        });
    }

    /**
     * The other half, and the half that produced the HIGH finding: every bounded column of the audited table
     * must be either truncated or a <em>recorded</em> exception with its reason.
     *
     * <p>The column list is <strong>derived from the DDL</strong>, not hardcoded. An earlier version listed five
     * names, which meant {@code unmapped_fields varchar(4000)} — already in V3 and reserved for CAP-318 vendor
     * fields — was invisible to it, and any new bounded column would have left both tests green. A parity test
     * whose inputs are a hand-maintained list has the same failure mode as the thing it guards.
     */
    @Test
    @DisplayName("every bounded column is either truncated or a recorded exception")
    void everyBoundedColumnIsEitherTruncatedOrAnExplicitException() throws IOException {
        Map<String, Integer> widths = declaredWidths();
        java.util.Set<String> truncatedColumns = truncateBounds().keySet().stream()
                .map(TRUNCATED_PROPERTY_TO_COLUMN::get)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(widths)
                .as("the DDL parse must find the bounded columns of %s, or this test checks nothing", AUDITED_TABLE)
                .isNotEmpty();

        for (Map.Entry<String, Integer> column : widths.entrySet()) {
            boolean handled =
                    truncatedColumns.contains(column.getKey()) || UNTRUNCATED_WITH_REASON.containsKey(column.getKey());
            assertThat(handled)
                    .as(
                            "%s is varchar(%d) on %s and is neither truncated by ExchangeAuditWriter nor listed in"
                                    + " UNTRUNCATED_WITH_REASON. An unprotected bounded column loses audit rows"
                                    + " SILENTLY -- the observer swallows the 22001, the exchange succeeds, and"
                                    + " ddl-auto=validate does not compare widths. That is how protocol_version was"
                                    + " found. Truncate it, or record why it cannot exceed its column.",
                            column.getKey(), column.getValue(), AUDITED_TABLE)
                    .isTrue();
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────────────

    private static Map<String, Integer> truncateBounds() throws IOException {
        Map<String, Integer> bounds = new LinkedHashMap<>();
        Matcher matcher = TRUNCATE_CALL.matcher(Files.readString(WRITER));
        while (matcher.find()) {
            bounds.put(matcher.group(1), Integer.valueOf(matcher.group(2)));
        }
        assertThat(bounds)
                .as("the truncate-call pattern found nothing in ExchangeAuditWriter -- if the writer was"
                        + " reformatted or the helper renamed, this test silently stops checking anything,"
                        + " which is the failure mode it exists to prevent")
                .isNotEmpty();
        return bounds;
    }

    /**
     * Bounded column widths of {@link #AUDITED_TABLE} as the database will actually have them.
     *
     * <p>Three things this deliberately gets right, each of which an earlier version got wrong:
     *
     * <ul>
     *   <li>Migrations are <strong>globbed and applied in version order</strong>, so a future V6 that narrows a
     *       width is seen. Hardcoding V3 and V5 made any later change invisible.
     *   <li>The {@code CREATE TABLE} parse is <strong>scoped to the audited table</strong>. V3 also creates
     *       {@code supplier_schedule_lease}, which has its own {@code capability varchar(64)} and
     *       {@code last_run_outcome varchar(32)}; a whole-file parse with {@code putIfAbsent} silently mixed the
     *       two tables' columns together.
     *   <li>{@code ALTER COLUMN ... TYPE} is applied over the top, in order, so the last word wins.
     * </ul>
     */
    private static Map<String, Integer> declaredWidths() throws IOException {
        Map<String, Integer> widths = new LinkedHashMap<>();
        Pattern columnDeclaration = Pattern.compile("^\\s+(\\w+) character varying\\((\\d+)\\)", Pattern.MULTILINE);
        Pattern alterColumn = Pattern.compile(
                "ALTER TABLE\\s+(\\w+)\\s+ALTER COLUMN\\s+(\\w+) TYPE character varying\\((\\d+)\\)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        for (Path migration : migrationsInVersionOrder()) {
            String sql = Files.readString(migration);
            String createBody = createTableBody(sql, AUDITED_TABLE);
            if (createBody != null) {
                Matcher declared = columnDeclaration.matcher(createBody);
                while (declared.find()) {
                    widths.put(declared.group(1), Integer.valueOf(declared.group(2)));
                }
            }
            Matcher altered = alterColumn.matcher(sql);
            while (altered.find()) {
                if (AUDITED_TABLE.equalsIgnoreCase(altered.group(1))) {
                    widths.put(altered.group(2), Integer.valueOf(altered.group(3)));
                }
            }
        }

        assertThat(widths.get("protocol_version"))
                .as("protocol_version must resolve to 64 (V3 declares 32, V5 widens it). Anything else means"
                        + " the migration parse is wrong and every comparison here is meaningless")
                .isEqualTo(64);
        return widths;
    }

    /** {@code V<n>__*.sql} in ascending version order, so later migrations override earlier ones. */
    private static java.util.List<Path> migrationsInVersionOrder() throws IOException {
        try (var files = Files.list(MIGRATIONS)) {
            java.util.List<Path> ordered = files.filter(
                            path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(java.util.Comparator.comparingInt(ExchangeAuditColumnWidthParityTest::versionOf))
                    .toList();
            assertThat(ordered)
                    .as("no migrations found under %s -- the glob is wrong and this test checks nothing", MIGRATIONS)
                    .isNotEmpty();
            return ordered;
        }
    }

    private static int versionOf(Path migration) {
        Matcher matcher =
                Pattern.compile("^V(\\d+)__").matcher(migration.getFileName().toString());
        if (!matcher.find()) {
            throw new AssertionError("migration does not follow V<n>__name.sql: " + migration);
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** The body of one {@code CREATE TABLE}, or {@code null} when this file does not create it. */
    @org.jspecify.annotations.Nullable
    private static String createTableBody(String sql, String table) {
        Matcher create = Pattern.compile(
                        "CREATE TABLE\\s+" + Pattern.quote(table) + "\\s*\\(", Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        if (!create.find()) {
            return null;
        }
        int depth = 1;
        int index = create.end();
        while (index < sql.length() && depth > 0) {
            char character = sql.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            }
            index++;
        }
        return sql.substring(create.end(), index);
    }
}
