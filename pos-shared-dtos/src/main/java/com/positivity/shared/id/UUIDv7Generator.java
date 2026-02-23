package com.positivity.shared.id;

import java.util.UUID;

import org.jspecify.annotations.NonNull;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * UUID v7 generator utility for platform entities.
 * <p>
 * Implements ADR-0013: UUID v7 Identifier Strategy for Platform Entities.
 * <p>
 * UUID v7 provides time-ordered UUIDs with the following benefits:
 * <ul>
 * <li>Maintains database index performance (temporal locality)</li>
 * <li>Non-enumerable (security)</li>
 * <li>Globally unique without coordination</li>
 * <li>RFC 9562 standard compliant</li>
 * </ul>
 *
 * <p>
 * <strong>Usage in JPA entities:</strong>
 * </p>
 * 
 * <pre>
 * {
 *     &#64;code
 *     &#64;Entity
 *     public class MyEntity {
 *         &#64;Id &#64;UUIDv7Id
 *         private UUID id;
 *     }
 * }
 * </pre>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9562.html">RFC 9562 - UUID
 *      Specification</a>
 * @see <a href="https://github.com/f4b6a3/uuid-creator">UUID Creator
 *      Library</a>
 */
public final class UUIDv7Generator {

    private UUIDv7Generator() {
        // Utility class - prevent instantiation
    }

    /**
     * Generates a new time-ordered UUID v7.
     * <p>
     * UUID v7 format: tttttttt-tttt-7xxx-yxxx-xxxxxxxxxxxx
     * <ul>
     * <li>t: Unix timestamp (milliseconds)</li>
     * <li>7: Version identifier (v7)</li>
     * <li>x, y: Random bits</li>
     * </ul>
     *
     * @return a new UUID v7 instance
     */

    public static @NonNull UUID generate() {
        UUID uuid = UuidCreator.getTimeOrderedEpoch();
        if (uuid == null) {
            throw new IllegalStateException("UuidCreator.getTimeOrderedEpoch() returned null");
        }
        return uuid;
    }

    /**
     * Checks if a UUID is version 7.
     *
     * @param uuid the UUID to checkO
     * @return true if the UUID is version 7, false otherwise
     */
    public static boolean isUUIDv7(@NonNull UUID uuid) {
        return uuid.version() == 7;
    }

    /**
     * Validates that a UUID string represents a valid UUID v7.
     *
     * @param uuidString the UUID string to validate
     * @return true if valid UUID v7, false otherwise
     */
    public static boolean isValidUUIDv7String(@NonNull String uuidString) {
        try {
            UUID uuid = UUID.fromString(uuidString);
            if (uuid == null) {
                throw new IllegalStateException("UuidCreator.getTimeOrderedEpoch() returned null");
            }
            return isUUIDv7(uuid);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
