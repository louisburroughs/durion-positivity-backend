-- labor_rate.currency was declared char(3) in V4 while every other currency column in this module
-- (product_base_price, location_price_override) is varchar(3), and LaborRate maps it with the same
-- @Column(length = 3) String as its siblings. Hibernate's `ddl-auto: validate` reads the char(3)
-- back as bpchar and refuses to start:
--
--   Schema validation: wrong column type encountered in column [currency] in table [labor_rate];
--   found [bpchar (Types#CHAR)], but expecting [varchar(3) (Types#VARCHAR)]
--
-- so pos-price failed to boot on alpha. Bring the column in line with the mapping and with the
-- rest of the module. Postgres trims trailing blanks on the bpchar -> varchar cast; ISO 4217 codes
-- are exactly three characters, so no stored value changes.

SET TIME ZONE 'UTC';

ALTER TABLE labor_rate ALTER COLUMN currency TYPE varchar(3);
