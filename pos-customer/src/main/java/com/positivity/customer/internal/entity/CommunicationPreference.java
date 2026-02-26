package com.positivity.customer.internal.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Communication preference entity storing consent flags and channel preferences
 * for a party.
 * 
 * <p>
 * Per issue #107, this entity tracks:
 * </p>
 * <ul>
 * <li>Email, SMS, and phone communication preferences
 * (OPT_IN|OPT_OUT|NOT_APPLICABLE)</li>
 * <li>Marketing communications consent</li>
 * <li>Custom consent flags stored as key-value pairs</li>
 * <li>Update source tracking (APP|API|ADMIN|IMPORT)</li>
 * </ul>
 * 
 * <p>
 * Default behavior: null preference values are interpreted as OPT_OUT.
 * </p>
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/107">Backend
 *      Issue #107</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "communication_preference", indexes = {
                @Index(name = "idx_comm_pref_party", columnList = "party_id")
})
@EntityListeners(AuditingEntityListener.class)
@Schema(description = "Communication preferences and consent flags for a party")
public class CommunicationPreference {

        @Id
        @GeneratedValue
        @UUIDv7Id
        @Column(name = "preference_id", columnDefinition = "UUID", updatable = false, nullable = false)
        @Schema(description = "Unique identifier for the communication preference")
        private UUID preferenceId;

        @Column(name = "party_id", nullable = false, unique = true)
        @Schema(description = "Party UUID this preference belongs to", example = "550e8400-e29b-41d4-a716-446655440000")
        private UUID partyId;

        @Column(name = "email_preference", length = 20)
        @Schema(description = "Email communication preference", example = "OPT_IN", allowableValues = { "OPT_IN",
                        "OPT_OUT",
                        "NOT_APPLICABLE" })
        private String emailPreference;

        @Column(name = "sms_preference", length = 20)
        @Schema(description = "SMS communication preference", example = "OPT_OUT", allowableValues = { "OPT_IN",
                        "OPT_OUT",
                        "NOT_APPLICABLE" })
        private String smsPreference;

        @Column(name = "phone_preference", length = 20)
        @Schema(description = "Phone communication preference", example = "OPT_IN", allowableValues = { "OPT_IN",
                        "OPT_OUT",
                        "NOT_APPLICABLE" })
        private String phonePreference;

        @Column(name = "marketing_preference", length = 20)
        @Schema(description = "Marketing communications preference", example = "OPT_OUT", allowableValues = { "OPT_IN",
                        "OPT_OUT" })
        private String marketingPreference;

        @ElementCollection(fetch = FetchType.EAGER)
        @CollectionTable(name = "consent_flag", joinColumns = @JoinColumn(name = "preference_id"))
        @MapKeyColumn(name = "flag_name", length = 100)
        @Column(name = "flag_value")
        @Builder.Default
        @Schema(description = "Custom consent flags as key-value pairs")
        private Map<String, Boolean> consentFlags = new HashMap<>();

        @Column(name = "preferences_note", length = 1000)
        @Schema(description = "User-provided note or preferences summary")
        private String preferencesNote;

        @Column(name = "update_source", length = 20)
        @Schema(description = "Source of the last update", example = "APP", allowableValues = { "APP", "API", "ADMIN",
                        "IMPORT" })
        private String updateSource;

        @Version
        @Column(name = "version", nullable = false)
        @Schema(description = "Optimistic locking version")
        private Long version;

        @CreatedDate
        @Column(name = "created_at", updatable = false, nullable = false)
        @Schema(description = "Timestamp when the preference was created")
        private Instant createdAt;

        @LastModifiedDate
        @Column(name = "updated_at", nullable = false)
        @Schema(description = "Timestamp when the preference was last updated")
        private Instant updatedAt;
}
