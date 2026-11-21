-- Daily Habit Tracking (US-20): habit statuses, stopped runs and per-day expiry.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application makes
-- these same changes on start; this script is only needed if that setting is ever turned off.
-- Every change only adds: nullable columns and one unique constraint. No row is changed or deleted.
-- Rows written before this release keep NULL in the new columns and are read from their timestamps.
-- The previous release runs unchanged on the new schema, so rolling back needs no database step.

-- Step 1: read-only check. If it returns rows, the unique constraint cannot be added
-- (Hibernate would skip it quietly); resolve those rows by hand first.
SELECT routine_completion_id, routine_habit_id, count(*) AS records, array_agg(id ORDER BY id) AS ids
FROM habit_completions
WHERE routine_completion_id IS NOT NULL AND routine_habit_id IS NOT NULL
GROUP BY routine_completion_id, routine_habit_id
HAVING count(*) > 1;

-- Step 2: the changes. Safe to run more than once.
BEGIN;

ALTER TABLE habit_completions
    ADD COLUMN IF NOT EXISTS status VARCHAR(20)
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'SKIPPED', 'POSTPONED'));

ALTER TABLE routine_completions ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE routine_completions ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE habit_completions DROP CONSTRAINT IF EXISTS uk_habit_completions_run_step;
ALTER TABLE habit_completions
    ADD CONSTRAINT uk_habit_completions_run_step UNIQUE (routine_completion_id, routine_habit_id);

COMMIT;
