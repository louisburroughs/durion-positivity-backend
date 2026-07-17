package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ClaimCodeSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimCodeSequenceRepository extends JpaRepository<ClaimCodeSequence, Integer> {

    /**
     * Locks the year's counter row ({@code SELECT ... FOR UPDATE}) so concurrent claim
     * creations serialize on the allocation (PRD §4).
     */
    @NonNull
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ClaimCodeSequence s where s.year = :year")
    Optional<ClaimCodeSequence> lockByYear(@Param("year") int year);
}
