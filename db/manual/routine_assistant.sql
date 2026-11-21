-- Personal AI Routine Assistant (version 1): saved planning preferences, the user's answers to
-- length suggestions, and two optional columns on habit run records.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application creates these
-- same tables and columns on start; this script is only needed if that setting is ever turned off.
-- It only adds: two new tables and two nullable columns. No existing row is changed or deleted.
--   habit_completions.planned_minutes  - the habit's planned length when it was started; filled
--                                        for new runs only, null on every older row.
--   habit_completions.reported_minutes - a length the user entered after completing the habit;
--                                        only ever set by the user, null by default.
-- Preferences and decisions belong to their user (and a decision to its routine habit) and go
-- with them (ON DELETE CASCADE), so deleting a user or an activity works as before.
--
-- Rollback: the previous release ignores the new tables and columns, so going back needs no
-- database step. To remove them for good (only after going back; this loses entered lengths):
--   DROP TABLE assistant_decisions; DROP TABLE assistant_preferences;
--   ALTER TABLE habit_completions DROP COLUMN reported_minutes, DROP COLUMN planned_minutes;
-- Rehearsed 2026-11-15 on a copy of copymylife-before-us26-20261111.dump: Hibernate ran exactly this
-- DDL (2 tables, 2 nullable columns, 1 index, constraints on the new tables only); no row changed.
-- Safe to run more than once.

CREATE TABLE IF NOT EXISTS assistant_preferences (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    wake_time TIME(0),
    sleep_time TIME(0),
    available_from TIME(0),
    available_to TIME(0),
    breakfast_time TIME(0),
    lunch_time TIME(0),
    dinner_time TIME(0),
    meal_minutes INTEGER,
    exercise_time TIME(0),
    focus_block_minutes INTEGER,
    break_every_minutes INTEGER,
    break_minutes INTEGER,
    rest_frequency VARCHAR(16),
    intensity VARCHAR(16),
    commitments VARCHAR(4000),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_assistant_preferences_user UNIQUE (user_id),
    CONSTRAINT fk_assistant_preferences_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS assistant_decisions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    routine_habit_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    planned_minutes INTEGER,
    suggested_minutes INTEGER,
    decided_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_assistant_decisions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_decisions_routine_habit FOREIGN KEY (routine_habit_id) REFERENCES routine_habits (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_assistant_decisions_user_link ON assistant_decisions (user_id, routine_habit_id);

ALTER TABLE habit_completions ADD COLUMN IF NOT EXISTS planned_minutes INTEGER;
ALTER TABLE habit_completions ADD COLUMN IF NOT EXISTS reported_minutes INTEGER;
