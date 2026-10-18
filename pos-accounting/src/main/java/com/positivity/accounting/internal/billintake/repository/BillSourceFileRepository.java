package com.positivity.accounting.internal.billintake.repository;

import com.positivity.accounting.internal.billintake.entity.BillSourceFile;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stored bill files (CAP:550 S25, #2518), originals and derived pages. */
public interface BillSourceFileRepository extends JpaRepository<BillSourceFile, UUID> {

    /** The derived pages of an original. */
    @NonNull
    List<BillSourceFile> findByDerivedFrom(@NonNull UUID derivedFrom);
}
