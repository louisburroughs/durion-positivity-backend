-- Wave 4 (odoo parity #1077/#1080): serial/lot capture and source-document returnability.

-- H3 (#1080): captured lot/serial numbers per line (count <= quantity, enforced in service)
CREATE TABLE order_line_serial (
  order_line_id UUID NOT NULL,
  serial_index INTEGER NOT NULL,
  serial_number VARCHAR(128) NOT NULL,
  PRIMARY KEY (order_line_id, serial_index)
);

-- E1 (#1077): explicit returnable flag imported from the source document (resolved Q6);
-- null on counter lines (returnable by policy), never inferred for imported lines.
ALTER TABLE sales_order_line ADD COLUMN returnable BOOLEAN;
