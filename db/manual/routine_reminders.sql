-- US-22 Routine Reminders: each user's reminder setting and the reminders already shown to them.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application creates these
-- same tables on start; this script is only needed if that setting is ever turned off.
-- It only adds two new tables. No existing table, column or row is changed.
--   reminder_settings   - one row per user who changed the setting; no row means the defaults
--                         (reminders on, 15 minutes before).
--   reminder_deliveries - one row per reminder handed to the user's browser; the unique
--                         (user_id, reminder_key) makes each occurrence shown once. Rows older
--                         than three days are deleted by the application as it goes.
-- Both belong to their user and go with them (ON DELETE CASCADE).
--
-- Rollback: the previous release ignores both tables, so going back needs no database step.
-- To remove them for good (only after going back; this loses users' reminder settings):
--   DROP TABLE reminder_deliveries; DROP TABLE reminder_settings;
-- Safe to run more than once.

CREATE TABLE IF NOT EXISTS reminder_settings (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    lead_minutes INTEGER NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_reminder_settings_user UNIQUE (user_id),
    CONSTRAINT fk_reminder_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS reminder_deliveries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    reminder_key VARCHAR(80) NOT NULL,
    occurrence_date DATE NOT NULL,
    delivered_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_reminder_deliveries_user_key UNIQUE (user_id, reminder_key),
    CONSTRAINT fk_reminder_deliveries_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
