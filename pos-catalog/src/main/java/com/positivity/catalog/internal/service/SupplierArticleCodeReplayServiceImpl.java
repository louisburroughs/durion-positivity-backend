package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.config.SupplierArticleCodeReplayService;
import com.positivity.catalog.internal.dto.SupplierArticleCodeReplayResultDto;
import com.positivity.catalog.internal.entity.SupplierArticleCodeEntity;
import com.positivity.catalog.internal.repository.SupplierArticleCodeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-emits {@code catalog.supplier-article-code.updated} the same way {@code
 * ProductFactReplayServiceImpl} re-emits {@code catalog.product.updated} (#1309): through the same
 * publisher live traffic uses, cursor-paged over the current-state table's own id so a replay of a
 * large vendor catalogue can be stopped and resumed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierArticleCodeReplayServiceImpl implements SupplierArticleCodeReplayService {

    /** Bound on one call, so a mistyped limit cannot turn a replay into a broker flood. */
    public static final int MAX_LIMIT = 1000;

    private final SupplierArticleCodeRepository supplierArticleCodeRepository;
    private final CatalogFactPublisher catalogFactPublisher;
    private final Clock clock;

    @Override
    @NonNull
    @Transactional
    public SupplierArticleCodeReplayResultDto replayPage(
            @Nullable UUID afterId, @Nullable Instant updatedSince, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Instant startedAt = Instant.now(clock);

        List<SupplierArticleCodeEntity> rows =
                supplierArticleCodeRepository.findForReplay(afterId, updatedSince, PageRequest.of(0, pageSize));

        for (SupplierArticleCodeEntity row : rows) {
            catalogFactPublisher.publishSupplierArticleCodeUpdated(row);
        }

        boolean complete = rows.size() < pageSize;
        UUID nextAfterId = complete || rows.isEmpty() ? null : rows.getLast().getId();

        log.info(
                "Replayed {} supplier-article-code facts (after={}, updatedSince={}, complete={})",
                rows.size(),
                afterId,
                updatedSince,
                complete);

        return new SupplierArticleCodeReplayResultDto(rows.size(), nextAfterId, complete, updatedSince, startedAt);
    }
}
