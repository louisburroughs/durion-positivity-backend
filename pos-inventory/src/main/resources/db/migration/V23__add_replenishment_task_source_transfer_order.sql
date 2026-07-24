-- odoo-parity F5 (#1045, spec F5): link a replenishment need to the cross-site TransferOrder
-- that materializes its internal sourcing.
--
-- source_transfer_order_id — set only when internal sourcing resolved the need to a cross-site
--                            transfer (WS-C TransferOrder created in DRAFT); NULL for same-site
--                            bin-move tasks and for BACKSTOCK_UNAVAILABLE tasks. No foreign key
--                            constraint: the linkage is advisory (the transfer aggregate owns
--                            its own lifecycle) and mirrors the module's no-cross-table-FK style.
ALTER TABLE replenishment_task
    ADD COLUMN source_transfer_order_id uuid;
