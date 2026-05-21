-- V20: Assign bit_index for NLTI permissions (seeded but missing bit_index)
--      and add new catalog entries for batch 7 (accounting, CRM, pricing,
--      inventory, workorder) at bit indexes 241-261.
--
-- RULE: bit_index values are permanent.  Never reassign or reuse them.

-- ── NLTI (bit indexes 221-223) ───────────────────────────────────────────
-- These permissions were seeded but V4 did not include their bit_index UPDATE.
UPDATE permissions SET bit_index = 221 WHERE name = 'nlti:request:submit';
UPDATE permissions SET bit_index = 222 WHERE name = 'nlti:request:read';
UPDATE permissions SET bit_index = 223 WHERE name = 'nlti:audit:read';

-- ── Accounting (batch 2, bit indexes 241-243) ────────────────────────────
UPDATE permissions SET bit_index = 241 WHERE name = 'accounting:events:reprocess';
UPDATE permissions SET bit_index = 242 WHERE name = 'accounting:export:request';
UPDATE permissions SET bit_index = 243 WHERE name = 'accounting:export:view';

-- ── CRM (batch 2, bit index 244) ─────────────────────────────────────────
UPDATE permissions SET bit_index = 244 WHERE name = 'crm:billing_rules:edit';

-- ── Pricing (batch 2, bit index 245) ─────────────────────────────────────
UPDATE permissions SET bit_index = 245 WHERE name = 'pricing:base_price:create';

-- ── Inventory (batch 2, bit indexes 246-260) ─────────────────────────────
UPDATE permissions SET bit_index = 246 WHERE name = 'inventory:ledger:view';
UPDATE permissions SET bit_index = 247 WHERE name = 'inventory:location:admin';
UPDATE permissions SET bit_index = 248 WHERE name = 'inventory:location:view';
UPDATE permissions SET bit_index = 249 WHERE name = 'inventory:pick_list:create';
UPDATE permissions SET bit_index = 250 WHERE name = 'inventory:pick_list:execute';
UPDATE permissions SET bit_index = 251 WHERE name = 'inventory:pick_list:view';
UPDATE permissions SET bit_index = 252 WHERE name = 'inventory:putaway:claim';
UPDATE permissions SET bit_index = 253 WHERE name = 'inventory:putaway:execute';
UPDATE permissions SET bit_index = 254 WHERE name = 'inventory:putaway:generate';
UPDATE permissions SET bit_index = 255 WHERE name = 'inventory:putaway:view';
UPDATE permissions SET bit_index = 256 WHERE name = 'inventory:return:view';
UPDATE permissions SET bit_index = 257 WHERE name = 'inventory:return:write';
UPDATE permissions SET bit_index = 258 WHERE name = 'inventory:shortage:resolve';
UPDATE permissions SET bit_index = 259 WHERE name = 'inventory:shortage:view';
UPDATE permissions SET bit_index = 260 WHERE name = 'inventory:stock_movement:create';

-- ── Workorder (batch 2, bit index 261) ───────────────────────────────────
UPDATE permissions SET bit_index = 261 WHERE name = 'workorder:parts:consume';
