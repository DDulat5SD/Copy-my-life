package com.copymylife.copymylife.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// US-29: one relationship per pair of users, whichever of the two asked. Hibernate cannot declare an
// index on an expression, so it is added here on start, as db/manual/friendships_pair_unique.sql
// does. It is left out, with a warning, while older rows still hold both directions of one pair,
// so no existing row is changed or removed; FriendshipService then still treats them as one.
@Component
public class FriendshipPairIndex implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FriendshipPairIndex.class);

    static final String CREATE_IF_SAFE = """
            DO $$
            BEGIN
                IF to_regclass('public.uk_friendships_pair') IS NULL THEN
                    IF EXISTS (
                        SELECT 1
                        FROM friendships
                        GROUP BY LEAST(sender_id, receiver_id), GREATEST(sender_id, receiver_id)
                        HAVING COUNT(*) > 1
                    ) THEN
                        RAISE WARNING 'uk_friendships_pair not created: some pairs of users have more than one row';
                    ELSE
                        CREATE UNIQUE INDEX uk_friendships_pair
                            ON friendships (LEAST(sender_id, receiver_id), GREATEST(sender_id, receiver_id));
                    END IF;
                END IF;
            END
            $$
            """;

    private final JdbcTemplate jdbcTemplate;

    public FriendshipPairIndex(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        jdbcTemplate.execute(CREATE_IF_SAFE);

        if (jdbcTemplate.queryForObject("SELECT to_regclass('public.uk_friendships_pair') IS NULL", Boolean.class)) {
            log.warn("uk_friendships_pair is missing: some pairs of users have more than one friendship row");
        }
    }
}
