-- pos-catalog UUID v7 migration (forward-only, deterministic)
-- Applies only when pre-UUID numeric/string identifiers still exist.

-- product
ALTER TABLE IF EXISTS product ADD COLUMN IF NOT EXISTS id_new UUID;
ALTER TABLE IF EXISTS product_images ADD COLUMN IF NOT EXISTS product_id_new UUID;
ALTER TABLE IF EXISTS product_images DROP CONSTRAINT IF EXISTS product_images_product_id_fkey;
ALTER TABLE IF EXISTS product_images DROP COLUMN IF EXISTS product_id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'product_images' AND column_name = 'product_id_new'
    ) THEN
        ALTER TABLE product_images RENAME COLUMN product_id_new TO product_id;
    END IF;
END $$;
ALTER TABLE IF EXISTS product DROP CONSTRAINT IF EXISTS product_pkey;
ALTER TABLE IF EXISTS product DROP COLUMN IF EXISTS id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'product' AND column_name = 'id_new'
    ) THEN
        ALTER TABLE product RENAME COLUMN id_new TO id;
    END IF;
END $$;
ALTER TABLE IF EXISTS product ADD PRIMARY KEY (id);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'product_images')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'product')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'product_images_product_id_fkey') THEN
        ALTER TABLE product_images
            ADD CONSTRAINT product_images_product_id_fkey FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE;
    END IF;
END $$;

-- service
ALTER TABLE IF EXISTS service ADD COLUMN IF NOT EXISTS id_new UUID;
ALTER TABLE IF EXISTS service DROP CONSTRAINT IF EXISTS service_pkey;
ALTER TABLE IF EXISTS service DROP COLUMN IF EXISTS id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'service' AND column_name = 'id_new'
    ) THEN
        ALTER TABLE service RENAME COLUMN id_new TO id;
    END IF;
END $$;
ALTER TABLE IF EXISTS service ADD PRIMARY KEY (id);

-- non_inventory_product
ALTER TABLE IF EXISTS non_inventory_product ADD COLUMN IF NOT EXISTS id_new UUID;
ALTER TABLE IF EXISTS non_inventory_product DROP CONSTRAINT IF EXISTS non_inventory_product_pkey;
ALTER TABLE IF EXISTS non_inventory_product DROP COLUMN IF EXISTS id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'non_inventory_product' AND column_name = 'id_new'
    ) THEN
        ALTER TABLE non_inventory_product RENAME COLUMN id_new TO id;
    END IF;
END $$;
ALTER TABLE IF EXISTS non_inventory_product ADD PRIMARY KEY (id);

-- catalog_entity
ALTER TABLE IF EXISTS catalog_entity ADD COLUMN IF NOT EXISTS id_new UUID;
ALTER TABLE IF EXISTS catalog_entity DROP CONSTRAINT IF EXISTS catalog_entity_pkey;
ALTER TABLE IF EXISTS catalog_entity DROP COLUMN IF EXISTS id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'catalog_entity' AND column_name = 'id_new'
    ) THEN
        ALTER TABLE catalog_entity RENAME COLUMN id_new TO id;
    END IF;
END $$;
ALTER TABLE IF EXISTS catalog_entity ADD PRIMARY KEY (id);

-- category/product.category_id
ALTER TABLE IF EXISTS category ADD COLUMN IF NOT EXISTS id_new UUID;
ALTER TABLE IF EXISTS product ADD COLUMN IF NOT EXISTS category_id_new UUID;
ALTER TABLE IF EXISTS product DROP COLUMN IF EXISTS category_id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'product' AND column_name = 'category_id_new'
    ) THEN
        ALTER TABLE product RENAME COLUMN category_id_new TO category_id;
    END IF;
END $$;
ALTER TABLE IF EXISTS category DROP CONSTRAINT IF EXISTS category_pkey;
ALTER TABLE IF EXISTS category DROP COLUMN IF EXISTS id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'category' AND column_name = 'id_new'
    ) THEN
        ALTER TABLE category RENAME COLUMN id_new TO id;
    END IF;
END $$;
ALTER TABLE IF EXISTS category ADD PRIMARY KEY (id);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'product')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'category')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'product_category_id_fkey') THEN
        ALTER TABLE product
            ADD CONSTRAINT product_category_id_fkey FOREIGN KEY (category_id) REFERENCES category(id);
    END IF;
END $$;

-- subcategory/product.subcategory_id
ALTER TABLE IF EXISTS subcategory ADD COLUMN IF NOT EXISTS id_new UUID;
ALTER TABLE IF EXISTS product ADD COLUMN IF NOT EXISTS subcategory_id_new UUID;
ALTER TABLE IF EXISTS product DROP COLUMN IF EXISTS subcategory_id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'product' AND column_name = 'subcategory_id_new'
    ) THEN
        ALTER TABLE product RENAME COLUMN subcategory_id_new TO subcategory_id;
    END IF;
END $$;
ALTER TABLE IF EXISTS subcategory DROP CONSTRAINT IF EXISTS subcategory_pkey;
ALTER TABLE IF EXISTS subcategory DROP COLUMN IF EXISTS id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'subcategory' AND column_name = 'id_new'
    ) THEN
        ALTER TABLE subcategory RENAME COLUMN id_new TO id;
    END IF;
