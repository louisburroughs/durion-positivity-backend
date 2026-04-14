package com.positivity.accounting.internal.payment;

import com.positivity.accounting.internal.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for payment gateway execution requests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayPaymentRequest {

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code")
    private String currency;

    @NotNull(message = "paymentMethod is required")
    private PaymentMethod paymentMethod;

    @NotBlank(message = "vendorId is required")
    private String vendorId;

    @NotBlank(message = "paymentSource is required")
    private String paymentSource;

    @Size(max = 1000, message = "memo must be at most 1000 characters")
    private String memo;

    @Builder.Default
    @NotNull(message = "metadata cannot be null")
    private List<@NotBlank(message = "metadata entries cannot be blank") String> metadata = new ArrayList<>();
}
