-- Report Content (US-24): routines, posts and post comments can be reported, and one person has
-- at most one active (OPEN or IN_REVIEW) report per piece of content.
--
-- NOT needed by hand: on every start the application adds the three columns and their foreign keys
-- (Hibernate ddl-auto=update) and then runs steps 2-4 below itself (ReportConstraints), before the
-- server takes requests. Running this script first is also safe; the application then finds
-- everything in place. Every step is safe to run more than once.
--
-- What changes, all additive except one constraint swap:
--   1. reports gets three nullable columns, reported_routine_id, reported_post_id and
--      reported_post_comment_id, each with a foreign key ON DELETE SET NULL and an index. Deleting a
--      post or a post comment (both are deleted for real) empties the link; the report, its reason and
--      its review history stay. Routines are only ever soft-deleted. No CASCADE is added anywhere.
--   2. The type check is replaced by a wider one. Postgres cannot widen a CHECK in place, so the old
--      one (chk_reports_type in production, reports_type_check where Hibernate created the table; it
--      allows only USER and COMMENT) is dropped and chk_reports_type with all five types is added in
--      the same transaction. Every existing row (USER or COMMENT) passes the new check.
--   3. chk_reports_target: a report names only the target its type uses. Added NOT VALID (new rows
--      are checked at once), then validated; if older rows break it, it stays NOT VALID with a warning.
--   4. One partial unique index per type on (reporter_id, <target column>) WHERE the report is OPEN or
--      IN_REVIEW. A RESOLVED or REJECTED report does not count, so content can be reported again once
--      an administrator has closed the earlier report. If older rows already hold two active reports
--      by one person for one target, that index is NOT created (warning) and NO row is changed:
--      run the conflict report at the end, let an administrator close the extra reports in the admin
--      portal (Reject), then run this script again. Until every index is there the application
--      starts but refuses every new report (503) and logs an ERROR naming what is missing; it reads
--      PostgreSQL's catalog again on each report, so reporting comes back as soon as this script has
--      made the last index (no restart needed).
--
-- No row is inserted, updated or deleted.
--
-- Locks: the transaction briefly locks reports and the tables its new foreign keys point to
-- (routines, posts, post_comments). SET LOCAL lock_timeout makes it give up after 5 seconds of
-- waiting for another session instead of holding up the application; PostgreSQL then rolls the
-- whole transaction back (nothing is left half done) and the script can simply be run again.
--
-- Rollback: the previous release keeps working with all of this in place (it only writes USER and
-- COMMENT reports, which every new constraint accepts), so going back needs no database step AS LONG
-- AS no ROUTINE, POST or POST_COMMENT report exists yet. The previous release cannot read those
-- types (its Report.Type has only USER and COMMENT): its admin report list and /reports/mine would
-- fail while such rows exist. Check first, read only:
--   SELECT type, COUNT(*) FROM reports GROUP BY type;
-- If there are any, roll back only after an administrator has closed them and they have been
-- exported and moved out of the table by a separate, reviewed step (never by this script).
-- To remove it all for good, only after deleting or archiving every ROUTINE, POST and POST_COMMENT
-- report (the old check below would refuse them):
--   DROP INDEX IF EXISTS uk_reports_active_user, uk_reports_active_comment, uk_reports_active_routine,
--       uk_reports_active_post, uk_reports_active_post_comment;
--   ALTER TABLE reports DROP CONSTRAINT IF EXISTS chk_reports_target;
--   ALTER TABLE reports DROP CONSTRAINT IF EXISTS chk_reports_type,
--       ADD CONSTRAINT chk_reports_type CHECK (type IN ('USER', 'COMMENT'));
--   ALTER TABLE reports DROP COLUMN IF EXISTS reported_routine_id, DROP COLUMN IF EXISTS reported_post_id,
--       DROP COLUMN IF EXISTS reported_post_comment_id;

BEGIN;
SET LOCAL lock_timeout = '5s';

