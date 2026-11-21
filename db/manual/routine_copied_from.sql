-- Creator Statistics (US-12): a routine saved through Copy or Adapt & copy remembers the routine
-- it was copied from, so a creator can see how often each published routine has been copied.
--
-- NOT needed by hand: on every start the application adds the column, its foreign key and its
-- index (Hibernate ddl-auto=update) and then the check below (RoutineCopyConstraints), before the
-- server takes requests. Running this script first is also safe; the application then finds
-- everything in place. Every step is safe to run more than once.
--
-- What changes, all additive:
--   1. routines.copied_from_id, nullable. Every existing routine keeps NULL: copies made before
--      US-12 never recorded their source, and none is guessed (not from titles, not from content).
--      Copy counts start at 0 and grow from the deploy on.
--   2. fk_routines_copied_from -> routines(id) ON DELETE SET NULL. Routines are only soft-deleted,
--      so the link stays with deleted routines; if a source row were ever removed for real, its
--      copies would lose only the link. No CASCADE.
--   3. idx_routines_copied_from, for counting the copies of a routine.
--   4. chk_routines_copied_from_not_self: a routine is never its own source. Added NOT VALID (new
--      rows are checked at once), then validated; every existing row passes (all NULL).
--
-- No row is inserted, updated or deleted.
--
-- Locks: the transaction briefly locks routines (the foreign key points at the same table).
-- SET LOCAL lock_timeout makes it give up after 5 seconds of waiting for another session instead of
-- holding up the application; PostgreSQL then rolls the whole transaction back and the script can
-- simply be run again.
--
-- Rollback: the previous release keeps working with all of this in place: it never names the
-- column, so its copies get NULL (those copies are then not counted once US-12 is back). Going back
-- needs no database step. To remove it all for good:
--   ALTER TABLE routines DROP CONSTRAINT IF EXISTS chk_routines_copied_from_not_self;
--   DROP INDEX IF EXISTS idx_routines_copied_from;
--   ALTER TABLE routines DROP COLUMN IF EXISTS copied_from_id;  -- also drops its foreign key

BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE routines ADD COLUMN IF NOT EXISTS copied_from_id bigint;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.routines'::regclass AND conname = 'fk_routines_copied_from'
    ) THEN
        ALTER TABLE routines ADD CONSTRAINT fk_routines_copied_from
            FOREIGN KEY (copied_from_id) REFERENCES routines (id) ON DELETE SET NULL;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_routines_copied_from ON routines (copied_from_id);

-- Same as RoutineCopyConstraints.NOT_SELF_CHECK.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.routines'::regclass AND conname = 'chk_routines_copied_from_not_self'
    ) THEN
        ALTER TABLE public.routines ADD CONSTRAINT chk_routines_copied_from_not_self
            CHECK (copied_from_id IS NULL OR copied_from_id <> id) NOT VALID;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.routines'::regclass AND conname = 'chk_routines_copied_from_not_self'
          AND NOT convalidated
    ) THEN
        BEGIN
            ALTER TABLE public.routines VALIDATE CONSTRAINT chk_routines_copied_from_not_self;
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING 'chk_routines_copied_from_not_self left NOT VALID: a routine names itself as its source';
        END;
    END IF;
END
$$;

COMMIT;

-- Check afterwards (read only). Expected: the column (bigint, nullable), the foreign key with
-- confdeltype 'n' (SET NULL), the index, the check validated, and every existing routine still NULL.
-- SELECT column_name, data_type, is_nullable FROM information_schema.columns
--     WHERE table_schema = 'public' AND table_name = 'routines' AND column_name = 'copied_from_id';
-- SELECT conname, contype, confdeltype, convalidated, pg_get_constraintdef(oid) FROM pg_constraint
--     WHERE conrelid = 'routines'::regclass AND conname IN ('fk_routines_copied_from', 'chk_routines_copied_from_not_self');
-- SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'routines' AND indexname = 'idx_routines_copied_from';
-- SELECT COUNT(*) AS routines, COUNT(copied_from_id) AS with_source FROM routines;
