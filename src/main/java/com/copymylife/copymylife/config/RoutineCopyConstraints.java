package com.copymylife.copymylife.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// US-12: Hibernate adds routines.copied_from_id with its foreign key (ON DELETE SET NULL) and
// index, but not a check, so a routine that names itself as its source is refused here, once every
// bean is ready and before the server takes requests (as db/manual/routine_copied_from.sql does by
// hand). Added NOT VALID, then validated; no row is changed. Cycles need a routine's source to
// change later, which nothing does (the column is not updatable).
@Component
public class RoutineCopyConstraints implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RoutineCopyConstraints.class);

    static final String NOT_SELF_CHECK = """
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
            $$
            """;

    private final JdbcTemplate jdbcTemplate;

    public RoutineCopyConstraints(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Copies are still saved without the check; it only guards against a hand-made mistake.
    @Override
    public void afterSingletonsInstantiated() {
        try {
            jdbcTemplate.execute(NOT_SELF_CHECK);
        } catch (DataAccessException exception) {
            log.warn("chk_routines_copied_from_not_self could not be checked: {}",
                    exception.getMostSpecificCause().getMessage());
        }
    }
}
