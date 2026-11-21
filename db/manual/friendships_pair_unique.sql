-- Friend System (US-29): one relationship per pair of users, whichever of the two asked.
--
-- NOT needed by hand: the application (FriendshipPairIndex) runs this same block on every start.
-- It only adds one unique index on an expression of two existing columns; Hibernate's
-- ddl-auto=update cannot declare such an index. No row is changed or deleted. If older rows still
-- hold both directions of one pair, the index is left out with a warning instead, and the
-- application keeps treating such rows as one relationship.
--
-- Rollback: the previous release ignores the index, so going back needs no database step.
-- To remove it for good: DROP INDEX IF EXISTS uk_friendships_pair;
-- Safe to run more than once.

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
$$;
