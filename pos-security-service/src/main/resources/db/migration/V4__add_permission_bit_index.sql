-- PERM-002: Add bit_index column for compact permission bitset encoding
-- Each permission maps to a unique bit position in the PermissionCode catalog.
-- Permissions absent from the PermissionCode catalog retain NULL.

ALTER TABLE permissions ADD COLUMN IF NOT EXISTS bit_index INTEGER;

-- Unique partial index: only non-null bit_index values must be distinct
CREATE UNIQUE INDEX IF NOT EXISTS idx_permissions_bit_index
    ON permissions (bit_index)
    WHERE bit_index IS NOT NULL;

-- Accounting (0-10)
UPDATE permissions SET bit_index = 0   WHERE name = 'accounting:je:view';
UPDATE permissions SET bit_index = 1   WHERE name = 'accounting:je:create';
UPDATE permissions SET bit_index = 2   WHERE name = 'accounting:je:post';
UPDATE permissions SET bit_index = 3   WHERE name = 'accounting:ap:view';
UPDATE permissions SET bit_index = 4   WHERE name = 'accounting:ap:pay';
UPDATE permissions SET bit_index = 5   WHERE name = 'accounting:coa:view';
UPDATE permissions SET bit_index = 6   WHERE name = 'accounting:coa:create';
UPDATE permissions SET bit_index = 7   WHERE name = 'accounting:coa:edit';
UPDATE permissions SET bit_index = 8   WHERE name = 'accounting:events:view';
UPDATE permissions SET bit_index = 9   WHERE name = 'accounting:events:submit';
UPDATE permissions SET bit_index = 10  WHERE name = 'accounting:events:retry';

-- Catalog (11-26)
UPDATE permissions SET bit_index = 11  WHERE name = 'catalog:product:view';
UPDATE permissions SET bit_index = 12  WHERE name = 'catalog:product:create';
UPDATE permissions SET bit_index = 13  WHERE name = 'catalog:product:edit';
UPDATE permissions SET bit_index = 14  WHERE name = 'catalog:product:delete';
UPDATE permissions SET bit_index = 15  WHERE name = 'product:lifecycle:update';
UPDATE permissions SET bit_index = 16  WHERE name = 'product:lifecycle:override_discontinued';
UPDATE permissions SET bit_index = 17  WHERE name = 'catalog:category:view';
UPDATE permissions SET bit_index = 18  WHERE name = 'catalog:category:create';
UPDATE permissions SET bit_index = 19  WHERE name = 'catalog:category:edit';
UPDATE permissions SET bit_index = 20  WHERE name = 'catalog:category:delete';
UPDATE permissions SET bit_index = 21  WHERE name = 'catalog:service_type:view';
UPDATE permissions SET bit_index = 22  WHERE name = 'catalog:service_type:create';
UPDATE permissions SET bit_index = 23  WHERE name = 'catalog:service_type:edit';
UPDATE permissions SET bit_index = 24  WHERE name = 'catalog:variant:view';
UPDATE permissions SET bit_index = 25  WHERE name = 'catalog:variant:create';
UPDATE permissions SET bit_index = 26  WHERE name = 'catalog:variant:edit';

