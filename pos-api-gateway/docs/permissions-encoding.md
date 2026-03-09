# Compact Permission Bitset Authorization Design

**Target Platform:** Java 21, Spring Boot 4.0.3
**Architecture Context:** JWT-based stateless authorization with API gateway enforcement

---

## 1. Overview

This document describes a compact and efficient authorization model using a **permission bitset encoded inside a JWT claim**.

The design is intended for systems with:

* Large permission catalogs (≈200 permissions)
* Role-based permission grouping
* Per-user permission overrides
* High API traffic where **JWT size matters**
* Stateless authorization at the API gateway

Instead of embedding permission names in JWT tokens, the system encodes a **bitset representing permissions**. Each bit position corresponds to a specific permission. The resulting binary data is encoded as a **Base64URL string** and placed into the token.

This design provides:

| Benefit                   | Description                             |
| ------------------------- | --------------------------------------- |
| Compact tokens            | ~25 bytes for 200 permissions           |
| Fast evaluation           | O(1) bit checks                         |
| Stateless validation      | No DB lookup required                   |
| Flexible permission model | Supports roles and user-specific grants |

---

## 2. High-Level Architecture

```plain text
                +------------------------+
                | Authorization Server   |
                | (Spring Authorization) |
                +-----------+------------+
                            |
                            | JWT Access Token
                            |
                            v
+-------------------+     +----------------------+
| API Gateway       |---->| Resource Services    |
|                   |     |                      |
| - Validate JWT    |     | @PreAuthorize rules  |
| - Decode permset  |     | using authorities    |
| - Convert to      |     |                      |
|   authorities     |     +----------------------+
+-------------------+
```

Authorization logic is evaluated in the gateway or resource server using the decoded permission mask.

---

## 3. Permission Model

The system supports three authorization sources.

### Roles

Roles are collections of permissions.

Example:

| Role           | Permissions                                           |
| -------------- | ----------------------------------------------------- |
| SERVICE_WRITER | workorder.create, inventory.lookup                    |
| SHOP_MANAGER   | workorder.create, inventory.lookup, inventory.receive |
| ADMIN          | all permissions                                       |

### Direct User Permissions

Users may have additional permissions beyond their roles.

Example:

```plain text
User: alice

Roles:
  SERVICE_WRITER

Additional permissions:
  inventory.receive
```

### Effective Permissions

At token generation time:

```plain text
EffectivePermissions =
  role_permissions
  UNION
  direct_user_permissions
```

---

## 4. Permission Catalog

Permissions are defined in a **versioned catalog**. Each permission receives a permanent bit index.

## Example Permission Enum

```java
public enum PermissionCode {

    USER_READ(0, "user.read"),
    USER_WRITE(1, "user.write"),

    INVENTORY_LOOKUP(2, "inventory.lookup"),
    INVENTORY_RECEIVE_PO(3, "inventory.receive.po"),
    INVENTORY_RECEIVE_ASN(4, "inventory.receive.asn"),

    WORKORDER_CREATE(5, "workorder.create"),
    WORKORDER_CLOSE(6, "workorder.close");

    private final int bitIndex;
    private final String code;

    PermissionCode(int bitIndex, String code) {
        this.bitIndex = bitIndex;
        this.code = code;
    }

    public int bitIndex() {
        return bitIndex;
    }

    public String code() {
        return code;
    }
}
```

### Catalog Rules

1. **Never reuse bit indexes**
2. **Only append new permissions**
3. Retired permissions remain unused but reserved
4. Each token carries a **catalog version**

Example:

```plain text
perm_ver = 3
```

---

## 5. JWT Structure

Example JWT payload:

```json
{
  "iss": "durion-auth",
  "sub": "user123",
  "uid": "user123",
  "perm_bits": "AQIDBAUG",
  "perm_ver": 3,
  "iat": 1711000000,
  "exp": 1711003600
}
```

### Claims

| Claim       | Purpose                             |
| ----------- | ----------------------------------- |
| `sub`       | Subject identifier                  |
| `uid`       | User ID                             |
| `perm_bits` | Base64URL encoded permission bitset |
| `perm_ver`  | Permission catalog version          |
| `iat`       | Issued at                           |
| `exp`       | Token expiration                    |

---

## 6. Permission Bitset Encoding

Permissions are represented as a **Java BitSet**.

For ~200 permissions:

```plain text
200 bits = 25 bytes
```

These bytes are encoded using Base64URL for safe transport in JWT.

---

## 7. Bitset Codec Implementation

```java
import java.util.Base64;
import java.util.BitSet;
import java.util.Set;

public class PermissionBitsetCodec {

    public static String encode(Set<PermissionCode> permissions) {

        BitSet bits = new BitSet();

        for (PermissionCode p : permissions) {
            bits.set(p.bitIndex());
        }

        byte[] raw = bits.toByteArray();

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw);
    }

    public static BitSet decode(String encoded) {

        byte[] raw = Base64.getUrlDecoder()
                .decode(encoded);

        return BitSet.valueOf(raw);
    }

    public static boolean hasPermission(
            String encoded,
            PermissionCode permission) {

        BitSet bits = decode(encoded);

        return bits.get(permission.bitIndex());
    }
}
```

---

## 8. Computing Effective Permissions

During authentication:

```java
Set<PermissionCode> resolveEffectivePermissions(String userId) {

    Set<PermissionCode> permissions = new HashSet<>();

    permissions.addAll(rolePermissions(userId));
    permissions.addAll(userDirectPermissions(userId));

    return permissions;
}
```

