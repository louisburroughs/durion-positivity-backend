package com.pos.agent.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Minimal JWT-like utility for encoding/decoding SecurityContext data.
 * Uses HMAC-SHA256 for integrity and Base64 URL encoding without padding.
 */
public final class JwtTokenUtil {
    private static final String SECRET_ENV = "AGENT_JWT_SECRET";
    private static final String SECRET_PROPERTY = "agent.jwt.secret";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private JwtTokenUtil() {
    }

    public static String encode(SecurityPayload payload) {
        Objects.requireNonNull(payload, "payload is required");
        String secret = resolveSecret();

        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadData = buildPayloadData(payload);

        String headerEncoded = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadEncoded = base64Url(payloadData.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerEncoded + "." + payloadEncoded;
        String signature = sign(signingInput, secret);

        return signingInput + "." + signature;
    }

    public static SecurityPayload decode(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token cannot be null or blank");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid token format");
        }

        String secret = resolveSecret();
        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput, secret);

        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid token signature");
        }

        String payloadData = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return parsePayloadData(payloadData);
    }

    private static String resolveSecret() {
        String secret = System.getenv(SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            secret = System.getProperty(SECRET_PROPERTY);
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Signing secret is required. Set AGENT_JWT_SECRET or system property '" + SECRET_PROPERTY + "'.");
        }
        return secret;
    }

    private static String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return base64Url(signatureBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign token", e);
        }
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String buildPayloadData(SecurityPayload payload) {
        return "userId=" + urlEncode(payload.userId()) +
                "&roles=" + urlEncode(String.join(",", payload.roles())) +
                "&permissions=" + urlEncode(String.join(",", payload.permissions())) +
                "&serviceId=" + urlEncode(payload.serviceId()) +
                "&serviceType=" + urlEncode(payload.serviceType());
    }

    private static SecurityPayload parsePayloadData(String payloadData) {
        String[] pairs = payloadData.split("&");
        String userId = null;
        String rolesRaw = null;
        String permissionsRaw = null;
        String serviceId = null;
        String serviceType = null;

        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0];
            String value = urlDecode(kv[1]);
            switch (key) {
                case "userId" -> userId = emptyToNull(value);
                case "roles" -> rolesRaw = value;
                case "permissions" -> permissionsRaw = value;
                case "serviceId" -> serviceId = emptyToNull(value);
                case "serviceType" -> serviceType = emptyToNull(value);
                default -> {
                }
            }
        }

        List<String> roles = splitToList(rolesRaw);
        List<String> permissions = splitToList(permissionsRaw);

        return new SecurityPayload(userId, roles, permissions, serviceId, serviceType);
    }

    private static List<String> splitToList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = raw.split(",");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            String cleaned = part.trim();
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
        return values;
    }

    private static String urlEncode(String value) {
        String safe = value == null ? "" : value;
        return URLEncoder.encode(safe, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record SecurityPayload(String userId,
            List<String> roles,
            List<String> permissions,
            String serviceId,
            String serviceType) {
        public SecurityPayload {
            roles = roles == null ? Collections.emptyList()
                    : roles.stream().filter(Objects::nonNull).collect(Collectors.toList());
            permissions = permissions == null ? Collections.emptyList()
                    : permissions.stream().filter(Objects::nonNull).collect(Collectors.toList());
        }
    }
}