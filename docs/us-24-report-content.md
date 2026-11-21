# US-24 Report Content

Local work only; not pushed or deployed. This note is for review before any production deployment.

## The story (SRS, Could Have)

As a user, I want to report inappropriate posts, comments, or routines so that moderators can review them.

* **AC1** — When a user presses Report, chooses a reason and sends it, the report reaches the
  administrators' moderation list and the user is shown a confirmation.
* **AC2** — When the user already has an active report about that content, a clear
  "already reported" message is shown.

Everything below that is not in these two criteria (the reason list, the explanation length, the
moderation actions per type, what happens after a report is closed) is an implementation choice
made to fit the existing code; it is not part of the SRS.

## What can be reported

| Type (`reports.type`) | Target column | Button | Who is `reported_user_id` |
|---|---|---|---|
| `USER` (before US-24) | `reported_user_id` | user-profile.html "Report user" | the user |
| `COMMENT` (before US-24; a comment under a routine) | `reported_comment_id` | routine.html comment ··· menu → Report; replies: Report next to Reply | the comment's author |
| `ROUTINE` | `reported_routine_id` | routine.html "Report" in the routine card | the routine's owner |
| `POST` | `reported_post_id` | posts.js "Report" under a post (posts.html?user=, the user-profile post view, profile.html) | the post's author |
| `POST_COMMENT` | `reported_post_comment_id` | posts.js "Report" under a comment | the comment's author |

Buttons are shown only to signed-in members for other people's content (never one's own). A
visitor on a routine page is asked to sign in, as for every other action there.

## API

All need a signed-in member (`/reports/**` is `hasRole("USER")`); visitors get 401/403, banned
members' tokens are not accepted, administrators get 403.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/reports/users/{id}` | kept |
| `POST` | `/reports/comments/{id}` | kept (routine comment) |
| `POST` | `/reports/routines/{id}` | new |
| `POST` | `/reports/posts/{id}` | new |
| `POST` | `/reports/post-comments/{id}` | new |
| `GET` | `/reports/mine` | kept; the caller's own reports, newest first |

Request: `{"reason": "SPAM", "details": "optional text"}`.

Success: HTTP 200 (as before) with the earlier fields plus three new target ids and a message:

```json
{"id": 41, "type": "POST", "reportedUserId": 7, "reportedCommentId": null,
 "reportedRoutineId": null, "reportedPostId": 12, "reportedPostCommentId": null,
 "reason": "SPAM", "details": null, "status": "OPEN", "createdAt": "2026-11-16T09:00:00",
 "message": "Your report has been submitted."}
```

Refusals: `{"message": "...", "code": "..."}`, never a stack trace.

| Status | code | message |
|---|---|---|
| 400 | `INVALID_REASON` | Please select a valid report reason. (also for no body or non-JSON) |
| 400 | `DETAILS_TOO_LONG` | Your explanation can be at most 1000 characters. |
| 400 | `SELF_REPORT` | You cannot report yourself / your own comment / routine / post |
| 403 | `FORBIDDEN` | User account required |
| 404 | `NOT_FOUND` | User / Comment / Routine / Post not found — the same words and body for missing, private, hidden and deleted content, and for an id that is not a number |
| 409 | `ALREADY_REPORTED` | You have already reported this content. |
| 503 | `UNAVAILABLE` | Reports can't be sent right now. Please try again later. (only while a database safeguard is missing: one of the five indexes, the widened type check or the target check; see deployment) |

## Reasons

`SPAM`, `HARASSMENT`, `INAPPROPRIATE_CONTENT`, `MISINFORMATION`, `OTHER` (`ReportService.Reason`,
labels in `report-dialog.js` and `admin.js`). The server accepts them in any letter case, trims
them and stores the code in the existing `reason varchar(120)` column. Anything else is refused;
the page's own check is only for convenience. Reports made before US-24 keep their free-text
reasons and are shown as they are. The explanation is optional, trimmed, at most 1000 characters
(the existing `details varchar(1000)`), stored and shown as plain text.

## Who may report what

