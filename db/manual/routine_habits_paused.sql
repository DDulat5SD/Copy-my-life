-- Manage Habits (US-09): pause a habit in one routine.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application adds this
-- same column on start; this script is only needed if that setting is ever turned off.
-- It only adds one nullable column. No row is changed or deleted: every existing routine habit
-- keeps NULL, which means "active". The previous release ignores the column, so rolling back
-- needs no database step (habits paused meanwhile simply count as active again).
-- Safe to run more than once.

ALTER TABLE routine_habits ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP(6) WITH TIME ZONE;
