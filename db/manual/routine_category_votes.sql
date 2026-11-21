-- Community-Based Routine Categorization (US-28): users vote for a routine's category, and each
-- routine has one voting period after which the votes decide its category.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application creates these
-- same two tables on start; this script is only needed if that setting is ever turned off. It only
-- adds two new tables. No existing row is changed or deleted.
-- A vote belongs to a routine and a user and goes with them; it refers to its category by id, so
-- a rename keeps it, and a category with votes cannot be removed (ON DELETE NO ACTION).
--
-- Rollback: the previous release ignores both tables, so going back needs no database step.
-- To remove them for good (only after going back):
--   DROP TABLE routine_category_votes; DROP TABLE routine_category_votings;
-- Safe to run more than once.

CREATE TABLE IF NOT EXISTS routine_category_votes (
    id BIGSERIAL PRIMARY KEY,
    routine_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_category_votes_routine_user UNIQUE (routine_id, user_id),
    CONSTRAINT fk_category_votes_routine FOREIGN KEY (routine_id) REFERENCES routines (id) ON DELETE CASCADE,
    CONSTRAINT fk_category_votes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_category_votes_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE TABLE IF NOT EXISTS routine_category_votings (
    id BIGSERIAL PRIMARY KEY,
    routine_id BIGINT NOT NULL,
    opened_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    closes_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP(6) WITH TIME ZONE,
    winner_category_id BIGINT,
    winner_name VARCHAR(40),
    CONSTRAINT uk_category_votings_routine UNIQUE (routine_id),
    CONSTRAINT fk_category_votings_routine FOREIGN KEY (routine_id) REFERENCES routines (id) ON DELETE CASCADE,
    CONSTRAINT fk_category_votings_winner FOREIGN KEY (winner_category_id) REFERENCES categories (id)
);
