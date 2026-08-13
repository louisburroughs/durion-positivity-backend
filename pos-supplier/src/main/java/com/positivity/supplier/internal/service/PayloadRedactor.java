package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.enums.RedactionClassification;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <h2>Two levels of redaction: credentials always, classifications per binding</h2>
 *
 * The credential vocabulary ({@link #SENSITIVE_NAMES}) applies identically to every binding — no
 * configuration can narrow it, because failing to redact a credential is unrecoverable. On top of it, a
 * binding may declare {@link RedactionClassification}s (issue #1259, ADR-0050 §7 minimization:
 * "redaction configuration lives with the binding"), each of which contributes a compiled-in field-name
 * vocabulary of its own — customer identifiers for fleet workorder authorization payloads, pricing for
 * PRICAT-class documents. Classifications rather than free-text field lists, so the same class of data
 * is treated consistently across vendors: widening a class here widens it for every binding declaring it.
 *
 * <h2>One limitation, real and deliberately not papered over</h2>
 *
 * <strong>Only NAMED fields can be found.</strong> The four pattern families match XML elements, XML
 * attributes, JSON fields and form parameters, all of which pair a name with a value — and that holds
 * for classification vocabularies exactly as for credentials. A positional or fixed-width vendor
 * format — an EDIFACT segment, a delimited flat file — carries values with no names at all, so nothing
 * matches and a REDACTED capture of such a document is stored substantially intact whatever
 * classifications the binding declares. This is not a bug in the patterns; it is a limit of name-based
 * redaction, and closing it needs a per-format field map, which is codec knowledge and therefore
 * CAP-318's.
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

    /** The credential patterns, applied to every payload regardless of binding configuration. */
    private static final NamePatterns CREDENTIAL_PATTERNS = NamePatterns.compile(SENSITIVE_NAMES);

    /**
     * The field-name vocabulary of each {@link RedactionClassification} — the per-binding half of
     * ADR-0050 §7 minimization. Compiled in, like {@link #SENSITIVE_NAMES}, so a class means the same
     * thing on every binding that declares it; what varies per binding is only WHICH classes apply.
     *
     * <p>Underscore variants are listed alongside the joined form for the same reason the credential set
     * lists {@code api_key} next to {@code apikey}: matching is case-insensitive but literal, so
     * {@code CustomerId} and {@code CUSTOMER_ID} need separate entries to both be found.
     */
    private static final Map<RedactionClassification, List<String>> CLASSIFICATION_NAMES = Map.of(
            RedactionClassification.CUSTOMER_IDENTIFIER,
                    List.of(
                            "customerid",
                            "customer_id",
                            "customernumber",
                            "customer_number",
                            "customerno",
                            "customer_no",
                            "customerreference",
                            "customer_reference",
                            "customercode",
                            "customer_code",
                            "accountid",
                            "account_id",
                            "accountnumber",
                            "account_number",
                            "accountno",
                            "account_no",
                            "fleetid",
                            "fleet_id",
                            "fleetaccount",
                            "fleet_account",
                            "fleetnumber",
                            "fleet_number",
                            "driverid",
                            "driver_id",
                            "drivernumber",
                            "driver_number",
                            "drivername",
                            "driver_name",
                            "vin",
                            "vehicleid",
                            "vehicle_id",
                            "licenseplate",
                            "license_plate",
                            "licenceplate",
                            "licence_plate",
                            "registrationnumber",
                            "registration_number"),
            RedactionClassification.COMMERCIAL_PRICING,
                    List.of(
                            "price",
                            "unitprice",
                            "unit_price",
                            "netprice",
                            "net_price",
                            "grossprice",
                            "gross_price",
                            "listprice",
                            "list_price",
                            "purchaseprice",
                            "purchase_price",
                            "retailprice",
                            "retail_price",
                            "discount",
                            "discountrate",
                            "discount_rate",
                            "discountpercent",
                            "discount_percent",
                            "rebate",
                            "rebaterate",
                            "rebate_rate",
                            "netamount",
                            "net_amount",
                            "grossamount",
                            "gross_amount"));

    /**
     * Compiled per-classification patterns. Built over {@code values()} rather than the map's keys so a
     * new enum constant without a vocabulary fails class initialization — loudly, at startup — instead of
     * silently redacting nothing for bindings that declare it.
     */
    private static final Map<RedactionClassification, NamePatterns> CLASSIFICATION_PATTERNS = java.util.Arrays.stream(
                    RedactionClassification.values())
            .collect(java.util.stream.Collectors.toUnmodifiableMap(java.util.function.Function.identity(), c -> {
                List<String> names = CLASSIFICATION_NAMES.get(c);
                if (names == null || names.isEmpty()) {
                    throw new IllegalStateException(
                            "RedactionClassification." + c + " has no field-name vocabulary in PayloadRedactor;"
                                    + " a class that redacts nothing must not exist");
                }
                return NamePatterns.compile(names);
            }));

    /**
     * Names redacted in a <strong>query string only</strong>, on top of {@link #SENSITIVE_NAMES}.
     *
     * <p>Deliberately a separate set rather than widening {@code SENSITIVE_NAMES}. These are short, generic
     * words that are unambiguous as URL parameters and dangerously ambiguous in a document body: widening the
     * shared set would newly redact {@code <Key>} and {@code "key"} inside vendor payloads at
     * {@code REDACTED}, silently destroying legitimate commercial content — part keys, sort keys, price keys —
     * in a store that keeps it for 400 days. The blast radius of a false positive differs by position, so the
     * rule does too.
     *
     * <p>Chosen from the conventions actually in use: {@code key} is Google's, {@code token} and
     * {@code api-key} are near-universal, {@code sig}/{@code signature} appear in every pre-signed URL scheme
     * (S3, Azure SAS), and {@code subscription-key} is Azure API Management's. A signed URL's token is a
     * bearer credential for as long as it is valid, and a redirect {@code Location} routinely carries one.
     */
    private static final List<String> URI_ONLY_SENSITIVE_NAMES = List.of(
            "key",
            "token",
            "api-key",
            "apikey",
            "access-token",
            "refresh-token",
            "id-token",
            "sig",
            "signature",
            "subscription-key",
            "auth",
            "sas",
            "x-amz-security-token",
            "x-amz-signature",
            "x-amz-credential",
            "awsaccesskeyid",
            "code",
            "id_token_hint");

    /** Query-parameter patterns: the shared names plus the URI-only ones. */
    private static final List<Pattern> QUERY_PARAM_PATTERNS = java.util.stream.Stream.concat(
                    SENSITIVE_NAMES.stream(), URI_ONLY_SENSITIVE_NAMES.stream())
            .distinct()
            .map(name -> Pattern.compile("(^|[?&])(" + Pattern.quote(name) + ")=([^&]*)", Pattern.CASE_INSENSITIVE))
            .toList();

    /**
     * A URL embedded in free text, e.g. a vendor {@code Location} header quoted in a failure message. Stops at
     * whitespace and at the characters that conventionally close a URL in prose.
     */
    private static final Pattern EMBEDDED_URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

    /** {@code //user:password@host} — userinfo, which is a plaintext credential in a URL (ADR-0050 §4). */
    private static final Pattern URL_USERINFO = Pattern.compile("(//)([^/@\\s]+)(@)");

    private PayloadRedactor() {
        // static rules
    }

    /**
     * The four name-addressable pattern families, compiled once from one name vocabulary. One instance
     * exists for the credential set and one per {@link RedactionClassification}; applying an instance is
     * what {@code REDACTED} means for that vocabulary.
     */
    private record NamePatterns(
            List<Pattern> xmlElements,
            List<Pattern> xmlAttributes,
            List<Pattern> jsonFields,
            List<Pattern> formFields) {

        @NonNull
        static NamePatterns compile(@NonNull List<String> names) {
            return new NamePatterns(
                    // <Password>value</Password> and <ns:Password ...>value</ns:Password>
                    names.stream()
                            .map(name -> Pattern.compile(
                                    "(<(?:[\\w.-]+:)?" + Pattern.quote(name) + "\\b[^>]*>)(.*?)(</(?:[\\w.-]+:)?"
                                            + Pattern.quote(name) + "\\s*>)",
                                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
                            .toList(),
                    // Password="value" and password='value' attributes
                    names.stream()
                            .map(name -> Pattern.compile(
                                    "(\\b" + Pattern.quote(name) + "\\s*=\\s*)([\"'])(.*?)(\\2)",
                                    Pattern.CASE_INSENSITIVE))
                            .toList(),
                    // "password": "value" and "password":123 in JSON
                    names.stream()
                            .map(name -> Pattern.compile(
                                    "(\"" + Pattern.quote(name) + "\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\s]+)",
                                    Pattern.CASE_INSENSITIVE))
                            .toList(),
                    // client_secret=value&... in form-encoded bodies
                    names.stream()
                            .map(name -> Pattern.compile(
                                    "(\\b" + Pattern.quote(name) + "=)([^&\\s]*)", Pattern.CASE_INSENSITIVE))
                            .toList());
        }

        /** Applies all four families, value-preserving in shape exactly as the class javadoc describes. */
        @NonNull
        String apply(@NonNull String payload) {
            String result = payload;
            for (Pattern pattern : xmlElements) {
                result = pattern.matcher(result)
                        .replaceAll(matchResult ->
                                Matcher.quoteReplacement(matchResult.group(1) + REDACTED + matchResult.group(3)));
            }
            for (Pattern pattern : xmlAttributes) {
                result = pattern.matcher(result)
                        .replaceAll(matchResult -> Matcher.quoteReplacement(
                                matchResult.group(1) + matchResult.group(2) + REDACTED + matchResult.group(4)));
            }
            for (Pattern pattern : jsonFields) {
                result = pattern.matcher(result)
                        .replaceAll(
                                matchResult -> Matcher.quoteReplacement(matchResult.group(1) + "\"" + REDACTED + "\""));
            }
            for (Pattern pattern : formFields) {
                result = pattern.matcher(result)
                        .replaceAll(matchResult -> Matcher.quoteReplacement(matchResult.group(1) + REDACTED));
            }
            return result;
        }
    }

    /**
     * Redacts credential values out of a request URI before it is stored (ADR-0050 §4/§7).
     *
     * <h2>Why the URI needs this at all</h2>
     *
     * {@code endpoint_uri} is a <em>metadata</em> column, and metadata is the part §7 keeps: it is stored
     * unencrypted, it survives the 400-day purge that nulls the payload columns, and the metadata listing
     * returns it to anyone holding {@code supplier:audit:read}. It is also populated at every capture level,
     * so a binding set to {@code METADATA_ONLY} — configured to retain no content — would still have retained
     * the full URI indefinitely. The same is true of {@code failure_detail}, which is why that is redacted
     * too.
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
        // Userinfo first, and at every capture level including METADATA_ONLY. A password in
        // //user:pass@host is a plaintext credential (ADR-0050 §4) and is not part of the query string, so
        // dropping the query alone would leave it intact.
        String result = stripUserinfo(uri);
        int queryStart = result.indexOf('?');
        if (captureLevel == PayloadCaptureLevel.METADATA_ONLY) {
            return queryStart < 0 ? result : result.substring(0, queryStart);
        }
        if (queryStart < 0) {
            return result;
        }
        return result.substring(0, queryStart + 1) + redactQuery(result.substring(queryStart + 1));
    }

    /**
     * Redacts credential-bearing query parameters and userinfo out of any URL embedded in free text.
     *
     * <p>For {@code failure_detail}, which quotes vendor responses. A redirect {@code Location} routinely
     * carries a signed URL whose token is a bearer credential for as long as it is valid, and this column is
     * stored unencrypted, is not covered by the payload purge, and is returned by the metadata listing — under
     * a schema that until now asserted it never contains a credential. That claim is now a control.
     *
     * <p>Applied at every capture level: an operator-facing failure message has no legitimate need for the
     * token inside a signed URL, so unlike the payload columns there is no level at which keeping it is the
     * point.
     *
     * @param text free text that may embed one or more URLs, or {@code null}
     * @return the text with any embedded URL credentials redacted
     */
    @Nullable
    public static String redactEmbeddedUris(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return EMBEDDED_URL
                .matcher(text)
                .replaceAll(match -> Matcher.quoteReplacement(redactUri(match.group(), PayloadCaptureLevel.FULL)));
    }

    /** Replaces {@code //user:secret@} with {@code //REDACTED@}, keeping the URL parseable. */
    @NonNull
    private static String stripUserinfo(@NonNull String uri) {
        return URL_USERINFO
                .matcher(uri)
                .replaceAll(match -> Matcher.quoteReplacement(match.group(1) + REDACTED + match.group(3)));
    }

    /** Redacts sensitive parameters in a bare query string, preserving parameter names and order. */
    @NonNull
    private static String redactQuery(@NonNull String query) {
        String result = query;
        for (Pattern pattern : QUERY_PARAM_PATTERNS) {
            result = pattern.matcher(result)
                    .replaceAll(match -> Matcher.quoteReplacement(match.group(1) + match.group(2) + "=" + REDACTED));
        }
        return result;
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
        return redact(payload, Set.of());
    }

    /**
     * Redacts credential values plus the named fields of the binding's declared classifications.
     *
     * <p>The credential vocabulary is always applied — a binding cannot configure it away — and each
     * classification contributes its own compiled vocabulary on top (ADR-0050 §7 minimization,
     * issue #1259).
     *
     * @param payload the raw wire document, or {@code null}
     * @param classifications the binding's declared classifications; empty means credentials only
     * @return the payload with the configured values replaced, or {@code null} when given {@code null}
     */
    @Nullable
    public static String redact(@Nullable String payload, @NonNull Set<RedactionClassification> classifications) {
        Objects.requireNonNull(classifications, "classifications must not be null");
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        String result = CREDENTIAL_PATTERNS.apply(payload);
        for (RedactionClassification classification : classifications) {
            result = CLASSIFICATION_PATTERNS.get(classification).apply(result);
        }
        return result;
    }

    /**
     * Applies a binding's capture level to a payload, with credential redaction only.
     *
     * @param payload the raw wire document, or {@code null}
     * @param captureLevel the level governing this exchange
     * @return {@code null} for {@code METADATA_ONLY}; the payload unchanged for {@code FULL}; the
     *     redacted payload for {@code REDACTED}
     */
    @Nullable
    public static String applyCaptureLevel(@Nullable String payload, @NonNull PayloadCaptureLevel captureLevel) {
        return applyCaptureLevel(payload, captureLevel, Set.of());
    }

    /**
     * Applies a binding's capture level to a payload, honouring its declared classifications.
     *
     * @param payload the raw wire document, or {@code null}
     * @param captureLevel the level governing this exchange
     * @param classifications the binding's declared classifications; only consulted at {@code REDACTED}
     * @return {@code null} for {@code METADATA_ONLY}; the payload unchanged for {@code FULL}; the
     *     redacted payload for {@code REDACTED}
     */
    @Nullable
    public static String applyCaptureLevel(
            @Nullable String payload,
            @NonNull PayloadCaptureLevel captureLevel,
            @NonNull Set<RedactionClassification> classifications) {
        return switch (captureLevel) {
            case METADATA_ONLY -> null;
            // FULL means "keep the document as sent" -- the operator explicitly accepted that for
            // this binding, and classifications deliberately do NOT apply: declaring one narrows
            // REDACTED, it does not turn FULL into a fourth level.
            case FULL -> payload;
            case REDACTED -> redact(payload, classifications);
        };
    }
}
