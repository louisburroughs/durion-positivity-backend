# MCP facade reachability — issue #1612

Issue #1612 found that every role was blocked from most MCP facade tools by **exactly one**
missing permission, never two or more. The gate was not structurally wrong: roles were each
short a single reference-data read, because a facade must resolve a location, customer or
order id before it can do anything useful. A role holding the functional permission still
got nothing.

Regenerate this matrix with:

```bash
python3 scripts/mcp-facade-reachability.py
```

It reads the facade permission groups from `pos-mcp-server`'s migrations and the role grants
from `scripts/fixtures/seed/alpha/security/role-permissions.csv` — no database. It reproduced
the live alpha figures exactly for all ten roles the issue scored, which is what makes those
files trustworthy substitutes for querying the deployed database.

`--check` compares against `scripts/mcp-facade-reachability-baseline.json` and fails when any
role gains or loses a facade. It is not wired into CI; reachability moves for legitimate
reasons, so the baseline is a record to update deliberately.

## Result

**78 → 174 reachable role-facade pairs** across the 15 scored facades. `EventsFacadeTool` is
excluded: it is gated on the `AUTHENTICATED` sentinel, which every authenticated caller holds.

| role | before | after |
|---|---:|---:|
| `ACCOUNTING_ASSOCIATE` | 1/15 | **14/15** |
| `SHOP_MANAGER` | 2/15 | **11/15** |
| `CONTROLLER` | 3/15 | **14/15** |
| `INVENTORY_CONTROLLER` | 3/15 | **11/15** |
| `INVENTORY_MANAGER` | 3/15 | **11/15** |
| `ACCOUNT_MANAGER` | 4/15 | **14/15** |
| `DISPATCHER` | 4/15 | **11/15** |
| `MANAGER` | 4/15 | **12/15** |
| `TECHNICIAN` | 4/15 | **11/15** |
| `INVENTORY_LEAD` | 6/15 | **12/15** |
| `GENERAL_MANAGER` | 9/15 | **15/15** |
| `SERVICE_ADVISOR` | 9/15 | **11/15** |
| `LOCATION_MANAGER` | 10/15 | **11/15** |
| `ADMIN` | 15/15 | 15/15 |
| `SYSTEM_ADMINISTRATOR` | 1/15 | 1/15 — unchanged, see below |
| `CUSTOMER` | 0/15 | 0/15 — unchanged, see below |
| `SELF_SERVICE_CUSTOMER` | 0/15 | 0/15 — unchanged, see below |

## What still blocks, and why it is deliberate

Only the three codes deferred as business policy remain: `accounting:coa:view`,
`tax:calculate`, and `reporting:view:financial-statements` beyond the finance roles. Whether a
`SHOP_MANAGER` or `TECHNICIAN` should see tax and accounting data is a business decision, not
an engineering one, so no grant was made for them.

Three roles were deliberately left out of the reference-data grants:

- **`CUSTOMER` and `SELF_SERVICE_CUSTOMER`** are `mcp_persona_eligible = false` since #1613, so
  MCP reference grants for them would be dead weight.
- **`SYSTEM_ADMINISTRATOR`** stays at 1/15, and this is the policy working rather than the
  mis-grant it resembles.
  `RolePermissionBaselineTest#systemAdministrator_isSecurityScopedNotSuperuser` states a rule,
  not a list: the role may hold `security:*`, `mcp:*`, `nlti:*` and a named carve-out set
  **only**, never a broader domain authority. Granting it the nine reference reads would have
  broken that invariant, so it was excluded.

`AdminFacadeTool` also stays where it was for the eleven roles that lack its admin codes.
#1612 attributed that gate to `security:audit:view`; the gate is actually
`security:user:view`, because `V40:436-441` gives the tool four single-code groups and
reachability is OR across them. The endpoint fix in this issue lets any caller read their own
permissions through the API, but the tool itself keeps its admin gate: giving
`getMyPermissions` an `AUTHENTICATED` group would have offered an admin tool to every
authenticated caller, which is the #1115 defect that
`FacadeToolPermissionSeedTest#noFacadeMixesAuthenticatedWithPrivilege` rejects.

## Current matrix