* The same visibility rules as the pages: routines through `RoutineAccessService.canViewRoutine`
  (public and not hidden by an administrator, or one's own; never deleted), posts through
  `PostService.viewable` (public, not hidden, author visible; or one's own). A comment is
  reportable when its routine or post is; deleted routine comments, banned authors' post comments
  and administrators' comments are "not found". Users: only member accounts (`role = USER`).
* Nothing is hidden, removed or banned by a report. Only an administrator's decision does that.

## One active report per person and target

* Active = `OPEN` or `IN_REVIEW`. A `RESOLVED` or `REJECTED` report does not count: the same
  person may report the same content again once an administrator has closed the earlier report
  (as before US-24).
* `ReportService` takes a transaction-scoped advisory lock per reporter
  (`pg_advisory_xact_lock(hashtext('report:' || id))`), checks for an active report and inserts.
* The database enforces it on its own: five partial unique indexes
  `uk_reports_active_{user,comment,routine,post,post_comment}` on `(reporter_id, <target>)`
  `WHERE type = '<TYPE>' AND status IN ('OPEN','IN_REVIEW')`. A violation is turned into the 409.
* Measured: before US-24, 8 identical requests at once stored 2 reports (USER) and 2 requests at
  once stored 2 (COMMENT). Now 2 and 8 identical requests at once, for each of the five types,
  give exactly one 200 and the rest 409, never a 5xx, and one stored row.
* Reporting is never on without that protection: before each new report `ReportConstraints.
  missingSafeguards()` reads PostgreSQL's catalog (`pg_index`: unique, valid, ready, partial;
  `pg_constraint`: the type check names all five types, `chk_reports_target` exists). If anything
  is missing, every report endpoint answers 503 `UNAVAILABLE` and nothing is written; reading
  (`/reports/mine`, the admin list and its actions) keeps working, so an administrator can close
  the duplicates that block an index.

## Database (additive except one constraint swap)

| Change | How it is made |
|---|---|
| `reports.reported_routine_id`, `reported_post_id`, `reported_post_comment_id` (nullable bigint) | Hibernate `ddl-auto=update` on start |
| `fk_reports_routine/post/post_comment` → routines/posts/post_comments, `ON DELETE SET NULL` | Hibernate (`@OnDelete(SET_NULL)`) |
| `idx_reports_routine/post/post_comment` | Hibernate (`@Table(indexes)`) |
| `chk_reports_type` allowing all five types (replaces the USER/COMMENT-only check) | `ReportConstraints` on start |
| `chk_reports_target`: only the column of its own type may be set | `ReportConstraints` (`NOT VALID`, then validated) |
| the five partial unique indexes | `ReportConstraints` (left out if older rows break one; reporting then stays off) |

`config/ReportConstraints` runs once every bean is ready (so after Hibernate's update) and before
the web server accepts requests. `db/manual/report_content_targets.sql` does all of it by hand, in
one transaction, and is safe to run any number of times; it also holds the read-only conflict
report and check queries. No row is inserted, updated or deleted by either.

Starting is not the same as migrating: the server starts even when an index could not be made.
The startup log tells them apart: `Report safeguards in place: type check, target check and 5
active-report indexes` (INFO) means the migration is complete; `Reporting is OFF until these
exist: [...]` (ERROR) means it is not, and the report endpoints answer 503 until it is.

Important: production's check `chk_reports_type` (and `reports_type_check` in Hibernate-made test
databases) allows only `USER` and `COMMENT`. Hibernate does not change existing checks, so without
the swap every new-type report would be refused by the database.

Deleted content: posts and post comments are really deleted, so their links become NULL and the
report, reason, explanation, reported user and review history stay (the admin page shows
"Deleted"). Routines and routine comments are only soft-deleted. No CASCADE was added; the
existing foreign keys are unchanged; no column was made NOT NULL.

## Moderation (admin-reports.html, `/admin-api/reports`)

The list shows ID, type, the reported content (routine title, post text, comment text with links
to the admin routine view and profiles; "Hidden", "Removed", "Deleted", "Banned" marks), the
reporter, reason and explanation, creation time, status and the action taken; it filters by type
and status. Every value is escaped (`safe()`).

| Action | Endpoint | For | Uses |
|---|---|---|---|
| Review / Reject / Resolve | `PUT /{id}/status` | all | as before (only forward from OPEN) |
| Ban user | `POST /{id}/ban-user` | all (the person behind it) | as before |
| Remove comment | `POST /{id}/remove-comment` | COMMENT | as before (soft delete) |
| Hide routine | `POST /{id}/hide-routine` | ROUTINE | `AdminContentService.setRoutineHidden` (US-25) |
| Hide post | `POST /{id}/hide-post` | POST | `AdminContentService.setPostHidden` (US-25) |
| Remove post | `POST /{id}/remove-post` | POST | `AdminContentService.removePost` (US-25) |
| Remove comment | `POST /{id}/remove-post-comment` | POST_COMMENT | `AdminContentService.removePostComment` (new) |

Each action locks the report row (`SELECT … FOR UPDATE`), refuses a closed report (409 "This report
is already closed"), the wrong type (409 "This report is not about a post"), deleted content
(409) and already-hidden content (409), asks the administrator to confirm, and resolves the report
with `action_taken`. Other reports about the same content stay open for a decision.
`/admin-api/**` stays administrator-only (SecurityConfig, role read from the database per request).

## The Report dialog (`report-dialog.js`, `report-dialog.css`)

One component, `CopyMyLifeReport.open({type, id, token})`, for all five buttons. Title per type,
five reasons as radio buttons with a legend, optional explanation with a counter, Cancel and Submit
Report. On open it checks `/reports/mine` and shows "You have already reported this content." at
once when an active report exists; the server's 409 shows the same. Submitting without a reason
shows "Please select a reason." and sends nothing; while sending, the button reads "Submitting…"
and the form is disabled, so a second click sends nothing. Success shows "Your report has been
submitted."; a 404 says "This content is no longer available."; other errors keep the form open.
`role="dialog"`, `aria-modal`, labelled title, focus moves in and returns, Tab stays inside, Escape
and the × close it. Built with DOM calls only; colours come from `theme.css` (Original and Autumn).

## Tests

* `feature/ReportContentRegressionTest` (38 tests): AC1 for all five types (answer, stored row,
  admin list), the earlier answer shape, nothing changes on report; AC2 for all five types;
  IN_REVIEW counts, RESOLVED/REJECTED do not; different reporters / different content; 2 and 8
  identical requests at once per type, 6 different reporters at once; the database refuses a
  second active row, two targets and an unknown type; the indexes and checks exist; starting on a
  pre-US-24 table (old check, three legacy duplicates) widens it, changes no row, reports the
  missing index and refuses new reports of every type with 503 until it exists; pre-US-24
  free-text reports stay readable; reason, body and explanation validation; hostile text stored
  literally; visitors, self-reports, missing, private (same body as missing), hidden, deleted,
  banned, administrators; members cannot use the admin API; hide/remove/ban actions, wrong-type and
  repeated actions; owners can still delete reported posts and comments; and the browser checks
  below.
* `src/test/e2e/report-content-check.mjs` (headless Chrome, started by the test): every Report
  button opens the dialog with the right title and target; each report is stored with the chosen
  reason; empty form, loading state, double submit, already reported (on open and from the server),
  deleted-while-open; keyboard; existing menus, own-content and owner/visitor views; the admin page
  shows all five types, keeps hostile text as text, changes a status and hides a routine; no
  sideways scrolling, dialog on screen and text contrast ≥ 4.5 in both designs at 360, 390, 768,
  1280, 1366 and 1920 px; no script errors or native dialogs.
* `security/StoredXssBrowserRegressionTest` now sends a valid reason with a hostile explanation and
  writes a hostile free-text reason straight to the database (as an old report would have it), so
  the admin page is still checked with both.

Full verification on 2026-11-16 (isolated PostgreSQL 18 on 127.0.0.1, HEAD export + these files):
`mvnw clean package` 930/930 (0 failures, 0 errors, 0 skipped, including the real-AI integration
test), BUILD SUCCESS; Python `ai/tests` 73/73.

## Migration rehearsal (local copies of the 2026-11-15 production backup only)

Backup `copymylife-before-ai-assistant-20261115.dump` (SHA-256 9CDC08B5…932E, 27 tables, 947 rows)
restored three times. Production has **no reports at all** in that backup, so four synthetic
pre-US-24 reports (free-text reasons; USER OPEN and IN_REVIEW, COMMENT RESOLVED and REJECTED) were
added to each copy to prove old rows survive.

* New jar, first start: only additive DDL for reports (three `ADD COLUMN`, three FKs `ON DELETE
  SET NULL`, three indexes, the type-check swap, `chk_reports_target`, five unique indexes); no
  INSERT, UPDATE or DELETE (PostgreSQL `log_statement = all`). Every pre-existing table has the same
  rows and content hash (reports and habit_completions compared on their original columns; the
  other DDL in that log is the AI assistant's, already in production). Log: `Report safeguards in
  place`. Second start: no DDL, rows and schema identical.
* On the migrated copy: ROUTINE, POST, POST_COMMENT reports 200, repeat 409, a legacy IN_REVIEW
  report blocks a new one (409), a legacy RESOLVED one does not; 8 identical requests at once →
  one 200, seven 409, one row; the database refuses an unknown type, two targets, a second active
  report and a missing routine; deleting a post empties its links and keeps the reports.
* `report_content_targets.sql` on a fresh copy: one transaction, no warnings; run again, only
  "already exists" notices; reports unchanged. Run while the previous release was serving
  requests: about 0.1 s, no failed request, the running release kept working without a restart.
  With another session holding a lock on `reports`, it stopped after the 5 s lock timeout and
  left the table exactly as it was.
* Old release (f084d74) on the SQL-migrated copy with only USER/COMMENT reports: starts, changes
  nothing in `reports`, admin list and `/reports/mine` 200, its duplicate check still 409.
* Old release on a copy that already holds new-type reports: admin list and `/reports/mine`
  answer 400 `No enum constant …Report.Type.POST` — see Rollback.
* Copy with a legacy duplicate (two OPEN USER reports by one person for one user): the new jar
  starts, logs `Reporting is OFF until these exist: [uk_reports_active_user]`, every report
  endpoint answers 503 while `/reports/mine` and the admin list work; the conflict report lists
  both ids; after an administrator rejects one and the SQL script runs, the index exists and
  reports are accepted again without a restart.

## Production deployment prerequisites

1. A fresh, verified production backup (as for every earlier story).
2. Read-only, in the Railway Postgres console, the pre-migration conflict report (also at the end
   of `db/manual/report_content_targets.sql`; it uses only columns the current table has — the
   post-migration version there needs the new columns and fails before the migration):

   ```sql
   SELECT type, reporter_id, COALESCE(reported_comment_id, reported_user_id) AS target_id,
          COUNT(*) AS active_reports, array_agg(id ORDER BY id) AS report_ids
   FROM reports
   WHERE status IN ('OPEN', 'IN_REVIEW')
   GROUP BY type, reporter_id, COALESCE(reported_comment_id, reported_user_id)
   HAVING COUNT(*) > 1
   ORDER BY type, reporter_id;
   ```

   If it returns rows, an administrator closes the extra reports (Reject) with the previous
   release, before the deploy. Deployment is ready only when this report is empty: otherwise the
   new release starts with reporting off (503) until the duplicates are closed and the SQL script
   is run.
3. Recommended order: run `db/manual/report_content_targets.sql` in the console first while the
   previous release keeps running (one transaction with `SET LOCAL lock_timeout = '5s'`: if another
   session holds a lock it stops with "canceling statement due to lock timeout", everything is
   rolled back and it can be run again; its WARNINGs, if any, say which index was left out), check
   it with the queries at the end of the file, then deploy. The previous release works with all of it in place. Letting the
   application make the changes on start also works (rehearsed), but then the migration result is
   only visible in the startup log.
4. After the deploy: the startup log must show `Report safeguards in place` (not `Reporting is
   OFF`), and the check queries at the end of the SQL file must show 5 `uk_reports_active_*`
   indexes, `chk_reports_type` with five values and `chk_reports_target` validated.
5. Do not send test reports to production.

## Rollback

The previous release runs on the migrated schema unchanged **as long as no ROUTINE, POST or
POST_COMMENT report exists**: its `Report.Type` has only USER and COMMENT, so its admin report list
and `/reports/mine` fail on such rows (rehearsed: 400 `No enum constant …Report.Type.POST`).
Rolling back is therefore **not safe** once anyone has reported a routine, post or post comment. Check with `SELECT type, COUNT(*) FROM reports GROUP BY
type;` first. The schema itself needs no rollback step; the full manual removal (only after the
new-type rows are handled) is in the header of the SQL file. Existing reports are never touched.

## Known limits

* Closing one report does not close other reports about the same content; each is decided on its
  own.
* Routines are hidden, not removed, from the report list (removing stays in Admin → Routines).
* A banned author's public routine can still be opened and reported (the routine page has always
  shown it; noted in the Could Have audit).
* Reporters are not notified of the outcome.
