package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The tenant's input-tax recovery settings (CAP:550 S32d item 2): per registered regime whether recovery is on and
 * where it posts, the evidence rules, each petty-expense category's recovery, and the change history.
 *
 * @param regimes one row per regime the tenant holds a registration for; empty without one (every USD tenant)
 * @param evidenceRules the evidence rules of each registered country; null when pos-tax did not answer
 * @param categories every petty-expense category with its recovery
 * @param history every change to a category's recovery, oldest first
 * @param asOf when the read was made
 */
@Schema(description = "Input-tax recovery: regimes, evidence rules, category shares and their history")
public record InputTaxRecoveryResponse(
        @ArraySchema(
                arraySchema = @Schema(description = "One row per registered regime; empty without a registration"),
                schema = @Schema(implementation = Regime.class))
        @NonNull
        List<Regime> regimes,

        @ArraySchema(
                arraySchema =
                        @Schema(
                                description = "Evidence rules per registered country; null when the tax service did"
                                        + " not answer",
                                nullable = true),
                schema = @Schema(implementation = CountryEvidenceRules.class))
        @Nullable
        List<CountryEvidenceRules> evidenceRules,

        @ArraySchema(
                arraySchema = @Schema(description = "Every petty-expense category with its recovery, in code order"),
                schema = @Schema(implementation = Category.class))
        @NonNull
        List<Category> categories,

        @ArraySchema(
                arraySchema = @Schema(description = "Every change to a category's recovery, oldest first"),
                schema = @Schema(implementation = HistoryItem.class))
        @NonNull
        List<HistoryItem> history,

        @Schema(description = "When the read was made", requiredMode = REQUIRED) @NonNull
        Instant asOf) {

    /**
     * One registered regime.
     *
     * @param countryCode the registration's country
     * @param regime the regime
     * @param enabled whether recovery under it is on today; null when the tax service could not say
     * @param registration the registration in effect today, or null
     * @param account the account its recovered tax posts to today, or null when unmapped
     */
    @Schema(name = "InputTaxRecoveryRegime", description = "One regime the tenant is registered for")
    public record Regime(
            @Schema(description = "ISO 3166-1 alpha-2 country", example = "ZZ", requiredMode = REQUIRED) @NonNull
            String countryCode,

            @Schema(
                    description = "Regime code, as the country profile names it",
                    example = "REGIME_1",
                    requiredMode = REQUIRED)
            @NonNull
            String regime,

            @Schema(
                    description = "Whether recovery is on today: a registration in effect and the country's currency"
                            + " equal to the functional currency; null when the tax service could not say",
                    nullable = true,
                    requiredMode = REQUIRED)
            @Nullable
            Boolean enabled,

            @Schema(description = "The registration in effect today, or null", nullable = true, requiredMode = REQUIRED)
            @Nullable
            Registration registration,

            @Schema(
                    description = "The account recovered tax posts to today, or null when unmapped",
                    nullable = true,
                    requiredMode = REQUIRED)
            @Nullable
            Account account) {}

    /**
     * A registration.
     *
     * @param number the registration number
     * @param since its first effective date
     */
    @Schema(name = "InputTaxRecoveryRegistration", description = "The registration in effect")
    public record Registration(
            @Schema(description = "Registration number", example = "ZZ0000000", requiredMode = REQUIRED) @NonNull
            String number,

            @Schema(description = "First effective date", example = "2026-01-01", requiredMode = REQUIRED) @NonNull
            LocalDate since) {

        @Override
        public @NonNull String toString() {
            return "Registration[since=" + since + "]";
        }
    }

    /**
     * An account.
     *
     * @param code its number
     * @param name its name
     */
    @Schema(name = "InputTaxRecoveryAccount", description = "An account recovered tax posts to")
    public record Account(
            @Schema(description = "Account number", example = "1250", requiredMode = REQUIRED) @NonNull
            String code,

            @Schema(description = "Account name", example = "Recoverable tax", requiredMode = REQUIRED) @NonNull
            String name) {}

    /**
     * One country's evidence rules.
     *
     * @param countryCode the country
     * @param currencyCode the currency of every threshold
     * @param rules the rules in effect today
     */
    @Schema(name = "InputTaxRecoveryCountryEvidenceRules", description = "A country's evidence rules in effect today")
    public record CountryEvidenceRules(
            @Schema(description = "ISO 3166-1 alpha-2 country", example = "ZZ", requiredMode = REQUIRED) @NonNull
            String countryCode,

            @Schema(
                    description = "ISO 4217 currency of every threshold",
                    example = "EUR",
                    nullable = true,
                    requiredMode = REQUIRED)
            @Nullable
            String currencyCode,

            @ArraySchema(
                    arraySchema = @Schema(description = "Rules in effect today"),
                    schema = @Schema(implementation = EvidenceRule.class))
            @NonNull
            List<EvidenceRule> rules) {}

    /**
     * One evidence rule.
     *
     * @param rule the evidence required
     * @param threshold the document total, tax included, from which it applies
     * @param appliesTo the document types it applies to
     */
    @Schema(
            name = "InputTaxRecoveryEvidenceRule",
            description = "From which total a document needs a piece of evidence")
    public record EvidenceRule(
            @Schema(
                    description = "Evidence required",
                    example = "SUPPLIER_REGISTRATION_NUMBER",
                    requiredMode = REQUIRED)
            @NonNull
            String rule,

            @Schema(
                    description = "Document total, tax included, from which it applies",
                    example = "100.00",
                    requiredMode = REQUIRED)
            @Nullable
            BigDecimal threshold,

            @ArraySchema(
                    arraySchema = @Schema(description = "Document types it applies to"),
                    schema = @Schema(example = "DRAWER_RECEIPT"))
            @NonNull
            List<String> appliesTo) {}

    /**
     * One petty-expense category's recovery.
     *
     * @param code the category
     * @param label its label
     * @param taxRecoverable whether its stated tax is recovered
     * @param recoverablePercent the share; null when not recoverable
     * @param version the setting's version, to send with a change
     */
    @Schema(name = "InputTaxRecoveryCategory", description = "A petty-expense category's tax recovery")
    public record Category(
            @Schema(description = "Permanent category code", example = "STAFF_MEALS", requiredMode = REQUIRED) @NonNull
            String code,

            @Schema(description = "The category's label", example = "Staff meals", requiredMode = REQUIRED) @NonNull
            String label,

            @Schema(description = "Whether its stated tax is recovered", requiredMode = REQUIRED)
            boolean taxRecoverable,

            @Schema(
                    description = "Share recovered; null when not recoverable",
                    example = "100.00",
                    nullable = true,
                    requiredMode = NOT_REQUIRED)
            @Nullable
            BigDecimal recoverablePercent,

            @Schema(
                    description = "The setting's version (0 when never set), to send with a change",
                    example = "0",
                    requiredMode = REQUIRED)
            int version) {}

    /**
     * One change to a category's recovery.
     *
     * @param effectiveFrom when it took effect
     * @param code the category
     * @param actor who made it
     * @param actorRole the role they acted in, when known
     * @param oldTaxRecoverable the value before; null for the first
     * @param oldRecoverablePercent the share before
     * @param newTaxRecoverable the value after
     * @param newRecoverablePercent the share after
     * @param reason the justification
     */
    @Schema(name = "InputTaxRecoveryHistoryItem", description = "One change to a category's tax recovery")
    public record HistoryItem(
            @Schema(description = "When it took effect", requiredMode = REQUIRED) @NonNull
            Instant effectiveFrom,

            @Schema(description = "Category code", example = "STAFF_MEALS", requiredMode = REQUIRED) @NonNull
            String code,

            @Schema(description = "Who made the change", example = "controller.cfo", requiredMode = REQUIRED) @NonNull
            String actor,

            @Schema(
                    description = "Role the actor acted in, when known",
                    example = "CONTROLLER",
                    nullable = true,
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String actorRole,

            @Schema(
                    description = "Recoverable before; null for the first setting",
                    nullable = true,
                    requiredMode = NOT_REQUIRED)
            @Nullable
            Boolean oldTaxRecoverable,

            @Schema(description = "Share before", nullable = true, requiredMode = NOT_REQUIRED) @Nullable
            BigDecimal oldRecoverablePercent,

            @Schema(description = "Recoverable after", requiredMode = REQUIRED)
            boolean newTaxRecoverable,

            @Schema(description = "Share after", nullable = true, requiredMode = NOT_REQUIRED) @Nullable
            BigDecimal newRecoverablePercent,

            @Schema(
                    description = "Why",
                    example = "Meals are half recoverable under the regime",
                    requiredMode = REQUIRED)
            @NonNull
            String reason) {}
}
