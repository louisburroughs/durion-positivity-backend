package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The canonical field names each {@link DomainType}'s loader record carries, read from the record
 * classes themselves.
 *
 * <p>Column mapping is driven by {@code RuleBasedContentDetectionServiceImpl.inferTargetField},
 * which is a hand-written synonym table. A synonym table alone cannot be the only mapping source
 * for a domain: it silently drops any column nobody thought to list. The LOCATION table, for
 * instance, has never carried {@code addressLine2} or {@code active} — which did not matter while
 * that domain read its file positionally, and would have quietly emptied two columns the moment it
 * did not.
 *
 * <p>So the synonym table handles genuinely different spellings ({@code zip} → {@code postalCode}),
 * and this class handles the ordinary case of a header that already names its field. Reading the
 * names off the record classes means a field added to a record is mappable the same day, with no
 * second list to remember to update.
 *
 * <p>Matching ignores case and every separator, so {@code addressLine1}, {@code address_line_1} and
 * {@code ADDRESS LINE 1} all reach the same field.
 */
public final class DomainRecordFields {

    private static final Map<DomainType, Class<?>> RECORD_TYPES = recordTypes();

    /** Per domain: comparison key (lowercase, alphanumeric only) to the record's field name. */
    private static final Map<DomainType, Map<String, String>> FIELDS_BY_DOMAIN = index();

    private DomainRecordFields() {}

    private static Map<DomainType, Class<?>> recordTypes() {
        Map<DomainType, Class<?>> types = new EnumMap<>(DomainType.class);
        types.put(DomainType.CATALOG_PRODUCT, CatalogProductRecord.class);
        types.put(DomainType.CUSTOMER, CustomerPersonRecord.class);
        types.put(DomainType.COMMERCIAL_CUSTOMER, CommercialCustomerRecord.class);
        types.put(DomainType.LOCATION, LocationRecord.class);
        types.put(DomainType.PERSON, PersonRecord.class);
        types.put(DomainType.BASE_PRICE, BasePriceRecord.class);
        types.put(DomainType.VEHICLE, VehicleBulkRecord.class);
        types.put(DomainType.VEHICLE_FITMENT, VehicleFitmentRecord.class);
        return types;
    }

    private static Map<DomainType, Map<String, String>> index() {
        Map<DomainType, Map<String, String>> byDomain = new EnumMap<>(DomainType.class);
        RECORD_TYPES.forEach((domain, recordType) -> {
            Map<String, String> byKey = new LinkedHashMap<>();
            Arrays.stream(recordType.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .forEach(field -> byKey.putIfAbsent(comparisonKey(field.getName()), field.getName()));
            byDomain.put(domain, Map.copyOf(byKey));
        });
        return Map.copyOf(byDomain);
    }

    /**
     * The record field a header names outright, or null when the header is not simply a field name.
     *
     * @param header a source column header, raw or already normalized — either works
     * @param domainType the domain whose record is being filled
     */
    @Nullable
    public static String matchCanonicalField(@NonNull String header, @NonNull DomainType domainType) {
        Map<String, String> fields = FIELDS_BY_DOMAIN.get(domainType);
        return fields == null ? null : fields.get(comparisonKey(header));
    }

    /** The record's field names, in declaration order. Empty for a domain with no record type yet. */
    @NonNull
    public static List<String> fieldsOf(@NonNull DomainType domainType) {
        Map<String, String> fields = FIELDS_BY_DOMAIN.get(domainType);
        return fields == null ? List.of() : List.copyOf(fields.values());
    }

    /** Lowercase, letters and digits only — so case and any separator style compare equal. */
    private static String comparisonKey(String value) {
        StringBuilder key = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                key.append(Character.toLowerCase(c));
            }
        }
        return key.toString().toLowerCase(Locale.ROOT);
    }

    /** Domains that have a loader record, for tests and diagnostics. */
    @NonNull
    public static List<DomainType> mappedDomains() {
        return new ArrayList<>(RECORD_TYPES.keySet());
    }
}
