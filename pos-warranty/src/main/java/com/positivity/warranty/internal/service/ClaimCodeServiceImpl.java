package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.entity.ClaimCodeSequence;
import com.positivity.warranty.internal.repository.ClaimCodeSequenceRepository;
import java.time.Clock;
import java.time.Year;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Table-based claim-code allocator (PRD §4): locks the year's {@code claim_code_sequence} row
 * with {@code SELECT ... FOR UPDATE} and increments {@code next_seq}, so allocation is atomic
 * and works identically on H2 (dev) and Postgres. V1 pre-seeds rows for 2024–2075; for years
 * beyond the seed range the row is created on first use (single-statement insert — the
 * theoretical first-allocation race outside the seeded range surfaces as a unique-key
 * violation, which the caller may retry).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimCodeServiceImpl implements ClaimCodeService {

    private final Clock clock;
    private final ClaimCodeSequenceRepository claimCodeSequenceRepository;

    @Override
    @Transactional
    public @NonNull String nextClaimCode() {
        int year = Year.now(clock).getValue();
        ClaimCodeSequence sequence = claimCodeSequenceRepository
                .lockByYear(year)
                .orElseGet(() -> claimCodeSequenceRepository.saveAndFlush(
                        ClaimCodeSequence.builder().year(year).nextSeq(1L).build()));
        long allocated = sequence.getNextSeq();
        sequence.setNextSeq(allocated + 1);
        claimCodeSequenceRepository.save(sequence);
        String claimCode = String.format("WC-%d-%06d", year, allocated);
        log.debug("Allocated claim code {}", claimCode);
        return claimCode;
    }
}
