package com.positivity.accounting.internal.billintake.repository;

import com.positivity.accounting.internal.billintake.entity.BillSourceFileContent;
import java.util.Collection;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bytes of stored bill files (CAP:550 S25, #2518). */
public interface BillSourceFileContentRepository extends JpaRepository<BillSourceFileContent, UUID> {

    /** Deletes the bytes of these files: a purge. */
    void deleteByFileIdIn(@NonNull Collection<UUID> fileIds);
}
