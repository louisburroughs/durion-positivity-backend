#!/usr/bin/env python3
"""Measure JWT access-token size for candidate location-scope claim encodings.

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

Run:  python3 scripts/measure-scope-claim-size.py
"""

import base64
import itertools
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


def locs(n: int):
    return [str(uuid.uuid4()) for _ in range(n)]


# --- candidate encodings ------------------------------------------------------
def enc_a_status_quo(nperms, nlocs):
    """A -- no scope in the token (today)."""
    return base_payload(nperms)


def enc_b_scope_claim_uuid_strings(nperms, nlocs):
    """B -- one flat scope claim, canonical 36-char UUID strings."""
    p = base_payload(nperms)
    p["loc_scope"] = "GLOBAL" if nlocs == 0 else locs(nlocs)
    return p


def enc_c_scope_claim_packed(nperms, nlocs):
    """C -- one flat scope claim, UUIDs packed as 16 raw bytes, base64url'd
    together into a single string (22 chars per id, no separators)."""
    p = base_payload(nperms)
    if nlocs == 0:
        p["loc_scope"] = "GLOBAL"
    else:
        raw = b"".join(uuid.uuid4().bytes for _ in range(nlocs))
        p["loc_scope"] = b64url(raw)
    return p


def enc_d_per_location_bitsets(nperms, nlocs):
    """D -- per-location permission bitset map: the fully general encoding, a
    separate perm_bits per location plus a global one."""
    p = base_payload(nperms)
    if nlocs == 0:
        return p
    # A scoped user's per-location grant set is a subset of the flat set; assume
    # the scoped assignment carries the same role, so the same bitset repeats.
    per = perm_bits(nperms)
    p["loc_perm_bits"] = {lid: per for lid in locs(nlocs)}
    return p


def enc_e_role_to_locations(nperms, nlocs):
    """E -- scope attached to the role assignment, not to permissions."""
    p = base_payload(nperms)
    if nlocs == 0:
        p["scp"] = {"ROLE_INVENTORY_MANAGER": "GLOBAL"}
    else:
        p["scp"] = {"ROLE_INVENTORY_MANAGER": locs(nlocs)}
    return p


ENCODINGS = [
    ("A  status quo (no scope)", enc_a_status_quo),
    ("B  loc_scope, UUID strings", enc_b_scope_claim_uuid_strings),
    ("C  loc_scope, packed b64url", enc_c_scope_claim_packed),
    ("D  per-location bitset map", enc_d_per_location_bitsets),
    ("E  role -> locations map", enc_e_role_to_locations),
]

PERM_PROFILES = [
    ("DISPATCHER-like", 11),
    ("SHOP_MANAGER-like", 17),
    ("CONTROLLER-like", 52),
    ("ADMIN-like", 387),
    ("whole catalog", CATALOG_SIZE),
]

LOC_COUNTS = [0, 1, 5, 25, 100, 500]


def main():
    print(f"catalog size {CATALOG_SIZE} codes; full bitset = "
          f"{len(perm_bits(CATALOG_SIZE))} base64url chars\n")
    print(f"Tomcat max-http-header-size = {MAX_HTTP_HEADER_SIZE} bytes; "
          f"'Authorization: Bearer ' overhead = {BEARER_OVERHEAD}\n")

    for pname, nperms in PERM_PROFILES:
        print(f"--- {pname} ({nperms} permissions) " + "-" * (46 - len(pname)))
        header = "  encoding".ljust(32) + "".join(f"{n:>9}" for n in LOC_COUNTS)
        print(header + "   locations")
        for label, fn in ENCODINGS:
            row = f"  {label}".ljust(32)
            for n in LOC_COUNTS:
                size = jws_size(fn(nperms, n))
                flag = "!" if size + BEARER_OVERHEAD > MAX_HTTP_HEADER_SIZE else ""
                row += f"{size:>8}{flag or ' '}"
            print(row)
        print()

    print("'!' marks a token that alone exceeds max-http-header-size.")
    print("Sizes are JWS compact-serialisation characters (== bytes, all ASCII).")


if __name__ == "__main__":
    main()
