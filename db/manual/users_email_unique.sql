-- Enforces one account per e-mail address, ignoring letter case.
-- Run by hand. It is NOT applied at application startup and it never changes or deletes rows.
--
-- Step 1 lists duplicates. If it returns rows, stop and resolve them by hand before continuing.
-- Step 2 fails without side effects while duplicates exist.

-- Step 1: read-only check.
SELECT lower(email) AS email, count(*) AS accounts, array_agg(id ORDER BY id) AS user_ids
FROM users
GROUP BY lower(email)
HAVING count(*) > 1;

-- Step 2: add the constraint.
CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_uq ON users (lower(email));
