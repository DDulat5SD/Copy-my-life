-- Admin Content Management (US-25): administrators hide routines, posts and public profiles and
-- manage the categories routines are filed under.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application adds these
-- same columns and the table on start; this script is only needed if that setting is ever
-- turned off. It only adds three nullable columns and one new table. No existing row is changed
-- or deleted: every routine, post and profile keeps NULL, which means "not hidden". On its first
-- start the application (CategorySeeder) fills an empty categories table with the six categories
-- the site offered before; the INSERT below does the same, and only when the table is empty.
--
-- Rollback: the previous release ignores the columns and the table, so going back needs no
-- database step. Anything hidden meanwhile would then show again, as before US-25. To remove
-- them for good (only after going back): DROP TABLE categories, and ALTER TABLE ... DROP COLUMN
-- for the three columns.
-- Safe to run more than once.

ALTER TABLE routines ADD COLUMN IF NOT EXISTS hidden_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE posts ADD COLUMN IF NOT EXISTS hidden_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_hidden_at TIMESTAMP(6) WITH TIME ZONE;

CREATE TABLE IF NOT EXISTS categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(40) NOT NULL,
    name_key VARCHAR(40) NOT NULL,
    position INTEGER NOT NULL,
    CONSTRAINT uk_categories_name_key UNIQUE (name_key)
);

INSERT INTO categories (name, name_key, position)
SELECT name, lower(name), position
FROM (VALUES ('Morning', 0), ('Fitness', 1), ('Study', 2), ('Wellness', 3), ('Productivity', 4), ('Creators', 5))
         AS defaults(name, position)
WHERE NOT EXISTS (SELECT 1 FROM categories);
