# ADR-0042 OpenAPI Rollout Baseline — retired

> **Retired.** This file was a backend-specific rollout baseline (point-in-time module
> inventory, wave sequencing, and a committed-spec metadata scan) for the #645 OpenAPI rollout.
> It has been superseded by its live successors, so it is no longer maintained here:
>
> - **Canonical decision / annotation standards:** ADR-0042, `durion/docs/adr/0042-openapi-annotation-standards.adr.md`
>   (<https://github.com/louisburroughs/durion/blob/main/docs/adr/0042-openapi-annotation-standards.adr.md>)
> - **Live module policy & wave assignment:** `pos-openapi-validation/src/test/resources/openapi/module-inventory.yaml`,
>   enforced by the `pos-openapi-validation` tests (`report` / `strict`).
> - **Generation & validation mechanics:** `docs/DEVELOPMENT_GUIDE.md` → OpenAPI Documentation.
> - **Historical baseline snapshot** (waves, gap classification, per-module metadata scan): available in
>   this file's git history prior to retirement.
>
> Do not re-add the baseline body here. Track rollout state in `module-inventory.yaml` and #645.
