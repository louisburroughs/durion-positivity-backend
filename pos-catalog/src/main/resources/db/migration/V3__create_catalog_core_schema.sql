-- Create core catalog schema tables for empty-database bootstrap.
-- Existing environments with pre-created schema are preserved via IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS category (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS subcategory (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    category_id UUID,
    CONSTRAINT fk_subcategory_category FOREIGN KEY (category_id) REFERENCES category(id)
);

CREATE TABLE IF NOT EXISTS product (
    id UUID PRIMARY KEY,
    name VARCHAR(255),
    description TEXT,
    status VARCHAR(50),
    unit_of_measure VARCHAR(50),
    short_description TEXT,
    long_description TEXT,
    manufacturer_part_number VARCHAR(255),
    manufacturer_id UUID,
    manufacturer_name VARCHAR(255),
    manufacturer_warranty TEXT,
    manufacturer_brand VARCHAR(255),
    country_of_origin VARCHAR(100),
    sku VARCHAR(255),
    product_code VARCHAR(255),
    upc VARCHAR(255),
    attributes TEXT,
    product_code_type VARCHAR(50),
    category_id UUID,
    subcategory_id UUID,
    type VARCHAR(100),
    material VARCHAR(100),
    color VARCHAR(100),
    warranty TEXT,
    specifications TEXT,
    lifecycle_state VARCHAR(50),
    lifecycle_state_effective_at TIMESTAMP WITH TIME ZONE,
    last_state_changed_by UUID,
    last_state_changed_at TIMESTAMP WITH TIME ZONE,
    lifecycle_override_reason TEXT,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category(id),
    CONSTRAINT fk_product_subcategory FOREIGN KEY (subcategory_id) REFERENCES subcategory(id),
    CONSTRAINT uk_product_sku UNIQUE (sku),
    CONSTRAINT uk_product_manufacturer_mpn UNIQUE (manufacturer_id, manufacturer_part_number)
);

CREATE INDEX IF NOT EXISTS idx_product_manufacturer_part_number ON product(manufacturer_part_number);

CREATE TABLE IF NOT EXISTS service (
    id UUID PRIMARY KEY,
    code VARCHAR(100),
    name VARCHAR(255),
    short_description TEXT,
    long_description TEXT,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_service_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS non_inventory_product (
    id UUID PRIMARY KEY,
    name VARCHAR(255),
    description TEXT
);

CREATE TABLE IF NOT EXISTS dimensions (
    id UUID PRIMARY KEY,
    product_id UUID,
    type VARCHAR(50),
    value DECIMAL(19, 4),
    unit VARCHAR(50),
    CONSTRAINT fk_dimensions_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS product_images (
    product_id UUID NOT NULL,
    image_url TEXT,
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS catalog_entity (
    id UUID PRIMARY KEY,
    name VARCHAR(255),
    description TEXT
);

CREATE TABLE IF NOT EXISTS catalog_entity_products (
    catalog_entity_id UUID NOT NULL,
    products_id UUID NOT NULL,
    CONSTRAINT fk_catalog_entity_products_catalog FOREIGN KEY (catalog_entity_id) REFERENCES catalog_entity(id) ON DELETE CASCADE,
    CONSTRAINT fk_catalog_entity_products_product FOREIGN KEY (products_id) REFERENCES product(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS catalog_entity_services (
    catalog_entity_id UUID NOT NULL,
    services_id UUID NOT NULL,
    CONSTRAINT fk_catalog_entity_services_catalog FOREIGN KEY (catalog_entity_id) REFERENCES catalog_entity(id) ON DELETE CASCADE,
    CONSTRAINT fk_catalog_entity_services_service FOREIGN KEY (services_id) REFERENCES service(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS catalog_entity_non_inventory_products (
    catalog_entity_id UUID NOT NULL,
    non_inventory_products_id UUID NOT NULL,
    CONSTRAINT fk_catalog_entity_non_inv_catalog FOREIGN KEY (catalog_entity_id) REFERENCES catalog_entity(id) ON DELETE CASCADE,
    CONSTRAINT fk_catalog_entity_non_inv_product FOREIGN KEY (non_inventory_products_id) REFERENCES non_inventory_product(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS approval_request (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS cost_tier (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS guardrail_policy (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS item_cost (
    item_cost_id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS item_cost_audit (
    audit_id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS location_price_override (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS price_book (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS price_book_rule (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS product_msrp (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS product_replacement (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS supplier_item_cost (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS uom_conversion (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS competitorxreference (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS oemxreference (
    id UUID PRIMARY KEY
);
