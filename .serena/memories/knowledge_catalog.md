# Knowledge Catalog (cross-repo navigation)

- Location: `../durion/knowledge-catalog/` (sibling `durion` checkout). Root: `index.md`.
- OKF navigation layer over whole workspace: one entry per ADR (`adr/`), domain (`domains/`), backend `pos-*` module (`backend/`), platform doc from either repo (`platform/`).
- Entry = pointer, not copy. YAML frontmatter: `type`, `title`, `description`, `resource` (GitHub URL), `path` (workspace-relative), `tags`. Follow `path:` for authoritative content.
- Use first to locate where something lives (ADR, domain rules, owning module) before reading source.
- Lookups: `grep -Rl "type: Domain" ../durion/knowledge-catalog/`; `grep -Rn "tags:.*<topic>" ../durion/knowledge-catalog/`.
- Never hand-edit entries: build output of `durion/scripts/generate-knowledge-catalog.py`. Fix sources instead (ADR frontmatter, domain `index.md`, `.business-rules/`), then regenerate in `durion`.
