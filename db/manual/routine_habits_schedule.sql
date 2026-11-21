-- Routine timeline (US-06) and personal routines (US-08): each habit's plan inside one routine.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application adds these
-- same columns on start; this script is only needed if that setting is ever turned off.
-- It only adds three nullable columns. No row is changed or deleted, and no time or day is
-- invented for existing habits: NULL means "any time" and "not tied to a day".
-- The previous release ignores the columns and leaves their values alone, so rolling back
-- needs no database step. Safe to run more than once.

ALTER TABLE routine_habits ADD COLUMN IF NOT EXISTS start_time TIME(0);
ALTER TABLE routine_habits ADD COLUMN IF NOT EXISTS repeat_days VARCHAR(27);
ALTER TABLE routine_habits ADD COLUMN IF NOT EXISTS notes VARCHAR(500);
