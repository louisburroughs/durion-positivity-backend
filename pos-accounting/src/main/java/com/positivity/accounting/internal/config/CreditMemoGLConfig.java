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
 * <p><strong>Required Configuration:</strong> All GL account IDs must be configured in application.yml.
 * The application will fail to start if any required property is missing.</p>
 * 
 * <pre>
 * pos:
 *   accounting:
 *     credit-memo:
 *       revenue-account-id: "12345678-1234-5678-1234-567812345678"
 *       tax-payable-account-id: "87654321-4321-8765-4321-876543218765"
 *       ar-account-id: "11111111-2222-3333-4444-555555555555"
 * </pre>
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Data
@Configuration
@Validated
@ConfigurationProperties(prefix = "pos.accounting.credit-memo")
public class CreditMemoGLConfig {

    /**
     * GL account for revenue reversals.
     * Required: Must be configured in application.yml (typically 4000-4999 range)
     */
    @NotNull(message = "pos.accounting.credit-memo.revenue-account-id must be configured")
    private UUID revenueAccountId;

    /**
     * GL account for sales tax payable reversals.
     * Required: Must be configured in application.yml (typically 2200-2299 range)
     */
    @NotNull(message = "pos.accounting.credit-memo.tax-payable-account-id must be configured")
    private UUID taxPayableAccountId;

    /**
     * GL account for accounts receivable reductions.
     * Required: Must be configured in application.yml (typically 1200-1299 range)
     */
    @NotNull(message = "pos.accounting.credit-memo.ar-account-id must be configured")
    private UUID arAccountId;
}
