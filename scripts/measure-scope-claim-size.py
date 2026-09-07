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

The adopted claim (ADR-0061) is two claims:

  * ``loc_fin_bits`` / ``loc_oth_bits`` -- the permissions that are location
    scoped, split by which hierarchy dimension their granting role traverses:
    FINANCIAL (accounting and general-manager roles) or OTHER (every other
    role, traversing the six non-financial ParentType dimensions). Same bit
    indexes as perm_bits, same codec, same ``perm_ver``, so no catalog version
    bump. A permission absent from BOTH is global.
  * ``loc_scope`` -- discriminated: ``"ALL"``, or the location nodes the caller
    is assigned to. A node may be a shop, or a District/Region/HQ node, in which
    case it covers every descendant (evaluated at check time against the
    replicated ancestor set, not expanded into the token).

``location_scope`` is a property of the ROLE (ALL | LOCATION). Two claims rather
than one because a user may hold both a LOCATION-scoped and an ALL-scoped role,
and the ALL role's grants must not widen the LOCATION role's.

Hierarchy is the compression: a Region manager holds ONE node id, not the shops
beneath it, so assigned-node counts stay small and the claim stays small. The
location-bitset alternative is measured at the bottom and deliberately NOT
adopted -- see the note there.

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
    """ADR-0061 section 2: two dimension bitsets + the assigned nodes.

    Three cases per profile, all with a single assigned node (the expected shape
    once hierarchy carries the middle tier):
      unscoped   -- every role is ALL-scoped; no scope claims emitted
      realistic  -- near-disjoint dimensions: ~40% of permissions scoped along
                    OTHER, ~10% along FINANCIAL (accounting permissions)
      worst case -- every permission scoped along BOTH dimensions at once
    """
    print("=== Adopted: loc_fin_bits + loc_oth_bits + loc_scope ===\n")
    print(f"{'permission profile':<22}{'unscoped':>10}{'realistic':>13}"
          f"{'worst case':>12}{'worst delta':>13}")
    worst = 0
    for name, n in PERM_PROFILES:
        base = base_payload(n)
        unscoped = jws_size(base)

        # Realistic: the two dimensions are near-disjoint (accounting permissions
        # vs operational ones), so each bitset is sparse.
        half = dict(base)
        half["loc_oth_bits"] = perm_bits(max(1, int(n * 0.4)))
        half["loc_fin_bits"] = perm_bits(max(1, int(n * 0.1)))
        half["loc_scope"] = [str(uuid.uuid4())]
        s_half = jws_size(half)

        # Worst case: every permission scoped along BOTH dimensions.
        full = dict(base)
        full["loc_oth_bits"] = perm_bits(n)
        full["loc_fin_bits"] = perm_bits(n)
        full["loc_scope"] = [str(uuid.uuid4())]
        s_full = jws_size(full)

        worst = max(worst, s_full)
        print(f"{name:<22}{unscoped:>10}{s_half:>13}{s_full:>12}{s_full - unscoped:>13}")

    budget = MAX_HTTP_HEADER_SIZE - BEARER_OVERHEAD
    print(f"\nWorst case {worst} B against a {budget} B header budget "
          f"({worst / budget:.2%}).")
    print("Each bitset is bounded by the catalog (86 chars). loc_scope grows only with")
    print("the number of ASSIGNED NODES, which hierarchy keeps at 1-2 in the normal")
    print("case -- a Region manager holds the Region node, not its shops.\n")

    print("Cost of additional assigned nodes (ADMIN-like profile, all scoped):")
    b = base_payload(387)
    b["loc_oth_bits"] = perm_bits(387)
    b["loc_fin_bits"] = perm_bits(387)
    for nodes in (1, 2, 4, 8, 16):
        p = dict(b)
        p["loc_scope"] = [str(uuid.uuid4()) for _ in range(nodes)]
        print(f"  {nodes:>2} node(s): {jws_size(p):>5} B")
    print("\nA cap of ~8 assigned nodes is an assertion that the hierarchy was modelled")
    print("correctly, not a size limit -- 16 nodes still costs under 1.5 KB.")


def rejected():
    """Recorded rationale -- encodings considered and not adopted."""
    print("\n\n=== Not adopted: expanding the hierarchy into the token ===\n")
    print("If a Region assignment were expanded at issuance into its member shops")
    print("instead of evaluated at check time, the claim would have cost:\n")

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
    location_bitset()


def location_bitset():
    """Why locations are NOT encoded as a bitset the way permissions are."""
    print("\n\n=== Not adopted: location bitset (indexed like PermissionCode) ===\n")
    print("Assumes an append-only location index assigned by pos-location and")
    print("replicated to every service. Claim payload only, Base64URL chars.\n")
    print(f"{'deployment':<14}{'covers':>8}{'bitset':>10}{'id list':>10}{'winner':>11}")
    for total, cover in [(50, 1), (500, 1), (500, 3), (500, 25),
                         (10000, 1), (10000, 25), (10000, 2000)]:
        idxs = sorted({int(i * (total - 1) / max(1, cover)) for i in range(cover)} | {total - 1})
        bs = len(b64url(java_bitset_bytes(idxs)))
        il = len(b64url(b"".join(uuid.uuid4().bytes for _ in range(cover))))
        print(f"{total:<14}{cover:>8}{bs:>10}{il:>10}{('bitset' if bs < il else 'id list'):>11}")

    print("\nThe bitset costs maxIndex/6 chars REGARDLESS of how many locations are")
    print("covered, so it couples every user's token size to the total number of")
    print("locations on the platform: opening the 5,000th shop makes a single-shop")
    print("technician's token ~834 chars heavier. An id list costs ~22 chars per")
    print("node and scales with that user's actual assignment instead.")
    print("\nHierarchy already keeps assigned-node counts at 1-2, which is exactly the")
    print("region where the id list wins. The bitset would also need an append-only,")
    print("replicated location-index registry -- a permanent distributed invariant --")
    print("whereas PermissionCode gets away with a bitset because it is a compile-time")
    print("enum with CATALOG_VERSION lockstep. Deferred behind the loc_scope")
    print("discriminator: adoptable later without a version bump if cardinality ever")
    print("justifies it.")


def main():
    print(f"catalog {CATALOG_SIZE} codes; full bitset = {len(perm_bits(CATALOG_SIZE))} "
          f"Base64URL chars; sizes in bytes (all ASCII)\n")
    adopted()
    rejected()


if __name__ == "__main__":
    main()
