# ADR-0044 — Event-Only Domain Walls and Module Communication Policy

> **Moved.** This ADR is **canonical in the durion repository**, not here. The full text,
> rules (R1–R6), §sections, and all amendments live at:
>
> **`durion/docs/adr/0044-platform-event-only-domain-walls.adr.md`**
> <https://github.com/louisburroughs/durion/blob/main/docs/adr/0044-platform-event-only-domain-walls.adr.md>
>
> This file was previously a full copy; it drifted from the canonical text (the 2026-07-22
> settlement amendment landed canonically first), so the copy has been retired in favor of this
> pointer. Do not re-add the body here — edit the canonical durion ADR and link to it.

## Backend enforcement

The build-time enforcement of this ADR is the cross-module ArchUnit rule
`com.positivity.archunit.DomainWallsTest` in `pos-archunit`. Its `SCOPED_MODULE_EXCEPTIONS`
javadoc documents the current, narrowest allowed synchronous domain edges (as of 2026-07-22:
the `pos-warranty` → `pos-invoice` settlement edge only) and cites the governing amendment.
