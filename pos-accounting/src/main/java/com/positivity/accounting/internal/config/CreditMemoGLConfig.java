package com.positivity.accounting.internal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Configuration for GL account mappings used in Credit Memo processing.
 * 
 * Default GL accounts can be overridden in application.yml:
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
@Configuration
@ConfigurationProperties(prefix = "pos.accounting.credit-memo")
public class CreditMemoGLConfig {

    /**
     * GL account for revenue reversals.
     * Default: Revenue account (typically 4000-4999 range)
     */
    private UUID revenueAccountId = UUID.fromString("00000000-0000-0000-0000-000000004000");

    /**
     * GL account for sales tax payable reversals.
     * Default: Tax Liability account (typically 2200-2299 range)
     */
    private UUID taxPayableAccountId = UUID.fromString("00000000-0000-0000-0000-000000002200");

    /**
     * GL account for accounts receivable reductions.
     * Default: AR account (typically 1200-1299 range)
     */
    private UUID arAccountId = UUID.fromString("00000000-0000-0000-0000-000000001200");
}