Example:

```plain text
Role permissions:
  workorder.create
  inventory.lookup

Direct permissions:
  inventory.receive

Effective permissions:
  workorder.create
  inventory.lookup
  inventory.receive
```

---

## 9. Adding Permissions to the JWT

Spring Authorization Server allows customizing JWT claims.

## Token Customizer

```java
@Bean
OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(
        PermissionService permissionService) {

    return context -> {

        if ("access_token".equals(context.getTokenType().getValue())) {

            String userId = context.getPrincipal().getName();

            Set<PermissionCode> permissions =
                    permissionService.resolveEffectivePermissions(userId);

            String permBits =
                    PermissionBitsetCodec.encode(permissions);

            context.getClaims().claim("uid", userId);
            context.getClaims().claim("perm_bits", permBits);
            context.getClaims().claim("perm_ver", 3);
        }
    };
}
```

---

## 10. Gateway JWT Conversion

The gateway converts permission bits into Spring Security authorities.

## JWT Authentication Converter

```java
@Component
public class PermissionJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {

        String permBits = jwt.getClaimAsString("perm_bits");
        Integer permVer = jwt.getClaim("perm_ver");

        if (permVer == null || permVer != 3) {
            throw new BadCredentialsException(
                    "Unsupported permission catalog version");
        }

        BitSet bits = PermissionBitsetCodec.decode(permBits);

        List<GrantedAuthority> authorities =
                Arrays.stream(PermissionCode.values())
                        .filter(p -> bits.get(p.bitIndex()))
                        .map(p -> new SimpleGrantedAuthority(
                                "PERM_" + p.code()))
                        .toList();

        return new JwtAuthenticationToken(
                jwt,
                authorities,
                jwt.getSubject());
    }
}
```

---

## 11. Spring Security Configuration

```java
@Bean
SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        PermissionJwtAuthenticationConverter converter)
        throws Exception {

    http
        .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health")
                .permitAll()
                .anyRequest()
                .authenticated())
        .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt
                        .jwtAuthenticationConverter(converter)));

    return http.build();
}
```

---

## 12. Service Authorization

Services can now use readable authorization rules.

### Example Controller

```java
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    @PreAuthorize("hasAuthority('PERM_inventory.receive.asn')")
    @PostMapping("/receive-asn")
    public ResponseEntity<?> receiveASN() {

        return ResponseEntity.ok().build();
    }
}
```

The permission check is performed using the decoded JWT authorities.

---

## 13. Token Lifecycle

Recommended configuration:

| Setting                | Value                    |
| ---------------------- | ------------------------ |
| Access token lifetime  | 5–15 minutes             |
| Refresh token lifetime | Several hours or days    |
| Revocation strategy    | Refresh tokens or logout |

Permission changes take effect when a new access token is issued.

---

## 14. Operational Considerations

## Permission Catalog Stability

Bit indexes must remain stable forever.

Example evolution:

```plain text
0 user.read
1 user.write
2 inventory.lookup
3 inventory.receive.po
4 inventory.receive.asn
5 workorder.create
6 workorder.close
7 (retired)
8 invoice.create
```

Retired bits are **never reused**.

---

## Debugging Tokens

A diagnostic endpoint or CLI tool should expand permission bits.

Example:

```plain text
Token perm_bits: AQIDBA

Decoded permissions:

inventory.lookup
inventory.receive.asn
workorder.create
```

---

## Testing Strategy

Tests should verify:

* each permission maps to the correct bit
* encoding and decoding symmetry
* gateway authority conversion
* catalog version enforcement

Example test:

```java
@Test
void permissionEncodingRoundTrip() {

    Set<PermissionCode> perms = Set.of(
            PermissionCode.WORKORDER_CREATE,
            PermissionCode.INVENTORY_LOOKUP
    );

    String encoded = PermissionBitsetCodec.encode(perms);

    assertTrue(PermissionBitsetCodec.hasPermission(
            encoded,
            PermissionCode.WORKORDER_CREATE));

    assertTrue(PermissionBitsetCodec.hasPermission(
            encoded,
            PermissionCode.INVENTORY_LOOKUP));
}
```

---

## 15. Example End-to-End Flow

### Login

```plain text
User authenticates
```

Authorization server computes permissions.

```plain text
Role permissions:
  inventory.lookup
  workorder.create

User grants:
  inventory.receive.asn
```

Bitset created:

```plain text
bits:
  2 = 1
  4 = 1
  5 = 1
```

JWT issued.

---

### Gateway

```plain text
JWT received
```

Gateway:

1. validates signature
2. reads `perm_bits`
3. decodes bitset
4. converts to authorities

Authorities created:

```plain text
PERM_inventory.lookup
PERM_inventory.receive.asn
PERM_workorder.create
```

---

### API Authorization

Controller rule:

```java
@PreAuthorize("hasAuthority('PERM_inventory.receive.asn')")
```

Access granted.

---

## 16. Summary

The permission bitset JWT model provides:

* Compact tokens
* Fast authorization
* Stateless gateway enforcement
* Compatibility with Spring Security

Key rules for success:

1. Use **BitSet + Base64URL encoding**
2. Maintain a **versioned permission catalog**
3. **Never reuse bit indexes**
4. Keep **access tokens short-lived**
5. Convert bitsets to **Spring authorities at the gateway**
