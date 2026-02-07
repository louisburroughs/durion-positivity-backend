-- pos-catalog UUID v7 Migration Scripts
-- ADR-0013: UUID v7 Identifier Strategy
-- Database: PostgreSQL and H2

-- ============================================
-- PRODUCT TABLE MIGRATION
-- ============================================

-- Step 1: Add new UUID column
ALTER TABLE product ADD COLUMN id_new UUID;

-- Step 2: Populate UUID v7 for existing records (application will handle this)
-- UPDATE product SET id_new = gen_random_uuid(); -- Temporary UUID v4 for existing data

-- Step 3: Update foreign key references (if any external references exist)
-- Note: product_images uses product_id as foreign key
ALTER TABLE product_images ADD COLUMN product_id_new UUID;
-- UPDATE product_images SET product_id_new = (SELECT id_new FROM product WHERE product.id = product_images.product_id);
ALTER TABLE product_images DROP CONSTRAINT IF EXISTS product_images_product_id_fkey;
ALTER TABLE product_images DROP COLUMN product_id;
ALTER TABLE product_images RENAME COLUMN product_id_new TO product_id;

-- Step 4: Drop old ID and set new UUID as primary key
ALTER TABLE product DROP CONSTRAINT IF EXISTS product_pkey;
ALTER TABLE product DROP COLUMN id;
ALTER TABLE product RENAME COLUMN id_new TO id;
ALTER TABLE product ADD PRIMARY KEY (id);

-- Step 5: Recreate foreign key constraint
ALTER TABLE product_images ADD CONSTRAINT product_images_product_id_fkey 
    FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE;

-- ============================================
-- SERVICE TABLE MIGRATION
-- ============================================

ALTER TABLE service ADD COLUMN id_new UUID;
-- UPDATE service SET id_new = gen_random_uuid();

-- Update CatalogEntity references if needed
-- (Handle in CatalogEntity migration)

ALTER TABLE service DROP CONSTRAINT IF EXISTS service_pkey;
ALTER TABLE service DROP COLUMN id;
ALTER TABLE service RENAME COLUMN id_new TO id;
ALTER TABLE service ADD PRIMARY KEY (id);

-- ============================================
-- NON_INVENTORY_PRODUCT TABLE MIGRATION
-- ============================================

ALTER TABLE non_inventory_product ADD COLUMN id_new UUID;
-- UPDATE non_inventory_product SET id_new = gen_random_uuid();

ALTER TABLE non_inventory_product DROP CONSTRAINT IF EXISTS non_inventory_product_pkey;
ALTER TABLE non_inventory_product DROP COLUMN id;
ALTER TABLE non_inventory_product RENAME COLUMN id_new TO id;
ALTER TABLE non_inventory_product ADD PRIMARY KEY (id);

-- ============================================
-- CATALOG_ENTITY TABLE MIGRATION
-- ============================================

ALTER TABLE catalog_entity ADD COLUMN id_new UUID;
-- UPDATE catalog_entity SET id_new = gen_random_uuid();

ALTER TABLE catalog_entity DROP CONSTRAINT IF EXISTS catalog_entity_pkey;
ALTER TABLE catalog_entity DROP COLUMN id;
ALTER TABLE catalog_entity RENAME COLUMN id_new TO id;
ALTER TABLE catalog_entity ADD PRIMARY KEY (id);

-- ============================================
-- CATEGORY TABLE MIGRATION
-- ============================================

ALTER TABLE category ADD COLUMN id_new UUID;
-- UPDATE category SET id_new = gen_random_uuid();

-- Update product foreign key references
ALTER TABLE product ADD COLUMN category_id_new UUID;
-- UPDATE product SET category_id_new = (SELECT id_new FROM category WHERE category.id = product.category_id);
ALTER TABLE product DROP COLUMN category_id;
ALTER TABLE product RENAME COLUMN category_id_new TO category_id;

ALTER TABLE category DROP CONSTRAINT IF EXISTS category_pkey;
ALTER TABLE category DROP COLUMN id;
ALTER TABLE category RENAME COLUMN id_new TO id;
ALTER TABLE category ADD PRIMARY KEY (id);

-- Recreate foreign key
ALTER TABLE product ADD CONSTRAINT product_category_id_fkey 
    FOREIGN KEY (category_id) REFERENCES category(id);

-- ============================================
-- SUBCATEGORY TABLE MIGRATION
-- ============================================

ALTER TABLE subcategory ADD COLUMN id_new UUID;
-- UPDATE subcategory SET id_new = gen_random_uuid();

-- Update product foreign key references
ALTER TABLE product ADD COLUMN subcategory_id_new UUID;
-- UPDATE product SET subcategory_id_new = (SELECT id_new FROM subcategory WHERE subcategory.id = product.subcategory_id);
ALTER TABLE product DROP COLUMN subcategory_id;
ALTER TABLE product RENAME COLUMN subcategory_id_new TO subcategory_id;

ALTER TABLE subcategory DROP CONSTRAINT IF EXISTS subcategory_pkey;
ALTER TABLE subcategory DROP COLUMN id;
ALTER TABLE subcategory RENAME COLUMN id_new TO id;
ALTER TABLE subcategory ADD PRIMARY KEY (id);

