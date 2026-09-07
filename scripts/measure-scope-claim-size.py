#!/usr/bin/env python3
"""Measure JWT access-token size for the location-scope claim.

Written for the #1375 spike (location scope / effective dating in the JWT).
Replicates the production encoding exactly so the numbers are comparable to
what pos-security-service actually issues:

  * ``perm_bits`` is ``java.util.BitSet.toByteArray()`` (little-endian bit order
    within each byte, trailing zero bytes trimmed) then Base64URL without
    padding -- see PermissionBitsetCodec.encode.
  * The token is a JWS compact serialisation: base64url(header) "."
    base64url(payload) "." base64url(signature).
  * Baseline claims mirror JwtServiceImpl.generateTokenPair: iss, aud, sub, jti,
    iat, exp, uid, username, roles, perm_bits, perm_ver, personId.

The adopted claim (ADR-0061) is two claims, both constant-size:

  * ``loc_bits`` -- the subset of ``perm_bits`` granted only by LOCATION-scoped
    roles. Same bit indexes, same codec, same ``perm_ver``, so no catalog
    version bump.
  * ``loc_scope`` -- the single location UUID the caller occupies, from its
    primary employee_location_assignment in pos-people. Omitted when
    ``loc_bits`` is empty.

``location_scope`` is a property of the ROLE (ALL | LOCATION). A location-scoped
employee occupies exactly one location; reach is never an enumerated set. Two
claims rather than one because a user may hold both a LOCATION-scoped and an
ALL-scoped role, and the ALL role's grants must not widen the LOCATION role's.

The set-valued encodings are retained below purely as recorded rationale for
why reach is single-valued. They are not implemented.

Run:  python3 scripts/measure-scope-claim-size.py
"""

import base64
import json
import uuid

# --- production constants (keep in step with the Java) ------------------------
CATALOG_SIZE = 510  # PermissionCode values
HS256_SIG_BYTES = 32
# Tomcat cap configured across the services' application.yml
MAX_HTTP_HEADER_SIZE = 65536
# "Authorization: Bearer " prefix counts against the header budget
BEARER_OVERHEAD = len("Authorization: Bearer ")


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def java_bitset_bytes(indexes) -> bytes:
    """Reproduce java.util.BitSet.toByteArray() for the given set bit indexes."""
    indexes = list(indexes)
    if not indexes:
        return b""
    size = max(indexes) // 8 + 1
    out = bytearray(size)
    for i in indexes:
        out[i // 8] |= 1 << (i % 8)
    return bytes(out)


def perm_bits(count: int) -> str:
    """Encode `count` permissions. Worst case for size is a high bit index, so
    spread the grants across the whole catalog rather than packing them low."""
    if count >= CATALOG_SIZE:
        idx = range(CATALOG_SIZE)
    else:
        step = CATALOG_SIZE / count
        idx = sorted({min(CATALOG_SIZE - 1, int(i * step)) for i in range(count)})
    return b64url(java_bitset_bytes(idx))


def jws_size(payload: dict) -> int:
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    body = b64url(json.dumps(payload, separators=(",", ":")).encode())
    sig = b64url(b"\x00" * HS256_SIG_BYTES)
    return len(header) + 1 + len(body) + 1 + len(sig)


def base_payload(nperms: int) -> dict:
    return {
        "iss": "pos-security-service",
        "aud": "durion-positivity",
        "sub": "felicia.grant",
        "jti": str(uuid.uuid4()),
        "iat": 1788000000,
        "exp": 1788003600,
        "uid": str(uuid.uuid4()),
        "username": "felicia.grant",
        "roles": ["ROLE_INVENTORY_MANAGER"],
        "perm_bits": perm_bits(nperms),
        "perm_ver": 76,
        "personId": str(uuid.uuid4()),
    }


PERM_PROFILES = [
    ("DISPATCHER-like", 11),
    ("SHOP_MANAGER-like", 17),
    ("CONTROLLER-like", 52),
    ("ADMIN-like", 387),
    ("whole catalog", CATALOG_SIZE),
]


def adopted():
    """ADR-0061 section 2: loc_bits (scoped permission subset) + loc_scope (one uuid).

    Three cases per profile:
      unscoped   -- every role is ALL-scoped; neither claim is emitted
      half       -- a realistic mix: half the permissions come from LOCATION roles
      all scoped -- every permission is location-limited (worst case for loc_bits)
    """
    print("=== Adopted encoding: loc_bits + loc_scope ===\n")
    print(f"{'permission profile':<22}{'unscoped':>10}{'half scoped':>13}"
          f"{'all scoped':>12}{'worst delta':>13}")
    worst = 0
    for name, n in PERM_PROFILES:
        base = base_payload(n)
        unscoped = jws_size(base)

        half = dict(base)
        half["loc_bits"] = perm_bits(max(1, n // 2))
        half["loc_scope"] = str(uuid.uuid4())
        s_half = jws_size(half)

        full = dict(base)
        full["loc_bits"] = perm_bits(n)
        full["loc_scope"] = str(uuid.uuid4())
        s_full = jws_size(full)

        worst = max(worst, s_full)
        print(f"{name:<22}{unscoped:>10}{s_half:>13}{s_full:>12}{s_full - unscoped:>13}")

    budget = MAX_HTTP_HEADER_SIZE - BEARER_OVERHEAD
    print(f"\nWorst case {worst} B against a {budget} B header budget "
          f"({worst / budget:.2%}).")
    print("Both claims are constant-size: loc_bits is bounded by the catalog (86 chars)")
    print("and loc_scope is one uuid, so token size never varies with how many")
    print("locations exist. No cardinality cap, no size-driven fallback.")


def rejected():
    """Recorded rationale only -- what a set-valued claim would have cost."""
    print("\n\n=== Rejected: set-valued reach (NOT implemented) ===\n")
    print("Retained as the record of why reach is single-valued. If an employee")
    print("could be scoped to N locations, the claim would have cost:\n")

    counts = [1, 5, 25, 100, 500]
    base = base_payload(387)  # ADMIN-like

    def uuid_strings(n):
        p = dict(base)
        p["loc_scope"] = [str(uuid.uuid4()) for _ in range(n)]
        return p

    def packed(n):
        p = dict(base)
        p["loc_scope"] = b64url(b"".join(uuid.uuid4().bytes for _ in range(n)))
        return p

    def per_location_bitsets(n):
        p = dict(base)
        per = perm_bits(387)
        p["loc_perm_bits"] = {str(uuid.uuid4()): per for _ in range(n)}
        return p

    rows = [
        ("UUID string list", uuid_strings),
        ("packed Base64URL set", packed),
        ("per-location bitset map", per_location_bitsets),
    ]
    print("  encoding".ljust(30) + "".join(f"{n:>9}" for n in counts) + "   locations")
    for label, fn in rows:
        line = f"  {label}".ljust(30)
        for n in counts:
            size = jws_size(fn(n))
            line += f"{size:>8}{'!' if size + BEARER_OVERHEAD > MAX_HTTP_HEADER_SIZE else ' '}"
        print(line)
    print("\n'!' exceeds max-http-header-size. The per-location bitset map is also the")
    print("only shape that would have forced PermissionCode / GatewayPermissionCatalog /")
    print("PermissionBitsetCodec / CATALOG_VERSION lockstep across 1,086 @PreAuthorize sites.")


def main():
    print(f"catalog {CATALOG_SIZE} codes; full bitset = {len(perm_bits(CATALOG_SIZE))} "
          f"Base64URL chars; sizes in bytes (all ASCII)\n")
    adopted()
    rejected()


if __name__ == "__main__":
    main()
