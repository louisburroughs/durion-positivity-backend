package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Removes credential material from an exchange payload before it is persisted (ADR-0050 §7).
 *
 * <p>The base client hands observers <strong>raw wire documents</strong> — it is format-agnostic and
 * cannot know which element of an EDIWheel document is sensitive — so redaction is the observer's
 * obligation, and this is where it happens.
 *
 * <p><strong>Why bodies need redacting at all, when credentials travel in headers.</strong> They
 * mostly do, and headers never reach {@code ExchangeContext}. But EDIFACT-derived and SOAP-style
 * vendor formats routinely repeat credentials <em>inside</em> the document: EDIWheel A2.5 carries
 * {@code <Password>} and {@code <UserID>} in the message header element, and OAuth token exchanges put
 * {@code client_secret} in a form body. A payload store that captured those verbatim would be a
 * credential database with a 400-day retention window.
 *
 * <p>Patterns are deliberately <em>value-preserving in shape</em>: the element or field is kept and
 * only its value replaced, so a redacted document still shows an operator that the field was present —
 * which is often the diagnostic they need — without disclosing it.
 *
 * <h2>Two limitations, both real and both deliberately not papered over</h2>
 *
 * <ol>
 *   <li><strong>The sensitive-name set is fixed, not per-binding.</strong> ADR-0050 §7 describes configurable
 *       per-binding field redaction; this is a compiled-in list applied identically everywhere. Nothing varies
 *       it — no property, no column, no request field. Deferred to CAP-318.
 *   <li><strong>Only NAMED fields can be found.</strong> The four pattern families match XML elements, XML
 *       attributes, JSON fields and form parameters, all of which pair a name with a value. A positional or
 *       fixed-width vendor format — an EDIFACT segment, a delimited flat file — carries values with no names
 *       at all, so nothing matches and a REDACTED capture of such a document is stored substantially intact.
 *       This is not a bug in the patterns; it is a limit of name-based redaction, and closing it needs a
 *       per-format field map, which is codec knowledge and therefore CAP-318's.
 * </ol>
 *
 * <p>Until then, the honest operational advice is that {@code METADATA_ONLY} is the only level that
 * <em>guarantees</em> no commercial content is retained for a positional-format binding.
 */
public final class PayloadRedactor {

    /** What replaces a redacted value. Distinctive so it is obvious in a stored payload. */
    public static final String REDACTED = "***REDACTED***";

    /**
     * Field and element names whose values are always removed, matched case-insensitively.
     *
     * <p>Chosen from the formats CAP-317 will actually carry: EDIWheel/Michelin XML
     * ({@code Password}, {@code UserID}, {@code ApiKey}), OAuth2 form and JSON token exchanges
     * ({@code client_secret}, {@code access_token}, {@code refresh_token}, {@code assertion}), and the
     * generic header-ish names vendors sometimes echo into bodies ({@code Authorization}).
     */
    private static final List<String> SENSITIVE_NAMES = List.of(
            "password",
            "passwd",
            "pwd",
            "secret",
            "client_secret",
            "clientsecret",
            "apikey",
            "api_key",
            "access_token",
            "accesstoken",
            "refresh_token",
            "refreshtoken",
            "id_token",
            "authorization",
            "auth_token",
            "authtoken",
            "bearer",
            "assertion",
            "credential",
            "credentials",
            "userid",
            "user_id",
            "username",
            "user_name");