-- CRM (27-54)
UPDATE permissions SET bit_index = 27  WHERE name = 'crm:party:view';
UPDATE permissions SET bit_index = 28  WHERE name = 'crm:party:search';
UPDATE permissions SET bit_index = 29  WHERE name = 'crm:party:create';
UPDATE permissions SET bit_index = 30  WHERE name = 'crm:party:edit';
UPDATE permissions SET bit_index = 31  WHERE name = 'crm:party:deactivate';
UPDATE permissions SET bit_index = 32  WHERE name = 'crm:party:merge';
UPDATE permissions SET bit_index = 33  WHERE name = 'crm:contact:view';
UPDATE permissions SET bit_index = 34  WHERE name = 'crm:contact:create';
UPDATE permissions SET bit_index = 35  WHERE name = 'crm:contact:edit';
UPDATE permissions SET bit_index = 36  WHERE name = 'crm:contact:delete';
UPDATE permissions SET bit_index = 37  WHERE name = 'crm:contact_role:view';
UPDATE permissions SET bit_index = 38  WHERE name = 'crm:contact_role:assign';
UPDATE permissions SET bit_index = 39  WHERE name = 'crm:contact_role:revoke';
UPDATE permissions SET bit_index = 40  WHERE name = 'crm:contact_preference:view';
UPDATE permissions SET bit_index = 41  WHERE name = 'crm:contact_preference:edit';
UPDATE permissions SET bit_index = 42  WHERE name = 'crm:vehicle:view';
UPDATE permissions SET bit_index = 43  WHERE name = 'crm:vehicle:search';
UPDATE permissions SET bit_index = 44  WHERE name = 'crm:vehicle:create';
UPDATE permissions SET bit_index = 45  WHERE name = 'crm:vehicle:edit';
UPDATE permissions SET bit_index = 46  WHERE name = 'crm:vehicle:deactivate';
UPDATE permissions SET bit_index = 47  WHERE name = 'crm:vehicle_party_association:create';
UPDATE permissions SET bit_index = 48  WHERE name = 'crm:vehicle_party_association:view';
UPDATE permissions SET bit_index = 49  WHERE name = 'crm:vehicle_party_association:edit';
UPDATE permissions SET bit_index = 50  WHERE name = 'crm:vehicle_preference:view';
UPDATE permissions SET bit_index = 51  WHERE name = 'crm:vehicle_preference:edit';
UPDATE permissions SET bit_index = 52  WHERE name = 'crm:processing_log:view';
UPDATE permissions SET bit_index = 53  WHERE name = 'crm:suspense:view';
UPDATE permissions SET bit_index = 54  WHERE name = 'crm:integration:audit';

-- Documents (55)
UPDATE permissions SET bit_index = 55  WHERE name = 'documents:render';

-- Inventory (56-81)
UPDATE permissions SET bit_index = 56  WHERE name = 'inventory:adjustment:create';
UPDATE permissions SET bit_index = 57  WHERE name = 'inventory:adjustment:approve';
UPDATE permissions SET bit_index = 58  WHERE name = 'inventory:adjustment:view';
UPDATE permissions SET bit_index = 59  WHERE name = 'inventory:putaway:override_location_compatibility';
UPDATE permissions SET bit_index = 60  WHERE name = 'inventory:putaway:override_location_capacity';
UPDATE permissions SET bit_index = 61  WHERE name = 'inventory:cycle_count:initiate';
UPDATE permissions SET bit_index = 62  WHERE name = 'inventory:cycle_count:view';
UPDATE permissions SET bit_index = 63  WHERE name = 'inventory:cycle_count:complete';
UPDATE permissions SET bit_index = 64  WHERE name = 'inventory:on_hand:view';
UPDATE permissions SET bit_index = 65  WHERE name = 'inventory:on_hand:search';
UPDATE permissions SET bit_index = 66  WHERE name = 'inventory:receiving:create';
UPDATE permissions SET bit_index = 67  WHERE name = 'inventory:receiving:view';
UPDATE permissions SET bit_index = 68  WHERE name = 'inventory:receiving:complete';
UPDATE permissions SET bit_index = 69  WHERE name = 'inventory:issue:parts';
UPDATE permissions SET bit_index = 70  WHERE name = 'inventory:override:part-match';
UPDATE permissions SET bit_index = 71  WHERE name = 'inventory:purchase_order:create';
UPDATE permissions SET bit_index = 72  WHERE name = 'inventory:purchase_order:view';
UPDATE permissions SET bit_index = 73  WHERE name = 'inventory:purchase_order:approve';
UPDATE permissions SET bit_index = 74  WHERE name = 'inventory:purchase_order:receive';
UPDATE permissions SET bit_index = 75  WHERE name = 'inventory:asn:create';
UPDATE permissions SET bit_index = 76  WHERE name = 'inventory:asn:view';
UPDATE permissions SET bit_index = 77  WHERE name = 'inventory:goods_receipt:create';
UPDATE permissions SET bit_index = 78  WHERE name = 'inventory:goods_receipt:view';
UPDATE permissions SET bit_index = 79  WHERE name = 'inventory:goods_receipt:override';
UPDATE permissions SET bit_index = 80  WHERE name = 'inventory:allocations:reallocate';
UPDATE permissions SET bit_index = 81  WHERE name = 'inventory:shortages:resolve';