-- Recreate foreign key
ALTER TABLE product ADD CONSTRAINT product_subcategory_id_fkey 
    FOREIGN KEY (subcategory_id) REFERENCES subcategory(id);

-- ============================================
-- DIMENSIONS TABLE MIGRATION
-- ============================================

ALTER TABLE dimensions ADD COLUMN id_new UUID;
-- UPDATE dimensions SET id_new = gen_random_uuid();

ALTER TABLE dimensions DROP CONSTRAINT IF EXISTS dimensions_pkey;
ALTER TABLE dimensions DROP COLUMN id;
ALTER TABLE dimensions RENAME COLUMN id_new TO id;
ALTER TABLE dimensions ADD PRIMARY KEY (id);

-- ============================================
-- OEMXREFERENCE TABLE MIGRATION
-- ============================================

ALTER TABLE oemxreference ADD COLUMN id_new UUID;
-- UPDATE oemxreference SET id_new = gen_random_uuid();

-- Update part_id and oem_part_id references
ALTER TABLE oemxreference ADD COLUMN part_id_new UUID;
ALTER TABLE oemxreference ADD COLUMN oem_part_id_new UUID;
-- UPDATE oemxreference SET part_id_new = (SELECT id FROM product WHERE product.id = oemxreference.part_id);
-- UPDATE oemxreference SET oem_part_id_new = (SELECT id FROM product WHERE product.id = oemxreference.oem_part_id);
ALTER TABLE oemxreference DROP COLUMN part_id;
ALTER TABLE oemxreference DROP COLUMN oem_part_id;
ALTER TABLE oemxreference RENAME COLUMN part_id_new TO part_id;
ALTER TABLE oemxreference RENAME COLUMN oem_part_id_new TO oem_part_id;

ALTER TABLE oemxreference DROP CONSTRAINT IF EXISTS oemxreference_pkey;
ALTER TABLE oemxreference DROP COLUMN id;
ALTER TABLE oemxreference RENAME COLUMN id_new TO id;
ALTER TABLE oemxreference ADD PRIMARY KEY (id);

-- Recreate foreign keys
ALTER TABLE oemxreference ADD CONSTRAINT oemxreference_part_id_fkey 
    FOREIGN KEY (part_id) REFERENCES product(id);
ALTER TABLE oemxreference ADD CONSTRAINT oemxreference_oem_part_id_fkey 
    FOREIGN KEY (oem_part_id) REFERENCES product(id);

-- ============================================
-- COMPETITORXREFERENCE TABLE MIGRATION
-- ============================================

ALTER TABLE competitorxreference ADD COLUMN id_new UUID;
-- UPDATE competitorxreference SET id_new = gen_random_uuid();

-- Update part_id and competitor_part_id references
ALTER TABLE competitorxreference ADD COLUMN part_id_new UUID;
ALTER TABLE competitorxreference ADD COLUMN competitor_part_id_new UUID;
-- UPDATE competitorxreference SET part_id_new = (SELECT id FROM product WHERE product.id = competitorxreference.part_id);
-- UPDATE competitorxreference SET competitor_part_id_new = (SELECT id FROM product WHERE product.id = competitorxreference.competitor_part_id);
ALTER TABLE competitorxreference DROP COLUMN part_id;
ALTER TABLE competitorxreference DROP COLUMN competitor_part_id;
ALTER TABLE competitorxreference RENAME COLUMN part_id_new TO part_id;
ALTER TABLE competitorxreference RENAME COLUMN competitor_part_id_new TO competitor_part_id;

ALTER TABLE competitorxreference DROP CONSTRAINT IF EXISTS competitorxreference_pkey;
ALTER TABLE competitorxreference DROP COLUMN id;
ALTER TABLE competitorxreference RENAME COLUMN id_new TO id;
ALTER TABLE competitorxreference ADD PRIMARY KEY (id);

-- Recreate foreign keys
ALTER TABLE competitorxreference ADD CONSTRAINT competitorxreference_part_id_fkey 
    FOREIGN KEY (part_id) REFERENCES product(id);
ALTER TABLE competitorxreference ADD CONSTRAINT competitorxreference_competitor_part_id_fkey 
    FOREIGN KEY (competitor_part_id) REFERENCES product(id);

-- ============================================
-- VERIFICATION QUERIES
-- ============================================

-- Check that all tables have UUID primary keys
SELECT table_name, column_name, data_type 
FROM information_schema.columns 
WHERE table_schema = 'public' 
  AND column_name = 'id' 
  AND table_name IN ('product', 'service', 'non_inventory_product', 'catalog_entity', 
                     'category', 'subcategory', 'dimensions', 'oemxreference', 'competitorxreference');

-- Verify foreign key relationships
SELECT 
    tc.table_name, 
    kcu.column_name, 
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name 
FROM information_schema.table_constraints AS tc 
JOIN information_schema.key_column_usage AS kcu
  ON tc.constraint_name = kcu.constraint_name
  AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage AS ccu
  ON ccu.constraint_name = tc.constraint_name
  AND ccu.table_schema = tc.table_schema
WHERE tc.constraint_type = 'FOREIGN KEY' 
  AND tc.table_schema = 'public'
  AND tc.table_name LIKE '%catalog%' OR tc.table_name IN ('product', 'service', 'dimensions')
ORDER BY tc.table_name;