-- 1. Columns, foreign keys and indexes (what Hibernate adds on start).
ALTER TABLE reports ADD COLUMN IF NOT EXISTS reported_routine_id bigint;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS reported_post_id bigint;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS reported_post_comment_id bigint;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.reports'::regclass AND conname = 'fk_reports_routine') THEN
        ALTER TABLE reports ADD CONSTRAINT fk_reports_routine
            FOREIGN KEY (reported_routine_id) REFERENCES routines (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.reports'::regclass AND conname = 'fk_reports_post') THEN
        ALTER TABLE reports ADD CONSTRAINT fk_reports_post
            FOREIGN KEY (reported_post_id) REFERENCES posts (id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.reports'::regclass AND conname = 'fk_reports_post_comment') THEN
        ALTER TABLE reports ADD CONSTRAINT fk_reports_post_comment
            FOREIGN KEY (reported_post_comment_id) REFERENCES post_comments (id) ON DELETE SET NULL;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_reports_routine ON reports (reported_routine_id);
CREATE INDEX IF NOT EXISTS idx_reports_post ON reports (reported_post_id);
CREATE INDEX IF NOT EXISTS idx_reports_post_comment ON reports (reported_post_comment_id);

-- 2. The type check allows all five types (ReportConstraints.WIDEN_TYPE_CHECK).
DO $$
DECLARE
    old_check record;
BEGIN
    FOR old_check IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
        WHERE con.conrelid = 'public.reports'::regclass
          AND con.contype = 'c'
          AND cardinality(con.conkey) = 1
          AND att.attname = 'type'
          AND pg_get_constraintdef(con.oid) NOT LIKE '%''POST_COMMENT''%'
    LOOP
        EXECUTE format('ALTER TABLE public.reports DROP CONSTRAINT %I', old_check.conname);
    END LOOP;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint con
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
        WHERE con.conrelid = 'public.reports'::regclass
          AND con.contype = 'c'
          AND cardinality(con.conkey) = 1
          AND att.attname = 'type'
    ) THEN
        ALTER TABLE public.reports ADD CONSTRAINT chk_reports_type
            CHECK (type IN ('USER', 'COMMENT', 'ROUTINE', 'POST', 'POST_COMMENT'));
    END IF;
END
$$;

-- 3. One target per type (ReportConstraints.TARGET_CHECK).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.reports'::regclass AND conname = 'chk_reports_target'
    ) THEN
        ALTER TABLE public.reports ADD CONSTRAINT chk_reports_target CHECK (
            CASE type
                WHEN 'USER' THEN num_nonnulls(reported_comment_id, reported_routine_id, reported_post_id, reported_post_comment_id) = 0
                WHEN 'COMMENT' THEN num_nonnulls(reported_routine_id, reported_post_id, reported_post_comment_id) = 0
                WHEN 'ROUTINE' THEN num_nonnulls(reported_comment_id, reported_post_id, reported_post_comment_id) = 0
                WHEN 'POST' THEN num_nonnulls(reported_comment_id, reported_routine_id, reported_post_comment_id) = 0
                WHEN 'POST_COMMENT' THEN num_nonnulls(reported_comment_id, reported_routine_id, reported_post_id) = 0
                ELSE false
            END
        ) NOT VALID;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.reports'::regclass AND conname = 'chk_reports_target' AND NOT convalidated
    ) THEN
        BEGIN
            ALTER TABLE public.reports VALIDATE CONSTRAINT chk_reports_target;
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING 'chk_reports_target left NOT VALID: some older reports name a second target';
        END;
    END IF;
END
$$;