-- Invoice (82-84)
UPDATE permissions SET bit_index = 82  WHERE name = 'invoice:manage';
UPDATE permissions SET bit_index = 83  WHERE name = 'invoice:billing-rules';
UPDATE permissions SET bit_index = 84  WHERE name = 'invoice:finalize';

-- Location (85-94)
UPDATE permissions SET bit_index = 85  WHERE name = 'location:read';
UPDATE permissions SET bit_index = 86  WHERE name = 'location:write';
UPDATE permissions SET bit_index = 87  WHERE name = 'location:bay:read';
UPDATE permissions SET bit_index = 88  WHERE name = 'location:bay:manage';
UPDATE permissions SET bit_index = 89  WHERE name = 'location:mobile-unit:read';
UPDATE permissions SET bit_index = 90  WHERE name = 'location:mobile-unit:manage';
UPDATE permissions SET bit_index = 91  WHERE name = 'location:service-area:read';
UPDATE permissions SET bit_index = 92  WHERE name = 'location:service-area:manage';
UPDATE permissions SET bit_index = 93  WHERE name = 'location:travel-buffer-policy:read';
UPDATE permissions SET bit_index = 94  WHERE name = 'location:travel-buffer-policy:manage';

-- MCP (95-102)
UPDATE permissions SET bit_index = 95  WHERE name = 'mcp:llm_api:view';
UPDATE permissions SET bit_index = 96  WHERE name = 'mcp:llm_api:create';
UPDATE permissions SET bit_index = 97  WHERE name = 'mcp:llm_api:update';
UPDATE permissions SET bit_index = 98  WHERE name = 'mcp:llm_api:delete';
UPDATE permissions SET bit_index = 99  WHERE name = 'mcp:system_prompt:view';
UPDATE permissions SET bit_index = 100 WHERE name = 'mcp:system_prompt:create';
UPDATE permissions SET bit_index = 101 WHERE name = 'mcp:system_prompt:update';
UPDATE permissions SET bit_index = 102 WHERE name = 'mcp:system_prompt:delete';

-- Order (103-115)
UPDATE permissions SET bit_index = 103 WHERE name = 'order:order:view';
UPDATE permissions SET bit_index = 104 WHERE name = 'order:order:create';
UPDATE permissions SET bit_index = 105 WHERE name = 'order:order:edit';
UPDATE permissions SET bit_index = 106 WHERE name = 'order:order:cancel';
UPDATE permissions SET bit_index = 107 WHERE name = 'order:line:view';
UPDATE permissions SET bit_index = 108 WHERE name = 'order:line:create';
UPDATE permissions SET bit_index = 109 WHERE name = 'order:line:edit';
UPDATE permissions SET bit_index = 110 WHERE name = 'order:line:delete';
UPDATE permissions SET bit_index = 111 WHERE name = 'order:line:enter_manual_price';
UPDATE permissions SET bit_index = 112 WHERE name = 'order:price_override:view';
UPDATE permissions SET bit_index = 113 WHERE name = 'order:price_override:apply';
UPDATE permissions SET bit_index = 114 WHERE name = 'order:price_override:approve';
UPDATE permissions SET bit_index = 115 WHERE name = 'order:price_override:reject';

-- People (116-125)
UPDATE permissions SET bit_index = 116 WHERE name = 'people:employee:view';
UPDATE permissions SET bit_index = 117 WHERE name = 'people:employee:create';
UPDATE permissions SET bit_index = 118 WHERE name = 'people:employee:edit';
UPDATE permissions SET bit_index = 119 WHERE name = 'people:employee:deactivate';
UPDATE permissions SET bit_index = 120 WHERE name = 'people:role:view';
UPDATE permissions SET bit_index = 121 WHERE name = 'people:role:assign';
UPDATE permissions SET bit_index = 122 WHERE name = 'people:role:revoke';
UPDATE permissions SET bit_index = 123 WHERE name = 'people:skill:view';
UPDATE permissions SET bit_index = 124 WHERE name = 'people:skill:assign';
UPDATE permissions SET bit_index = 125 WHERE name = 'people:skill:edit';

