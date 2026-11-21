-- Privacy Settings (US-23): to-do lists, their tasks, and posts.
--
-- NOT run automatically. With spring.jpa.hibernate.ddl-auto=update the application creates
-- these same tables on start; this script is only needed if that setting is ever turned off.
-- Only new tables are added. No existing table, column or row is changed.
-- Each table is removed with its owner: deleting a user deletes their lists, tasks and posts.
--
-- Rollback: the previous release ignores these tables and runs unchanged, so rolling back
-- needs no database step. Lists and posts made in the meantime stay in the tables, unseen,
-- and come back if this release is deployed again. Photo files stay under uploads/posts.

-- Step 1: read-only check after deploying.
SELECT (SELECT count(*) FROM todo_lists) AS lists,
       (SELECT count(*) FROM todo_tasks) AS tasks,
       (SELECT count(*) FROM posts) AS posts;

-- Step 2: the changes. Safe to run more than once.
BEGIN;

CREATE TABLE IF NOT EXISTS todo_lists (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    title      VARCHAR(100) NOT NULL,
    is_public  BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_todo_lists_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_todo_lists_user ON todo_lists (user_id);

CREATE TABLE IF NOT EXISTS todo_tasks (
    id           BIGSERIAL PRIMARY KEY,
    todo_list_id BIGINT NOT NULL,
    text         VARCHAR(200) NOT NULL,
    done         BOOLEAN NOT NULL,
    position     INTEGER NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_todo_tasks_list FOREIGN KEY (todo_list_id) REFERENCES todo_lists (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_todo_tasks_list ON todo_tasks (todo_list_id);

CREATE TABLE IF NOT EXISTS posts (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    text       VARCHAR(2000),
    image_url  VARCHAR(255),
    is_public  BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_posts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_posts_user ON posts (user_id);

COMMIT;
