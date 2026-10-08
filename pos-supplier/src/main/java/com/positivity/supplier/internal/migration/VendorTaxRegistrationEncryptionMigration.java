package com.positivity.supplier.internal.migration;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.entity.VendorTaxIdCipher;
import com.positivity.supplier.internal.entity.VendorTaxRegistration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Flyway {@code V4}: encrypts every vendor tax-registration number stored in clear (#2621; Security ruling on
 * #2617, ruling 3). After it, no {@code supplier_vendor.tax_registrations} element has a {@code number} key:
 * each is {@code {registrationId, scheme, region, last4, numberCiphertext}}.
 *
 * <h2>Why Java, and why a bean</h2>
 *
 * A SQL migration cannot hold the key. Spring Boot hands every {@link JavaMigration} bean to Flyway, so this
 * one is a {@link Component} with {@link VendorTaxIdCipher} injected: the same key, the same envelope and the
 * same AAD binding (tenant, vendor, registration) the application uses. It implements {@link JavaMigration}
 * directly rather than extending {@code BaseJavaMigration}, which would take the version from a class name
 * that Java naming rules forbid.
 *
 * <h2>Behaviour</h2>
 *
 * <ul>
 *   <li><strong>Idempotent per element.</strong> An element with {@code numberCiphertext} and no {@code number}
 *       is left alone; one with a {@code number} is sealed (keeping its {@code registrationId} when it has one,
 *       minting a UUIDv7 otherwise), given its {@code last4}, and loses {@code number}. A second run finds
 *       nothing to do.
 *   <li><strong>Every tenant.</strong> Like {@code V3}'s backfill it runs as the table owner and lifts
 *       {@code FORCE ROW LEVEL SECURITY} for its own statements, restoring it before it returns, inside
 *       Flyway's transaction; each element is sealed under its own row's {@code tenant_id}.
 *   <li><strong>Counts only.</strong> It logs how many vendors and registrations it touched, never a value.
 *       An element it cannot interpret fails the migration with its position, not its content.
 *   <li>The vendor {@code version} is not advanced and no fact is queued: this is storage, not a change.
 * </ul>
 */
@Component
public class VendorTaxRegistrationEncryptionMigration implements JavaMigration {

    private static final Logger log = LoggerFactory.getLogger(VendorTaxRegistrationEncryptionMigration.class);

    static final String VERSION = "4";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final VendorTaxIdCipher cipher;

    public VendorTaxRegistrationEncryptionMigration(@NonNull VendorTaxIdCipher cipher) {
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
    }

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion(VERSION);
    }

    @Override
    public String getDescription() {
        return "encrypt vendor tax registrations";
    }

    /** {@code null}: there is no script to checksum, and the migration is idempotent if it ever ran again. */
    @Override
    public @Nullable Integer getChecksum() {
        return null;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(@NonNull Context context) throws SQLException {
        Connection connection = context.getConnection();
        int vendors = 0;
        int sealed = 0;
        int alreadySealed = 0;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.supplier_vendor NO FORCE ROW LEVEL SECURITY");
        }
        List<Object[]> updates = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT tenant_id, vendor_id, tax_registrations::text"
                        + " FROM public.supplier_vendor WHERE jsonb_array_length(tax_registrations) > 0")) {
            while (rows.next()) {
                UUID tenantId = rows.getObject("tenant_id", UUID.class);
                UUID vendorId = rows.getObject("vendor_id", UUID.class);
                JsonNode registrations = JSON.readTree(rows.getString(3));
                ArrayNode rewritten = JSON.createArrayNode();
                boolean changed = false;
                int position = 0;
                for (JsonNode element : registrations) {
                    if (!element.has("number") && element.hasNonNull("numberCiphertext")) {
                        rewritten.add(element);
                        alreadySealed++;
                    } else if (element.hasNonNull("number") && element.hasNonNull("scheme")) {
                        rewritten.add(sealElement(tenantId, vendorId, element));
                        sealed++;
                        changed = true;
                    } else {
                        // Position only: never the element, which may hold a number.
                        throw new IllegalStateException("V4: vendor " + vendorId + " tax registration " + position
                                + " has neither a number nor a sealed number; refusing to guess");
                    }
                    position++;
                }
                if (changed) {
                    updates.add(new Object[] {JSON.writeValueAsString(rewritten), tenantId, vendorId});
                    vendors++;
                }
            }
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE public.supplier_vendor"
                + " SET tax_registrations = ?::jsonb WHERE tenant_id = ? AND vendor_id = ?")) {
            for (Object[] row : updates) {
                update.setString(1, (String) row[0]);
                update.setObject(2, row[1]);
                update.setObject(3, row[2]);
                update.addBatch();
            }
            update.executeBatch();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public.supplier_vendor FORCE ROW LEVEL SECURITY");
        }
        log.info(
                "V4 vendor tax registrations: sealed {} on {} vendors; {} were already sealed",
                sealed,
                vendors,
                alreadySealed);
    }

    private ObjectNode sealElement(UUID tenantId, UUID vendorId, JsonNode element) {
        String number = element.get("number").asString();
        UUID registrationId = element.hasNonNull("registrationId")
                ? UUID.fromString(element.get("registrationId").asString())
                : UUIDv7Generator.generate();
        ObjectNode sealedElement = JSON.createObjectNode();
        sealedElement.put("registrationId", registrationId.toString());
        sealedElement.put("scheme", element.get("scheme").asString());
        JsonNode region = element.get("region");
        if (region == null || region.isNull()) {
            sealedElement.putNull("region");
        } else {
            sealedElement.put("region", region.asString());
        }
        String last4 = VendorTaxRegistration.last4Of(number);
        if (last4 == null) {
            sealedElement.putNull("last4");
        } else {
            sealedElement.put("last4", last4);
        }
        sealedElement.put("numberCiphertext", cipher.seal(tenantId, vendorId, registrationId, number));
        return sealedElement;
    }
}