-- Pricing (126-137)
UPDATE permissions SET bit_index = 126 WHERE name = 'pricing:price_book:view';
UPDATE permissions SET bit_index = 127 WHERE name = 'pricing:price_book:create';
UPDATE permissions SET bit_index = 128 WHERE name = 'pricing:price_book:edit';
UPDATE permissions SET bit_index = 129 WHERE name = 'pricing:price_book:delete';
UPDATE permissions SET bit_index = 130 WHERE name = 'pricing:normalization:view';
UPDATE permissions SET bit_index = 131 WHERE name = 'pricing:normalization:edit';
UPDATE permissions SET bit_index = 132 WHERE name = 'pricing:restrictions:view';
UPDATE permissions SET bit_index = 133 WHERE name = 'pricing:restrictions:edit';
UPDATE permissions SET bit_index = 134 WHERE name = 'pricing:rule:view';
UPDATE permissions SET bit_index = 135 WHERE name = 'pricing:rule:create';
UPDATE permissions SET bit_index = 136 WHERE name = 'pricing:rule:edit';
UPDATE permissions SET bit_index = 137 WHERE name = 'pricing:rule:delete';

-- Security (138-148)
UPDATE permissions SET bit_index = 138 WHERE name = 'security:role:view';
UPDATE permissions SET bit_index = 139 WHERE name = 'security:role:create';
UPDATE permissions SET bit_index = 140 WHERE name = 'security:role:edit';
UPDATE permissions SET bit_index = 141 WHERE name = 'security:role:delete';
UPDATE permissions SET bit_index = 142 WHERE name = 'security:role:assign';
UPDATE permissions SET bit_index = 143 WHERE name = 'security:permission:view';
UPDATE permissions SET bit_index = 144 WHERE name = 'security:permission:register';
UPDATE permissions SET bit_index = 145 WHERE name = 'security:user:view';
UPDATE permissions SET bit_index = 146 WHERE name = 'security:user:create';
UPDATE permissions SET bit_index = 147 WHERE name = 'security:user:edit';
UPDATE permissions SET bit_index = 148 WHERE name = 'security:user:delete';

-- Shop (149-158)
UPDATE permissions SET bit_index = 149 WHERE name = 'shop:location:view';
UPDATE permissions SET bit_index = 150 WHERE name = 'shop:location:create';
UPDATE permissions SET bit_index = 151 WHERE name = 'shop:location:edit';
UPDATE permissions SET bit_index = 152 WHERE name = 'shop:location:deactivate';
UPDATE permissions SET bit_index = 153 WHERE name = 'shop:bay:view';
UPDATE permissions SET bit_index = 154 WHERE name = 'shop:bay:create';
UPDATE permissions SET bit_index = 155 WHERE name = 'shop:bay:edit';
UPDATE permissions SET bit_index = 156 WHERE name = 'shop:bay:assign';
UPDATE permissions SET bit_index = 157 WHERE name = 'shop:schedule:view';
UPDATE permissions SET bit_index = 158 WHERE name = 'shop:schedule:edit';

-- Appointments (159-162)
UPDATE permissions SET bit_index = 159 WHERE name = 'appointments:create';
UPDATE permissions SET bit_index = 160 WHERE name = 'appointments:view';
UPDATE permissions SET bit_index = 161 WHERE name = 'appointments:reschedule';
UPDATE permissions SET bit_index = 162 WHERE name = 'appointments:cancel';

-- Tax (163-164)
UPDATE permissions SET bit_index = 163 WHERE name = 'tax:calculate';
UPDATE permissions SET bit_index = 164 WHERE name = 'tax:mode:view';

-- Vehicle Fitment (165-169)
UPDATE permissions SET bit_index = 165 WHERE name = 'vehicle-fitment:hint:view';
UPDATE permissions SET bit_index = 166 WHERE name = 'vehicle-fitment:hint:create';
UPDATE permissions SET bit_index = 167 WHERE name = 'vehicle-fitment:hint:update';
UPDATE permissions SET bit_index = 168 WHERE name = 'vehicle-fitment:hint:delete';
UPDATE permissions SET bit_index = 169 WHERE name = 'vehicle-fitment:catalog:view';

