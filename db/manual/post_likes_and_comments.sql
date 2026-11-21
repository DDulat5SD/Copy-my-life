-- Like and Comment (US-15): likes and comments on posts.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application creates
-- these same tables on start; this script is only needed if that setting is ever turned off.
-- Only new tables are added. No existing table, column or row is changed.
-- A like or comment goes with its post and with its author (ON DELETE CASCADE).
-- uk_post_likes_post_user keeps one like per user and post; the application relies on its name
-- (INSERT ... ON CONFLICT ON CONSTRAINT uk_post_likes_post_user DO NOTHING).
--
-- Rollback: the previous release ignores these tables and runs unchanged, so rolling back
-- needs no database step. Likes and comments made in the meantime stay in the tables, unseen,
-- and come back if this release is deployed again. To remove them for good instead:
--   DROP TABLE IF EXISTS post_comments; DROP TABLE IF EXISTS post_likes;

-- Step 1: the changes. Safe to run more than once.
BEGIN;

CREATE TABLE IF NOT EXISTS post_likes (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_post_likes_post_user UNIQUE (post_id, user_id),
    CONSTRAINT fk_post_likes_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_post_likes_user ON post_likes (user_id);

CREATE TABLE IF NOT EXISTS post_comments (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    text       VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_post_comments_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_comments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_post_comments_post ON post_comments (post_id, created_at);
CREATE INDEX IF NOT EXISTS idx_post_comments_user ON post_comments (user_id);

COMMIT;

-- Step 2: read-only check, after this script or after deploying.
SELECT (SELECT count(*) FROM posts) AS posts,
       (SELECT count(*) FROM post_likes) AS post_likes,
       (SELECT count(*) FROM post_comments) AS post_comments;