END $$;
ALTER TABLE IF EXISTS subcategory ADD PRIMARY KEY (id);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'product')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'subcategory')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'product_subcategory_id_fkey') THEN
        ALTER TABLE product
            ADD CONSTRAINT product_subcategory_id_fkey FOREIGN KEY (subcategory_id) REFERENCES subcategory(id);
    END IF;
END $$;

-- dimensions
ALTER TABLE IF EXISTS dimensions ADD COLUMN IF NOT EXISTS id_new UUID;
ALTER TABLE IF EXISTS dimensions DROP CONSTRAINT IF EXISTS dimensions_pkey;
ALTER TABLE IF EXISTS dimensions DROP COLUMN IF EXISTS id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'dimensions' AND column_name = 'id_new'
    ) THEN
        ALTER TABLE dimensions RENAME COLUMN id_new TO id;
    END IF;
END $$;
ALTER TABLE IF EXISTS dimensions ADD PRIMARY KEY (id);

-- oemxreference
ALTER TABLE IF EXISTS oemxreference ADD COLUMN IF NOT EXISTS id_new UUID;
ALTER TABLE IF EXISTS oemxreference ADD COLUMN IF NOT EXISTS part_id_new UUID;
ALTER TABLE IF EXISTS oemxreference ADD COLUMN IF NOT EXISTS oem_part_id_new UUID;
ALTER TABLE IF EXISTS oemxreference DROP COLUMN IF EXISTS part_id;
ALTER TABLE IF EXISTS oemxreference DROP COLUMN IF EXISTS oem_part_id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'oemxreference' AND column_name = 'part_id_new'
    ) THEN
        ALTER TABLE oemxreference RENAME COLUMN part_id_new TO part_id;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'oemxreference' AND column_name = 'oem_part_id_new'
    ) THEN
        ALTER TABLE oemxreference RENAME COLUMN oem_part_id_new TO oem_part_id;
    END IF;
END $$;
ALTER TABLE IF EXISTS oemxreference DROP CONSTRAINT IF EXISTS oemxreference_pkey;
ALTER TABLE IF EXISTS oemxreference DROP COLUMN IF EXISTS id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'oemxreference' AND column_name = 'id_new'
    ) THEN
        ALTER TABLE oemxreference RENAME COLUMN id_new TO id;
    END IF;
END $$;
ALTER TABLE IF EXISTS oemxreference ADD PRIMARY KEY (id);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'oemxreference')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'product')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'oemxreference_part_id_fkey') THEN
        ALTER TABLE oemxreference
            ADD CONSTRAINT oemxreference_part_id_fkey FOREIGN KEY (part_id) REFERENCES product(id);
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'oemxreference')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'product')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'oemxreference_oem_part_id_fkey') THEN
        ALTER TABLE oemxreference
            ADD CONSTRAINT oemxreference_oem_part_id_fkey FOREIGN KEY (oem_part_id) REFERENCES product(id);
    END IF;
END $$;

-- competitorxreference
ALTER TABLE IF EXISTS competitorxreference ADD COLUMN IF NOT EXISTS id_new UUID;
ALTER TABLE IF EXISTS competitorxreference ADD COLUMN IF NOT EXISTS part_id_new UUID;
ALTER TABLE IF EXISTS competitorxreference ADD COLUMN IF NOT EXISTS competitor_part_id_new UUID;
ALTER TABLE IF EXISTS competitorxreference DROP COLUMN IF EXISTS part_id;
ALTER TABLE IF EXISTS competitorxreference DROP COLUMN IF EXISTS competitor_part_id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'competitorxreference' AND column_name = 'part_id_new'
    ) THEN
        ALTER TABLE competitorxreference RENAME COLUMN part_id_new TO part_id;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'competitorxreference' AND column_name = 'competitor_part_id_new'
    ) THEN
        ALTER TABLE competitorxreference RENAME COLUMN competitor_part_id_new TO competitor_part_id;
    END IF;
END $$;
ALTER TABLE IF EXISTS competitorxreference DROP CONSTRAINT IF EXISTS competitorxreference_pkey;
ALTER TABLE IF EXISTS competitorxreference DROP COLUMN IF EXISTS id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'competitorxreference' AND column_name = 'id_new'
    ) THEN
        ALTER TABLE competitorxreference RENAME COLUMN id_new TO id;
    END IF;
END $$;
ALTER TABLE IF EXISTS competitorxreference ADD PRIMARY KEY (id);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'competitorxreference')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'product')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'competitorxreference_part_id_fkey') THEN
        ALTER TABLE competitorxreference
            ADD CONSTRAINT competitorxreference_part_id_fkey FOREIGN KEY (part_id) REFERENCES product(id);
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'competitorxreference')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'product')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'competitorxreference_competitor_part_id_fkey') THEN
        ALTER TABLE competitorxreference
            ADD CONSTRAINT competitorxreference_competitor_part_id_fkey FOREIGN KEY (competitor_part_id) REFERENCES product(id);
    END IF;
END $$;
