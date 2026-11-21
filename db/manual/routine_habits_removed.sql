-- Manage Habits and Activities (US-09): deleting a habit that has completion history.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application adds this
-- same column on start; this script is only needed if that setting is ever turned off.
-- The change only adds one nullable column. No row is changed or deleted.
-- Rows written before this release keep NULL, which means the habit is still in its routine.
--
-- Rollback: the previous release runs on this schema unchanged, but it does not know the
-- column, so habits deleted after this release would show up in their routines again
-- (with the negative position they were given). Undo those deletions before rolling back,
-- or keep this release. The column itself can stay.

-- Step 1: read-only check of what this release has deleted so far.
SELECT routine_id, count(*) AS deleted_habits
FROM routine_habits
WHERE removed_at IS NOT NULL
GROUP BY routine_id;

-- Step 2: the change. Safe to run more than once.
ALTER TABLE routine_habits ADD COLUMN IF NOT EXISTS removed_at TIMESTAMP(6) WITH TIME ZONE;
