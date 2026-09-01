package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentApplicationListRow;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only implementation of {@link PaymentApplicationQueryService} (Wave 2 E10, issue #1598).
 */
@Service
@Transactional(readOnly = true)
public class PaymentApplicationQueryServiceImpl implements PaymentApplicationQueryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplicationQueryServiceImpl.class);

    /**
     * Hard cap on page size; also the fallback when the caller's requested page size is
     * non-positive or over this cap.
     */
    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentApplicationRepository paymentApplicationRepository;
    private final PaymentApplicationReversalRepository paymentApplicationReversalRepository;

    public PaymentApplicationQueryServiceImpl(
            PaymentApplicationRepository paymentApplicationRepository,
            PaymentApplicationReversalRepository paymentApplicationReversalRepository) {
        this.paymentApplicationRepository = paymentApplicationRepository;
        this.paymentApplicationReversalRepository = paymentApplicationReversalRepository;
    }

    @Override
    public @NonNull Page<PaymentApplicationListRow> listByAppliedDateWindow(
            @NonNull LocalDate appliedFrom,
            @NonNull LocalDate appliedTo,
            boolean includeReversed,
            @NonNull Pageable pageable) {

        if (appliedTo.isBefore(appliedFrom)) {
            throw new IllegalArgumentException("appliedTo cannot be before appliedFrom");
        }
        long windowDays = ChronoUnit.DAYS.between(appliedFrom, appliedTo);
        if (windowDays > MAX_APPLIED_DATE_WINDOW_DAYS) {
            throw new IllegalArgumentException(
                    "Applied-date window cannot exceed " + MAX_APPLIED_DATE_WINDOW_DAYS + " days");
        }

        log.info(
                "Listing payment applications for window {} to {} | includeReversed={}",
                appliedFrom,
                appliedTo,
                includeReversed);

        int pageSize = pageable.getPageSize() > 0 && pageable.getPageSize() <= MAX_PAGE_SIZE
                ? pageable.getPageSize()
                : MAX_PAGE_SIZE;
        // Sort order is server-controlled (appliedAt ascending); any caller-supplied sort is
        // ignored, mirroring VendorBillService#listByDueDateWindow.
        Pageable effectivePageable =
                PageRequest.of(pageable.getPageNumber(), pageSize, Sort.by(Sort.Direction.ASC, "applicationTimestamp"));

        Instant start = appliedFrom.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = appliedTo.atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC);

        if (!includeReversed) {
            Page<PaymentApplication> page =
                    paymentApplicationRepository.findActiveByApplicationTimestampBetween(start, end, effectivePageable);
            return page.map(app -> toRow(app, false));
        }

        Page<PaymentApplication> page =
                paymentApplicationRepository.findByApplicationTimestampBetween(start, end, effectivePageable);
        List<UUID> pageIds = page.getContent().stream()
                .map(PaymentApplication::getPaymentApplicationId)
                .toList();
        Set<UUID> reversedIds = pageIds.isEmpty()
                ? Set.of()
                : Set.copyOf(paymentApplicationReversalRepository.findReversedApplicationIds(pageIds));

        return page.map(app -> toRow(app, reversedIds.contains(app.getPaymentApplicationId())));
    }

    private static @NonNull PaymentApplicationListRow toRow(@NonNull PaymentApplication app, boolean reversed) {
        return PaymentApplicationListRow.builder()
                .applicationId(app.getPaymentApplicationId())
                .paymentId(app.getPaymentId())
                .invoiceId(app.getInvoiceId())
                .appliedAt(app.getApplicationTimestamp())
                .amount(app.getAppliedAmount())
                .reversed(reversed)
                .build();
    }
}
