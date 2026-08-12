package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Vendor auth config (ADR-0050 §4). All {@code *Ref} columns are secret
 * <em>references</em> ({@code scheme:key}, e.g. {@code env:MICHELIN_EDI_PASSWORD}) resolved
 * at call time by {@code SecretReferenceResolver} — plaintext credentials never persist,
 * never serialize into API responses ({@code AuthConfigView} has no reference fields), and
 * never appear in logs. {@code apiKeyHeader} is the plain header <em>name</em> the API key is
 * sent under — configuration data, not a secret.
 *
 * <p>{@code name} is unique per profile; endpoint bindings reference it as their
 * {@code authConfigName}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "supplier_auth_config",
        indexes = {@Index(name = "idx_sauth_profile", columnList = "vendor_profile_id")},
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "supplier_auth_config_profile_name_key",
                    columnNames = {"vendor_profile_id", "name"})
        })
@EntityListeners(AuditingEntityListener.class)
public class SupplierAuthConfigEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Owning {@code supplier_profile.vendor_profile_id} (DB FK {@code fk_sauth_profile}). */
    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64)
    private SupplierAuthType type;

    /** Secret reference of the Basic username; excluded from toString by secret hygiene. */
    @ToString.Exclude
    @Column(name = "username_ref", length = 512)
    private String usernameRef;

    /** Secret reference of the Basic password; excluded from toString by secret hygiene. */
    @ToString.Exclude
    @Column(name = "password_ref", length = 512)
    private String passwordRef;

    /** Secret reference of the API key; excluded from toString by secret hygiene. */
    @ToString.Exclude
    @Column(name = "api_key_ref", length = 512)
    private String apiKeyRef;

    /** Plain header name the API key is sent under (e.g. {@code apikey}) — not a secret. */
    @Column(name = "api_key_header", length = 100)
    private String apiKeyHeader;

    /** Secret reference of the OAuth2 token endpoint URL. */
    @ToString.Exclude
    @Column(name = "token_url_ref", length = 512)
    private String tokenUrlRef;

    /** Secret reference of the OAuth2 client id. */
    @ToString.Exclude
    @Column(name = "client_id_ref", length = 512)
    private String clientIdRef;

    /** Secret reference of the OAuth2 client secret. */
    @ToString.Exclude
    @Column(name = "client_secret_ref", length = 512)
    private String clientSecretRef;

    /** Secret reference of the static bearer token. */
    @ToString.Exclude
    @Column(name = "bearer_token_ref", length = 512)
    private String bearerTokenRef;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
