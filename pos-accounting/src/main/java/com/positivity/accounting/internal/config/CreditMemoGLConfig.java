package com.positivity.accounting.internal.config;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Configuration for GL account mappings used in Credit Memo processing.
 * 
 * All GL accounts must be configured in application.yml:
 * 
 * <pre>
 * pos:
 *   accounting:
 *     credit-memo:
 *       revenue-account-id: "..."
 *       tax-payable-account-id: "..."
 *       ar-account-id: "..."
 * </pre>
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "pos.accounting.credit-memo")
public class CreditMemoGLConfig {

    /**
     * GL account for revenue reversals.
     * Required: Revenue account (typically 4000-4999 range)
     */
    @NotNull
    private UUID revenueAccountId;

    /**
     * GL account for sales tax payable reversals.
     * Required: Tax Liability account (typically 2200-2299 range)
     */
    @NotNull
    private UUID taxPayableAccountId;

    /**
     * GL account for accounts receivable reductions.
     * Required: AR account (typically 1200-1299 range)
     */
    @NotNull
    private UUID arAccountId;
}
