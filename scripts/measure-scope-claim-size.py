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
# Tomcat's server.tomcat.max-http-header-size, configured across the services'
# application.yml. NOTE: this caps the request line PLUS ALL headers together --
# it is not a budget reserved for the token. Cookies, tracing and correlation
# headers all draw on the same 64 KB, so the percentages below are a floor on
# the token's share, not the headroom actually available to it.
MAX_HTTP_HEADER_SIZE = 65536
# "Authorization: Bearer " prefix, counted with the token
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
    """Encode `count` permissions, sized conservatively.

    BitSet.toByteArray() length is driven by the HIGHEST set bit, not by the
    number of bits set, so the worst case for token size is a grant at the top
    of the catalog. The top index is therefore always included and the rest are
    spread beneath it; otherwise low counts top out well short of the catalog
    end and the measured sizes come out optimistic.
    """
    if count >= CATALOG_SIZE:
        idx = set(range(CATALOG_SIZE))
    else:
        step = CATALOG_SIZE / count
        idx = {min(CATALOG_SIZE - 1, int(i * step)) for i in range(count)}
        idx.add(CATALOG_SIZE - 1)
    return b64url(java_bitset_bytes(sorted(idx)))


def jws_size(payload: dict) -> int:
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    body = b64url(json.dumps(payload, separators=(",", ":")).encode())
    sig = b64url(b"\x00" * HS256_SIG_BYTES)
    return len(header) + 1 + len(body) + 1 + len(sig)


def base_payload(nperms: int) -> dict:
    return {
        "iss": "pos-security-service",
        # JwtServiceImpl builds this with JJWT's .audience().add(AUDIENCE),
        # which serialises as an array; AUDIENCE = "api-gateway"
        # (JwtServiceImplTest asserts containsExactly("api-gateway")).
        "aud": ["api-gateway"],
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

    Measured conservatively: perm_bits() always sets the top catalog bit, so the
    bitset is full-length (64 bytes) whichever role is modelled. That makes the
    permission profile drop out of the result entirely -- an important finding in
    its own right, and the reason this reports one figure rather than a per-role
    table. See PERM_PROFILES below for the spread that is deliberately NOT shown.
    """
    print("=== Adopted: loc_fin_bits + loc_oth_bits + loc_scope ===\n")

    base = base_payload(387)
    unscoped = jws_size(base)

    scoped = dict(base)
    scoped["loc_oth_bits"] = perm_bits(387)
    scoped["loc_fin_bits"] = perm_bits(387)
    scoped["loc_scope"] = [str(uuid.uuid4())]
    s_scoped = jws_size(scoped)

    limit = MAX_HTTP_HEADER_SIZE - BEARER_OVERHEAD
    print(f"  no scope claims (today)      {unscoped:>6} B")
    print(f"  + loc_fin_bits/loc_oth_bits/loc_scope, one assigned node"
          f"   {s_scoped:>6} B   (+{s_scoped - unscoped})")
    print(f"\n  {s_scoped / limit:.2%} of the {MAX_HTTP_HEADER_SIZE} B max-http-header-size limit")
    print("  (which caps the request line and ALL headers together, not the token")
    print("   alone -- cookies and tracing headers draw on the same allowance, so")
    print("   treat this as the token's share, not the headroom available to it).")

    print("\nWhy there is no per-role breakdown: BitSet.toByteArray() is sized by the")
    print("HIGHEST set bit, not the number set, so any role holding a grant near the")
    print("end of a 510-code catalog carries a full 64-byte bitset. Measured")
    print("conservatively, a DISPATCHER-like role costs the same as ADMIN.")
    for name, n in PERM_PROFILES:
        p2 = dict(base)
        p2["perm_bits"] = perm_bits(n)
        p2["loc_oth_bits"] = perm_bits(n)
        p2["loc_fin_bits"] = perm_bits(n)
        p2["loc_scope"] = [str(uuid.uuid4())]
        print(f"    {name:<20} ({n:>3} permissions): {jws_size(p2):>5} B")

    print("\nCost of additional assigned nodes:")
    for nodes in (1, 2, 4, 8, 16):
        p3 = dict(scoped)
        p3["loc_scope"] = [str(uuid.uuid4()) for _ in range(nodes)]
        print(f"  {nodes:>2} node(s): {jws_size(p3):>5} B")
    print("\nA cap of ~8 assigned nodes is an assertion that the hierarchy was modelled")
    print("correctly, not a size limit -- 16 nodes still costs well under 2 KB.")


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
