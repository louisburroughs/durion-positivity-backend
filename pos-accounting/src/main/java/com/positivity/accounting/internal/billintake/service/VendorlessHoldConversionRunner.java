package com.positivity.accounting.internal.billintake.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs {@link VendorlessHoldConverter} once at startup (CAP:550 S25, #2518). Never blocks startup. Turn it off with
 * {@code pos.accounting.bill-intake.vendorless-hold-conversion.enabled=false}, as the test profiles do.
 *
 * <p>Follow-up: delete this runner and the converter once alpha and prod have converted their open
 * {@code VENDOR_ID_MISSING} holds; the reason is retired and nothing produces it any more.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "pos.accounting.bill-intake.vendorless-hold-conversion.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class VendorlessHoldConversionRunner implements ApplicationRunner {

    private final VendorlessHoldConverter converter;

    public VendorlessHoldConversionRunner(VendorlessHoldConverter converter) {
        this.converter = converter;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int converted = converter.convertAll();
            if (converted > 0) {
                log.info("Converted {} held supplier invoice(s) with no vendor into bill drafts", converted);
            }
        } catch (RuntimeException e) {
            log.error("Converting held vendor-less supplier invoices failed; continuing startup", e);
        }
    }
}
