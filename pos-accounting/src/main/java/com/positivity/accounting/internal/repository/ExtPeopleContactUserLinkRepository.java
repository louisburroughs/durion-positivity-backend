package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtPeopleContactUserLink;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The bound tenant's copy of pos-people-contact's user-person links (AP reads #2670); the tenant filter and RLS scope
 * every read, so the same username in another tenant never answers.
 */
public interface ExtPeopleContactUserLinkRepository extends JpaRepository<ExtPeopleContactUserLink, UUID> {

    /** A username's person's name, through its ACTIVE link; the names are CONFIDENTIAL and never logged. */
    interface LinkedName {

        @NonNull
        String getUsername();

        @Nullable
        String getFirstName();

        @Nullable
        String getLastName();
    }

    /**
     * The names of the persons {@code usernames} are linked to by an ACTIVE link, in one query: a response's actors
     * resolve together, never one query per row. A username without an ACTIVE link, or whose person is not in the copy,
     * has no row.
     */
    @Query("select l.username as username, p.firstName as firstName, p.lastName as lastName"
            + " from ExtPeopleContactUserLink l, ExtPeopleContactPerson p"
            + " where p.personId = l.personId and l.status = '" + ExtPeopleContactUserLink.ACTIVE + "'"
            + " and l.username in :usernames"
            + " order by l.username, l.linkId")
    @NonNull
    List<LinkedName> findActiveNames(@Param("usernames") @NonNull Collection<String> usernames);
}
