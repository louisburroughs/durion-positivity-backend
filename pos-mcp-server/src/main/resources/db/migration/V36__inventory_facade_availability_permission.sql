-- V36: Add inventory:availability:read to InventoryFacadeTool's candidate gate (ADR-0057, #1494).
--
-- V18 mirrored the downstream controller guards literally, and at that time every
-- InventoryFacadeTool method routed to an endpoint guarded by inventory:on_hand:view /
-- inventory:on_hand:search:
--   checkStock       → /v1/inventory/availability/by-sku
--   searchInventory  → /v1/inventory/availability/by-sku (same controller)
--   getLocationStock → /v1/inventory/locations/{locationId}/inventory-inquiry
--
-- ADR-0057 separates the two permission families: inventory:on_hand:* reads the stock record
-- itself, inventory:availability:* reads the derived projection net of prior commitments. The
-- two availability-backed methods moved to inventory:availability:read; getLocationStock keeps
-- inventory:on_hand:view. The union of the tool's methods is therefore all three codes.
--
-- mcp_tool_permission is OR-semantics (see V18): a caller holding ANY listed code sees the tool.
-- Adding the availability code makes the facade reachable for the roles that can legitimately
-- answer "can this part be promised" — TECHNICIAN, SERVICE_ADVISOR, LOCATION_MANAGER — while the
-- per-method authorization still runs downstream, so a caller holding only availability:read
-- still receives 403 from getLocationStock.
--
-- The on-hand codes are deliberately NOT deleted: INVENTORY_LEAD reaches the tool through them
-- for getLocationStock, which remains an on-hand read.
--
-- Idempotent: INSERT ... ON CONFLICT DO NOTHING tolerates re-runs.

INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('inventory:availability:read')
) AS perms(code)
WHERE mcp_tool.name = 'InventoryFacadeTool'
ON CONFLICT DO NOTHING;
