-- US-16 Save Routines, the posts part: posts a user bookmarked.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application creates this
-- same table on start; this script is only needed if that setting is ever turned off.
-- It only adds one new table. No existing table, column or row is changed.
-- A bookmark belongs to its user and to its post and goes with either (ON DELETE CASCADE), so
-- deleting a post, as its author or an administrator does, works as before.
--
-- Rollback: the previous release ignores the table, so going back needs no database step.
-- To remove it for good (only after going back; this loses users' saved posts):
--   DROP TABLE saved_posts;
-- Safe to run more than once.

CREATE TABLE IF NOT EXISTS saved_posts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_saved_posts_user_post UNIQUE (user_id, post_id),
    CONSTRAINT fk_saved_posts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_saved_posts_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
);
