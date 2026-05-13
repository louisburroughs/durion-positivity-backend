package com.positivity.bulkloader.internal.repository;

import com.positivity.bulkloader.internal.entity.TusUpload;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TusUploadRepository extends JpaRepository<TusUpload, UUID> {

    /**
     * Atomically advances the upload offset only if the current offset matches the expected value.
     * Returns 1 on success, 0 on conflict (concurrent write or wrong offset).
     */
    @Modifying
    @Query("UPDATE TusUpload t SET t.uploadOffset = :newOffset "
            + "WHERE t.id = :id AND t.uploadOffset = :expectedOffset AND t.completed = false")
    int advanceOffset(
            @Param("id") UUID id, @Param("expectedOffset") long expectedOffset, @Param("newOffset") long newOffset);

    List<TusUpload> findByExpiresAtBeforeAndCompletedFalse(Instant threshold);
}