```
facades scored: 15 (excluded: EventsFacadeTool)

role                     perms   reach  facades
CUSTOMER                     4    0/15  
SELF_SERVICE_CUSTOMER        4    0/15  
SHOP_MANAGER                20   11/15  Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, ShopManager, Vehicle, Workorder
DISPATCHER                  23   11/15  Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, ShopManager, Vehicle, Workorder
ACCOUNTING_ASSOCIATE        24   14/15  Accounting, Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, Reporting, ShopManager, Tax, Vehicle, Workorder
TECHNICIAN                  38   11/15  Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, ShopManager, Vehicle, Workorder
ACCOUNT_MANAGER             40   14/15  Accounting, Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, Reporting, ShopManager, Tax, Vehicle, Workorder
SYSTEM_ADMINISTRATOR        40    1/15  Admin
MANAGER                     48   12/15  Admin, Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, ShopManager, Vehicle, Workorder
INVENTORY_LEAD              51   12/15  Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, Reporting, ShopManager, Vehicle, Workorder
INVENTORY_CONTROLLER        55   11/15  Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, ShopManager, Vehicle, Workorder
INVENTORY_MANAGER           57   11/15  Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, ShopManager, Vehicle, Workorder
GENERAL_MANAGER             59   15/15  Accounting, Admin, Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, Reporting, ShopManager, Tax, Vehicle, Workorder
CONTROLLER                  63   14/15  Accounting, Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, Reporting, ShopManager, Tax, Vehicle, Workorder
SERVICE_ADVISOR             88   11/15  Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, ShopManager, Vehicle, Workorder
LOCATION_MANAGER           127   11/15  Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, ShopManager, Vehicle, Workorder
ADMIN                      425   15/15  Accounting, Admin, Catalog, Customer, Hr, Inventory, Invoice, Location, Order, Pricing, Reporting, ShopManager, Tax, Vehicle, Workorder

reachable role-facade pairs: 174

single-code blocks (one permission from reachable):
  reporting:view:financial-statements       14  CUSTOMER->Reporting; CUSTOMER->Tax; DISPATCHER->Reporting; INVENTORY_CONTROLLER->Reporting; INVENTORY_MANAGER->Reporting; LOCATION_MANAGER->Reporting; MANAGER->Reporting; SELF_SERVICE_CUSTOMER->Reporting; SELF_SERVICE_CUSTOMER->Tax; SERVICE_ADVISOR->Reporting; SHOP_MANAGER->Reporting; SYSTEM_ADMINISTRATOR->Reporting; SYSTEM_ADMINISTRATOR->Tax; TECHNICIAN->Reporting
  security:user:view                        13  ACCOUNTING_ASSOCIATE->Admin; ACCOUNT_MANAGER->Admin; CONTROLLER->Admin; CUSTOMER->Admin; DISPATCHER->Admin; INVENTORY_CONTROLLER->Admin; INVENTORY_LEAD->Admin; INVENTORY_MANAGER->Admin; LOCATION_MANAGER->Admin; SELF_SERVICE_CUSTOMER->Admin; SERVICE_ADVISOR->Admin; SHOP_MANAGER->Admin; TECHNICIAN->Admin
  accounting:coa:view                       12  CUSTOMER->Accounting; DISPATCHER->Accounting; INVENTORY_CONTROLLER->Accounting; INVENTORY_LEAD->Accounting; INVENTORY_MANAGER->Accounting; LOCATION_MANAGER->Accounting; MANAGER->Accounting; SELF_SERVICE_CUSTOMER->Accounting; SERVICE_ADVISOR->Accounting; SHOP_MANAGER->Accounting; SYSTEM_ADMINISTRATOR->Accounting; TECHNICIAN->Accounting
  tax:calculate                              9  DISPATCHER->Tax; INVENTORY_CONTROLLER->Tax; INVENTORY_LEAD->Tax; INVENTORY_MANAGER->Tax; LOCATION_MANAGER->Tax; MANAGER->Tax; SERVICE_ADVISOR->Tax; SHOP_MANAGER->Tax; TECHNICIAN->Tax
  catalog:product:view                       6  CUSTOMER->Catalog; CUSTOMER->Pricing; SELF_SERVICE_CUSTOMER->Catalog; SELF_SERVICE_CUSTOMER->Pricing; SYSTEM_ADMINISTRATOR->Catalog; SYSTEM_ADMINISTRATOR->Pricing
  location:read                              6  CUSTOMER->Location; CUSTOMER->ShopManager; SELF_SERVICE_CUSTOMER->Location; SELF_SERVICE_CUSTOMER->ShopManager; SYSTEM_ADMINISTRATOR->Location; SYSTEM_ADMINISTRATOR->ShopManager
  crm:party:view                             3  CUSTOMER->Customer; SELF_SERVICE_CUSTOMER->Customer; SYSTEM_ADMINISTRATOR->Customer
  people:employee:view                       3  CUSTOMER->Hr; SELF_SERVICE_CUSTOMER->Hr; SYSTEM_ADMINISTRATOR->Hr
  inventory:availability:read                3  CUSTOMER->Inventory; SELF_SERVICE_CUSTOMER->Inventory; SYSTEM_ADMINISTRATOR->Inventory
  invoice:invoice:view                       3  CUSTOMER->Invoice; SELF_SERVICE_CUSTOMER->Invoice; SYSTEM_ADMINISTRATOR->Invoice
  order:order:view                           3  CUSTOMER->Order; SELF_SERVICE_CUSTOMER->Order; SYSTEM_ADMINISTRATOR->Order
  vehicle-inventory:registry:view            3  CUSTOMER->Vehicle; SELF_SERVICE_CUSTOMER->Vehicle; SYSTEM_ADMINISTRATOR->Vehicle
  workorder:workorder:view                   3  CUSTOMER->Workorder; SELF_SERVICE_CUSTOMER->Workorder; SYSTEM_ADMINISTRATOR->Workorder

blocked by two or more codes: 0
```
