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
    private static final Path V3 = Path.of("src/main/resources/db/migration/V3__supplier_exchange_audit.sql");
    private static final Path V5 = Path.of("src/main/resources/db/migration/V5__widen_audit_protocol_version.sql");

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
     * {@code .property(truncate(<anything>, 123))} — the bound the writer actually applies.
     *
     * <p>The first argument is matched allowing one level of nested parentheses, because it is not always a
     * bare accessor: {@code endpointUri} truncates the result of a redaction call. The earlier pattern stopped
     * at the first comma and silently lost that bound the moment redaction was added — which this test then
     * reported as an unprotected column, correctly, rather than passing quietly. That is the behaviour wanted,
     * but the pattern should track the code it parses.
     */
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
                    .as("%s must be a declared column in the migrations", column)
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
     * The other half, and the half that produced the HIGH finding: a bounded column the writer does
     * <em>not</em> truncate must be a deliberate decision, not an omission.
     *
     * <p>{@code protocol_version} is the standing exception and is listed by name with its reason — it selects
     * a codec, so a truncated value would make the row misreport which vendor norm built the document, and
     * present-and-wrong is worse than absent-and-logged. It is instead bounded at the API by
     * {@code @Size(max = 64)} on {@code EndpointBindingRequest.version} and widened to match its source column
     * by V5. Any OTHER unprotected bounded column fails here.
     */
    @Test
    @DisplayName("no bounded column is written unprotected without a recorded exception")
    void everyBoundedColumnIsEitherTruncatedOrAnExplicitException() throws IOException {
        // Bounded columns written from caller- or operator-influenced values. capability/protocol_family are
        // enum names, http_method and outcome are ours, created_by is the system actor -- none can be
        // oversized without a code change, which a compiler or a CHECK constraint catches.
        Map<String, String> exceptionsWithReasons = Map.of(
                "protocol_version",
                "selects a codec, so truncation would misreport the norm used; bounded by @Size(max = 64) on"
                        + " EndpointBindingRequest.version and widened to 64 by V5 to match its source column");

        Map<String, Integer> widths = declaredWidths();
        Map<String, Integer> bounds = truncateBounds();
        java.util.Set<String> truncatedColumns = bounds.keySet().stream()
                .map(TRUNCATED_PROPERTY_TO_COLUMN::get)
                .collect(java.util.stream.Collectors.toSet());

        for (String column :
                new String[] {"supplier_ref", "endpoint_uri", "correlation_id", "failure_detail", "protocol_version"}) {
            assertThat(widths).containsKey(column);
            boolean protectedByTruncation = truncatedColumns.contains(column);
            boolean recordedException = exceptionsWithReasons.containsKey(column);
            assertThat(protectedByTruncation || recordedException)
                    .as(
                            "%s is varchar(%d) and is written from a value this service does not control the"
                                    + " length of. Either truncate it in ExchangeAuditWriter or record here why not"
                                    + " -- an unprotected bounded column loses audit rows silently, which is how"
                                    + " protocol_version was found",
                            column, widths.get(column))
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
     * Column widths as the database will actually have them: V3's {@code CREATE TABLE} declarations, with
     * V5's {@code ALTER COLUMN ... TYPE} applied over the top. Later migrations must be folded in the same
     * way, or this test would compare against a width that no longer exists.
     */
    private static Map<String, Integer> declaredWidths() throws IOException {
        Map<String, Integer> widths = new LinkedHashMap<>();
        Matcher declared = Pattern.compile("^\\s{4}(\\w+) character varying\\((\\d+)\\)", Pattern.MULTILINE)
                .matcher(Files.readString(V3));
        while (declared.find()) {
            widths.putIfAbsent(declared.group(1), Integer.valueOf(declared.group(2)));
        }
        Matcher altered = Pattern.compile(
                        "ALTER COLUMN (\\w+) TYPE character varying\\((\\d+)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(Files.readString(V5));
        while (altered.find()) {
            widths.put(altered.group(1), Integer.valueOf(altered.group(2)));
        }
        assertThat(widths.get("protocol_version"))
                .as("V5 must widen protocol_version to 64; if that ALTER is gone the parse is wrong and the"
                        + " comparison below would be meaningless")
                .isEqualTo(64);
        return widths;
    }
}