-- Vehicle Inventory (170-175)
UPDATE permissions SET bit_index = 170 WHERE name = 'vehicle-inventory:registry:view';
UPDATE permissions SET bit_index = 171 WHERE name = 'vehicle-inventory:registry:create';
UPDATE permissions SET bit_index = 172 WHERE name = 'vehicle-inventory:registry:update';
UPDATE permissions SET bit_index = 173 WHERE name = 'vehicle-inventory:registry:delete';
UPDATE permissions SET bit_index = 174 WHERE name = 'vehicle-inventory:preferences:manage';
UPDATE permissions SET bit_index = 175 WHERE name = 'vehicle-inventory:search:view';

-- Workorder (176-214)
UPDATE permissions SET bit_index = 176 WHERE name = 'workorder:workorder:view';
UPDATE permissions SET bit_index = 177 WHERE name = 'workorder:workorder:create';
UPDATE permissions SET bit_index = 178 WHERE name = 'workorder:workorder:edit';
UPDATE permissions SET bit_index = 179 WHERE name = 'workorder:workorder:delete';
UPDATE permissions SET bit_index = 180 WHERE name = 'workorder:workorder:start';
UPDATE permissions SET bit_index = 181 WHERE name = 'workorder:workorder:complete';
UPDATE permissions SET bit_index = 182 WHERE name = 'workorder:workorder:reopen_completed';
UPDATE permissions SET bit_index = 183 WHERE name = 'workorder:workorder:approve';
UPDATE permissions SET bit_index = 184 WHERE name = 'workorder:estimate:view';
UPDATE permissions SET bit_index = 185 WHERE name = 'workorder:estimate:create';
UPDATE permissions SET bit_index = 186 WHERE name = 'workorder:estimate:edit';
UPDATE permissions SET bit_index = 187 WHERE name = 'workorder:estimate:delete';
UPDATE permissions SET bit_index = 188 WHERE name = 'workorder:estimate:approve';
UPDATE permissions SET bit_index = 189 WHERE name = 'workorder:estimate:decline';
UPDATE permissions SET bit_index = 190 WHERE name = 'workorder:estimate:reopen';
UPDATE permissions SET bit_index = 191 WHERE name = 'workorder:estimate:calculate';
UPDATE permissions SET bit_index = 192 WHERE name = 'workorder:estimate_item:view';
UPDATE permissions SET bit_index = 193 WHERE name = 'workorder:estimate_item:add';
UPDATE permissions SET bit_index = 194 WHERE name = 'workorder:estimate_item:edit';
UPDATE permissions SET bit_index = 195 WHERE name = 'workorder:estimate_item:delete';
UPDATE permissions SET bit_index = 196 WHERE name = 'workorder:estimate_snapshot:create';
UPDATE permissions SET bit_index = 197 WHERE name = 'workorder:estimate_snapshot:view';
UPDATE permissions SET bit_index = 198 WHERE name = 'workorder:change_request:view';
UPDATE permissions SET bit_index = 199 WHERE name = 'workorder:change_request:create';
UPDATE permissions SET bit_index = 200 WHERE name = 'workorder:change_request:approve';
UPDATE permissions SET bit_index = 201 WHERE name = 'workorder:change_request:decline';
UPDATE permissions SET bit_index = 202 WHERE name = 'workorder:change_request:emergency_override';
UPDATE permissions SET bit_index = 203 WHERE name = 'workorder:approval_config:view';
UPDATE permissions SET bit_index = 204 WHERE name = 'workorder:approval_config:create';
UPDATE permissions SET bit_index = 205 WHERE name = 'workorder:approval_config:edit';
UPDATE permissions SET bit_index = 206 WHERE name = 'workorder:approval_config:delete';
UPDATE permissions SET bit_index = 207 WHERE name = 'workorder:invoice:view';
UPDATE permissions SET bit_index = 208 WHERE name = 'workorder:invoice:create';
UPDATE permissions SET bit_index = 209 WHERE name = 'workorder:labor:view';
UPDATE permissions SET bit_index = 210 WHERE name = 'workorder:labor:add';
UPDATE permissions SET bit_index = 211 WHERE name = 'workorder:parts:view';
UPDATE permissions SET bit_index = 212 WHERE name = 'workorder:parts:add';
UPDATE permissions SET bit_index = 213 WHERE name = 'workorder:wip:view';
UPDATE permissions SET bit_index = 214 WHERE name = 'workorder:wip:view_all_locations';
