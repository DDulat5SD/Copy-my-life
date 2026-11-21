package com.copymylife.copymylife.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// US-24: what Hibernate's ddl-auto=update cannot do for reports, run on every start as
// db/manual/report_content_targets.sql does by hand. Unlike FriendshipPairIndex this runs once
// every bean is ready (Hibernate has added the new columns) but before the server takes requests.
//  - The older type check allows only USER and COMMENT; it is replaced by one that allows all five.
//  - Each report names only the target its type uses (added NOT VALID, then validated if it can be).
//  - One active (OPEN or IN_REVIEW) report per reporter and target: one partial unique index per type.
// No row is changed or removed. An index that older rows already break is left out, and until every
// safeguard is there (missingSafeguards, read from PostgreSQL's catalog) ReportService refuses new
// reports with 503: the server starts, but reporting stays off and the log says why with an error.
@Component
public class ReportConstraints implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ReportConstraints.class);

    record ActiveIndex(String name, String type, String column) {
    }

    static final List<ActiveIndex> ACTIVE_INDEXES = List.of(
            new ActiveIndex("uk_reports_active_user", "USER", "reported_user_id"),
            new ActiveIndex("uk_reports_active_comment", "COMMENT", "reported_comment_id"),
            new ActiveIndex("uk_reports_active_routine", "ROUTINE", "reported_routine_id"),
            new ActiveIndex("uk_reports_active_post", "POST", "reported_post_id"),
            new ActiveIndex("uk_reports_active_post_comment", "POST_COMMENT", "reported_post_comment_id")
    );

    // Dropping and adding happen in one transaction, so there is never a moment without a type check.
    static final String WIDEN_TYPE_CHECK = """
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
            $$
            """;

    // The target of a type may be empty only once its content is deleted (ON DELETE SET NULL).
    static final String TARGET_CHECK = """
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
            $$
            """;

    static String createActiveIndexIfSafe(ActiveIndex index) {
        return """
                DO $$
                BEGIN
                    IF to_regclass('public.%1$s') IS NULL THEN
                        IF EXISTS (
                            SELECT 1
                            FROM public.reports
                            WHERE type = '%2$s' AND status IN ('OPEN', 'IN_REVIEW') AND %3$s IS NOT NULL
                            GROUP BY reporter_id, %3$s
                            HAVING COUNT(*) > 1
                        ) THEN
                            RAISE WARNING '%1$s not created: a reporter has more than one active %2$s report for one target';
                        ELSE
                            CREATE UNIQUE INDEX %1$s ON public.reports (reporter_id, %3$s)
                                WHERE type = '%2$s' AND status IN ('OPEN', 'IN_REVIEW');
                        END IF;
                    END IF;
                END
                $$
                """.formatted(index.name(), index.type(), index.column());
    }

    // A unique index counts only once PostgreSQL may use it (a failed build leaves one that is not valid).
    private static final String USABLE_INDEXES = """
            SELECT c.relname
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indexrelid
            WHERE i.indrelid = 'public.reports'::regclass
              AND i.indisunique AND i.indisvalid AND i.indisready AND i.indpred IS NOT NULL
            """;

    private static final String TYPE_CHECKS = """
            SELECT pg_get_constraintdef(con.oid)
            FROM pg_constraint con
            JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
            WHERE con.conrelid = 'public.reports'::regclass
              AND con.contype = 'c'
              AND cardinality(con.conkey) = 1
              AND att.attname = 'type'
            """;

    private static final String TARGET_CHECK_PRESENT = """
            SELECT EXISTS (
                SELECT 1 FROM pg_constraint
                WHERE conrelid = 'public.reports'::regclass AND conname = 'chk_reports_target'
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public ReportConstraints(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        apply("chk_reports_type", WIDEN_TYPE_CHECK);
        apply("chk_reports_target", TARGET_CHECK);
        ACTIVE_INDEXES.forEach(index -> apply(index.name(), createActiveIndexIfSafe(index)));

        List<String> missing = missingSafeguards();
        if (missing.isEmpty()) {
            log.info("Report safeguards in place: type check, target check and {} active-report indexes", ACTIVE_INDEXES.size());
        } else {
            log.error("Reporting is OFF until these exist: {}. Run the conflict report in db/manual/report_content_targets.sql, "
                    + "close the duplicates it lists, then run that script or restart", missing);
        }
    }

    // Empty when reports may be written: the five active-report indexes are usable, the type check
    // allows all five types and the target check is there. Asked again on every new report.
    public List<String> missingSafeguards() {
        List<String> missing = new ArrayList<>();

        try {
            List<String> usable = jdbcTemplate.queryForList(USABLE_INDEXES, String.class);
            ACTIVE_INDEXES.stream()
                    .map(ActiveIndex::name)
                    .filter(name -> !usable.contains(name))
                    .forEach(missing::add);

            List<String> typeChecks = jdbcTemplate.queryForList(TYPE_CHECKS, String.class);
            boolean typesWidened = !typeChecks.isEmpty() && typeChecks.stream().allMatch(definition ->
                    ACTIVE_INDEXES.stream().allMatch(index -> definition.contains("'" + index.type() + "'")));
            if (!typesWidened) {
                missing.add("chk_reports_type");
            }

            if (!Boolean.TRUE.equals(jdbcTemplate.queryForObject(TARGET_CHECK_PRESENT, Boolean.class))) {
                missing.add("chk_reports_target");
            }
        } catch (DataAccessException exception) {
            log.error("Report safeguards could not be read: {}", exception.getMostSpecificCause().getMessage());
            missing.add("catalog");
        }

        return missing;
    }

    // One step that fails leaves the others, and the reports that already worked, as they were.
    private void apply(String name, String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (DataAccessException exception) {
            log.warn("{} could not be checked: {}", name, exception.getMostSpecificCause().getMessage());
        }
    }
}
