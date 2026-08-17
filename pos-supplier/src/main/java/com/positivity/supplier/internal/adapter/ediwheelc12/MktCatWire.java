package com.positivity.supplier.internal.adapter.ediwheelc12;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * C1.2 MKCAT wire records, modelled literally (ADR-0051 §2).
 *
 * <p>The spec's element names are upper-camel and its values are frequently wrapped one level deeper
 * than a reader expects — {@code Name} is a list of language-tagged objects, not a string. These
 * records reproduce that rather than tidying it, so the tidying happens in one place with a name on
 * it.
 *
 * <p>Unknown properties are ignored throughout. The catalogue is published centrally to many
 * consumers and gains fields on the standards body's schedule, not ours; a strict reader would fail
 * a whole catalogue import over an addition that concerned somebody else.
 */
final class MktCatWire {

    private MktCatWire() {}

    /** An entry in {@code GET /treadDesignVariants/}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VariantListEntry(
            @Nullable String TreadDesignVariant,
            @Nullable String TreadDesign1,
            @Nullable String TreadDesign2,
            @Nullable String ProductName) {}

    /** {@code GET /treadDesignVariants/{ID}/}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VariantDetail(
            @Nullable String TreadDesignVariant,
            @Nullable String DateCreated,
            @Nullable String DateLastModified,
            @Nullable String TreadDesign1,
            @Nullable String TreadDesign2,
            @Nullable String ProductName,
            @Nullable String Brand,
            @Nullable String VehicleType,
            @Nullable String Seasonality,
            @Nullable String MainFunction,
            @Nullable String TreadPattern) {}

    /**
     * An image entry from {@code GET /treadDesignVariants/{ID}/images}.
     *
     * <p>{@code URI} is where the bytes are, and is the only field this integration acts on: the
     * artwork is fetched and stored, and the URI is kept as provenance rather than republished as a
     * render source.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImageEntry(
            @Nullable String ImageType,
            @Nullable String DefinitionHeight,
            @Nullable String DefinitionWidth,
            @Nullable String Resolution,
            @Nullable String FileType,
            @Nullable String FileName,
            @Nullable String URI) {}

    /** An entry from {@code GET /treadDesignVariants/{ID}/languageDependentData}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LanguageEntry(
            @Nullable String LanguageID,
            @Nullable String Language,
            @Nullable String ProductName,
            @Nullable String Description,
            @Nullable String FootNotes,
            @Nullable List<AdditionalGraphic> AdditionalGraphic) {}

    /** Extra artwork attached to language-dependent data rather than to the variant. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdditionalGraphic(
            @Nullable String Graphic, @Nullable String URI) {}

    /** An entry from {@code GET /suppliers/}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SupplierEntry(
            @Nullable String PartyID,
            @Nullable String AgencyCode,
            @Nullable List<NameEntry> Name) {}

    /** A language-tagged name. The catalogue wraps names one level deeper than a reader expects. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NameEntry(@Nullable String LanguageID, @Nullable String Name) {}
}
