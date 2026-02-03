package com.positivity.customer.internal.repository;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.customer.internal.entity.Person;

/**
 * Repository for Person entity operations.
 */
@Repository
public interface PersonRepository extends JpaRepository<Person, UUID> {

        /**
         * Find persons by last name (case-insensitive).
         *
         * @param lastName the last name to search for
         * @return list of matching persons
         */
        List<Person> findByLastNameIgnoreCase(@NonNull String lastName);

        /**
         * Find persons by first and last name (case-insensitive).
         *
         * @param firstName the first name
         * @param lastName  the last name
         * @return list of matching persons
         */
        List<Person> findByFirstNameIgnoreCaseAndLastNameIgnoreCase(@NonNull String firstName,
                        @NonNull String lastName);

        /**
         * Search persons by partial name match.
         *
         * @param searchTerm the search term
         * @return list of matching persons
         */
        @Query("SELECT p FROM Person p WHERE " +
                        "LOWER(p.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
                        "LOWER(p.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
        List<Person> searchByName(@Param("searchTerm") @NonNull String searchTerm);

        /**
         * Find persons by contact point value (email or phone).
         *
         * @param value the contact point value
         * @return list of matching persons
         */
        @Query("SELECT DISTINCT p FROM Person p JOIN p.contactPoints cp WHERE " +
                        "LOWER(cp.value) LIKE LOWER(CONCAT('%', :value, '%'))")
        List<Person> findByContactPointValue(@Param("value") @NonNull String value);
}
