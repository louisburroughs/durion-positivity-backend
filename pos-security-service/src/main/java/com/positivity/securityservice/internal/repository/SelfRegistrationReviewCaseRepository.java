package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.SelfRegistrationReviewCase;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseStatus;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelfRegistrationReviewCaseRepository extends JpaRepository<SelfRegistrationReviewCase, UUID> {

    @NonNull
    List<SelfRegistrationReviewCase> findByStatusOrderByCreatedAtDesc(@NonNull SelfRegistrationCaseStatus status);

    @NonNull
    List<SelfRegistrationReviewCase> findByCaseTypeAndStatusOrderByCreatedAtDesc(
            @NonNull SelfRegistrationCaseType caseType, @NonNull SelfRegistrationCaseStatus status);
}