-- 4. One active report per reporter and target (ReportConstraints.createActiveIndexIfSafe).
DO $$
BEGIN
    IF to_regclass('public.uk_reports_active_user') IS NULL THEN
        IF EXISTS (
            SELECT 1
            FROM public.reports
            WHERE type = 'USER' AND status IN ('OPEN', 'IN_REVIEW') AND reported_user_id IS NOT NULL
            GROUP BY reporter_id, reported_user_id
            HAVING COUNT(*) > 1
        ) THEN
            RAISE WARNING 'uk_reports_active_user not created: a reporter has more than one active USER report for one target';
        ELSE
            CREATE UNIQUE INDEX uk_reports_active_user ON public.reports (reporter_id, reported_user_id)
                WHERE type = 'USER' AND status IN ('OPEN', 'IN_REVIEW');
        END IF;
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('public.uk_reports_active_comment') IS NULL THEN
        IF EXISTS (
            SELECT 1
            FROM public.reports
            WHERE type = 'COMMENT' AND status IN ('OPEN', 'IN_REVIEW') AND reported_comment_id IS NOT NULL
            GROUP BY reporter_id, reported_comment_id
            HAVING COUNT(*) > 1
        ) THEN
            RAISE WARNING 'uk_reports_active_comment not created: a reporter has more than one active COMMENT report for one target';
        ELSE
            CREATE UNIQUE INDEX uk_reports_active_comment ON public.reports (reporter_id, reported_comment_id)
                WHERE type = 'COMMENT' AND status IN ('OPEN', 'IN_REVIEW');
        END IF;
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('public.uk_reports_active_routine') IS NULL THEN
        IF EXISTS (
            SELECT 1
            FROM public.reports
            WHERE type = 'ROUTINE' AND status IN ('OPEN', 'IN_REVIEW') AND reported_routine_id IS NOT NULL
            GROUP BY reporter_id, reported_routine_id
            HAVING COUNT(*) > 1
        ) THEN
            RAISE WARNING 'uk_reports_active_routine not created: a reporter has more than one active ROUTINE report for one target';
        ELSE
            CREATE UNIQUE INDEX uk_reports_active_routine ON public.reports (reporter_id, reported_routine_id)
                WHERE type = 'ROUTINE' AND status IN ('OPEN', 'IN_REVIEW');
        END IF;
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('public.uk_reports_active_post') IS NULL THEN
        IF EXISTS (
            SELECT 1
            FROM public.reports
            WHERE type = 'POST' AND status IN ('OPEN', 'IN_REVIEW') AND reported_post_id IS NOT NULL
            GROUP BY reporter_id, reported_post_id
            HAVING COUNT(*) > 1
        ) THEN
            RAISE WARNING 'uk_reports_active_post not created: a reporter has more than one active POST report for one target';
        ELSE
            CREATE UNIQUE INDEX uk_reports_active_post ON public.reports (reporter_id, reported_post_id)
                WHERE type = 'POST' AND status IN ('OPEN', 'IN_REVIEW');
        END IF;
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('public.uk_reports_active_post_comment') IS NULL THEN
        IF EXISTS (
            SELECT 1
            FROM public.reports
            WHERE type = 'POST_COMMENT' AND status IN ('OPEN', 'IN_REVIEW') AND reported_post_comment_id IS NOT NULL
            GROUP BY reporter_id, reported_post_comment_id
            HAVING COUNT(*) > 1
        ) THEN
            RAISE WARNING 'uk_reports_active_post_comment not created: a reporter has more than one active POST_COMMENT report for one target';
        ELSE
            CREATE UNIQUE INDEX uk_reports_active_post_comment ON public.reports (reporter_id, reported_post_comment_id)
                WHERE type = 'POST_COMMENT' AND status IN ('OPEN', 'IN_REVIEW');
        END IF;
    END IF;
END
$$;

COMMIT;

-- Conflict report BEFORE the migration (read only; uses only columns the pre-US-24 table has, where
-- only USER and COMMENT reports exist). Each row is one reporter with more than one active report for
-- one target, which would stop an index above. Deploy only when it returns no rows; otherwise keep
-- the oldest (first id) and let an administrator Reject the others in the admin portal first.
--
-- SELECT type, reporter_id, COALESCE(reported_comment_id, reported_user_id) AS target_id,
--        COUNT(*) AS active_reports, array_agg(id ORDER BY id) AS report_ids
-- FROM reports
-- WHERE status IN ('OPEN', 'IN_REVIEW')
-- GROUP BY type, reporter_id, COALESCE(reported_comment_id, reported_user_id)
-- HAVING COUNT(*) > 1
-- ORDER BY type, reporter_id;
--
-- Conflict report AFTER the migration (read only; needs the three new columns), for when the script
-- or the application warned that an index was left out:
--
-- SELECT type, reporter_id,
--        COALESCE(reported_post_comment_id, reported_post_id, reported_routine_id, reported_comment_id, reported_user_id) AS target_id,
--        COUNT(*) AS active_reports, array_agg(id ORDER BY id) AS report_ids
-- FROM reports
-- WHERE status IN ('OPEN', 'IN_REVIEW')
-- GROUP BY type, reporter_id,
--          COALESCE(reported_post_comment_id, reported_post_id, reported_routine_id, reported_comment_id, reported_user_id)
-- HAVING COUNT(*) > 1
-- ORDER BY type, reporter_id;
--
-- Check afterwards (read only):
-- SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'reports' ORDER BY indexname;
-- SELECT conname, convalidated, pg_get_constraintdef(oid) FROM pg_constraint
--     WHERE conrelid = 'reports'::regclass ORDER BY conname;