    /** {@code <Password>value</Password>} and {@code <ns:Password ...>value</ns:Password>}. */
    private static final List<Pattern> XML_ELEMENT_PATTERNS = SENSITIVE_NAMES.stream()
            .map(name -> Pattern.compile(
                    "(<(?:[\\w.-]+:)?" + Pattern.quote(name) + "\\b[^>]*>)(.*?)(</(?:[\\w.-]+:)?" + Pattern.quote(name)
                            + "\\s*>)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
            .toList();

    /** {@code Password="value"} and {@code password='value'} attributes. */
    private static final List<Pattern> XML_ATTRIBUTE_PATTERNS = SENSITIVE_NAMES.stream()
            .map(name -> Pattern.compile(
                    "(\\b" + Pattern.quote(name) + "\\s*=\\s*)([\"'])(.*?)(\\2)", Pattern.CASE_INSENSITIVE))
            .toList();

    /** {@code "password": "value"} and {@code "password":123} in JSON. */
    private static final List<Pattern> JSON_FIELD_PATTERNS = SENSITIVE_NAMES.stream()
            .map(name -> Pattern.compile(
                    "(\"" + Pattern.quote(name) + "\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\s]+)",
                    Pattern.CASE_INSENSITIVE))
            .toList();

    /** {@code client_secret=value&...} in form-encoded bodies. */
    private static final List<Pattern> FORM_FIELD_PATTERNS = SENSITIVE_NAMES.stream()
            .map(name -> Pattern.compile("(\\b" + Pattern.quote(name) + "=)([^&\\s]*)", Pattern.CASE_INSENSITIVE))
            .toList();

    private PayloadRedactor() {
        // static rules
    }

    /**
     * Redacts credential values out of a request URI before it is stored (ADR-0050 §4/§7).
     *
     * <h2>Why the URI needs this at all</h2>
     *
     * {@code endpoint_uri} is the one audit column stored in <strong>plaintext at every capture level,
     * including {@code METADATA_ONLY}, and exempt from the 400-day payload purge</strong> — so it is retained
     * forever, and the metadata listing returns it to anyone holding {@code supplier:audit:read}. A binding
     * configured to retain nothing would still retain the full URI permanently.
     *
     * <p>{@code ExchangeAuditEntity} used to assert that credentials never travel in the URI. That is true of
     * the three shipped auth strategies and <em>enforced by nothing</em>: vendors legitimately take query
     * parameters, a fourth strategy or a binding path carrying {@code ?apikey=...} would put a live credential
     * into permanent unpurged storage, and the reason it has not happened is convention. This function is the
     * control that makes the statement true rather than hopeful.
     *
     * <p>The form-field patterns are exactly right for a query string — {@code name=value} pairs separated by
     * {@code &} — so this reuses them rather than inventing a URI-specific rule set.
     *
     * <h2>METADATA_ONLY drops the query string entirely</h2>
     *
     * Not just its sensitive parameters. That level's whole meaning is that no content is retained, and query
     * parameters carry content: order numbers, part numbers, account references, date ranges. Redacting only
     * the names on the sensitive list would leave commercial data behind at the one level that promises to
     * keep none. The path is kept, because knowing WHICH endpoint was called is metadata and is the point of
     * the trail.
     *
     * @param uri the absolute request URI, or {@code null}
     * @param captureLevel the level governing this row
     * @return the URI safe to persist; {@code null} in, {@code null} out
     */
    @Nullable
    public static String redactUri(@Nullable String uri, @NonNull PayloadCaptureLevel captureLevel) {
        Objects.requireNonNull(captureLevel, "captureLevel must not be null");
        if (uri == null) {
            return null;
        }
        int queryStart = uri.indexOf('?');
        if (captureLevel == PayloadCaptureLevel.METADATA_ONLY) {
            return queryStart < 0 ? uri : uri.substring(0, queryStart);
        }
        if (queryStart < 0) {
            // No query string: nothing a name=value pattern could match, and the path itself is metadata.
            return uri;
        }
        String query = uri.substring(queryStart + 1);
        for (Pattern pattern : FORM_FIELD_PATTERNS) {
            query = pattern.matcher(query)
                    .replaceAll(matchResult -> Matcher.quoteReplacement(matchResult.group(1) + REDACTED));
        }
        return uri.substring(0, queryStart + 1) + query;
    }

    /**
     * Redacts credential values in a payload, whatever its format.
     *
     * <p>All four pattern families are applied unconditionally rather than sniffing the format first:
     * content types from vendors are unreliable (that is why the transport reads bytes), a multipart
     * body can contain more than one format, and a pattern that finds nothing costs nothing. Failing
     * to redact is unrecoverable; over-applying is not.
     *
     * @param payload the raw wire document, or {@code null}
     * @return the payload with credential values replaced, or {@code null} when given {@code null}
     */
    @Nullable
    public static String redact(@Nullable String payload) {
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        String result = payload;
        for (Pattern pattern : XML_ELEMENT_PATTERNS) {
            result = pattern.matcher(result)
                    .replaceAll(matchResult ->
                            Matcher.quoteReplacement(matchResult.group(1) + REDACTED + matchResult.group(3)));
        }
        for (Pattern pattern : XML_ATTRIBUTE_PATTERNS) {
            result = pattern.matcher(result)
                    .replaceAll(matchResult -> Matcher.quoteReplacement(
                            matchResult.group(1) + matchResult.group(2) + REDACTED + matchResult.group(4)));
        }
        for (Pattern pattern : JSON_FIELD_PATTERNS) {
            result = pattern.matcher(result)
                    .replaceAll(matchResult -> Matcher.quoteReplacement(matchResult.group(1) + "\"" + REDACTED + "\""));
        }
        for (Pattern pattern : FORM_FIELD_PATTERNS) {
            result = pattern.matcher(result)
                    .replaceAll(matchResult -> Matcher.quoteReplacement(matchResult.group(1) + REDACTED));
        }
        return result;
    }

    /**
     * Applies a binding's capture level to a payload.
     *
     * @param payload the raw wire document, or {@code null}
     * @param captureLevel the level governing this exchange
     * @return {@code null} for {@code METADATA_ONLY}; the payload unchanged for {@code FULL}; the
     *     redacted payload for {@code REDACTED}
     */
    @Nullable
    public static String applyCaptureLevel(@Nullable String payload, @NonNull PayloadCaptureLevel captureLevel) {
        return switch (captureLevel) {
            case METADATA_ONLY -> null;
            // FULL means "keep the document as sent" -- the operator explicitly accepted that for
            // this binding. Credential-header redaction is not at stake here because headers never
            // reach a payload; what FULL preserves is body content.
            case FULL -> payload;
            case REDACTED -> redact(payload);
        };
    }
}
