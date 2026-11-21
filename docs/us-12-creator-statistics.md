# US-12 Creator Statistics

Local work only; not pushed or deployed. This note is for review before any production deployment.

## The story (SRS, Could Have)

A creator wants to see how many followers they have and how many times each of their public
routines has been copied.

* **AC1** — A creator with published public routines can open their statistics and see their
  follower count and the number of copies for each routine.
* **AC2** — If a creator has no followers or a routine has no copies, the statistics show 0
  correctly, without errors.

Added beyond the two criteria (stated separately, nothing in them changed):

* The overview also shows the total of copies and the number of published routines.
* Each routine also shows its likes, saves and rating, read from data the app already keeps
  (no views, impressions or other invented numbers).
* Empty, loading and error states, with Try again.

## Features reused

| Need | Reused |
|---|---|
| Followers | `UserFollowRepository.countFollowers` — exactly the number the profile shows (banned followers left out) |
| Copy | `RoutineService.copyRoutine` (Copy) and `copyAdaptedRoutine` (US-19 Adapt & copy); both save through `saveCopy` |
| Who may copy what | `RoutineAccessService.requireViewableRoutine` (unchanged) |
| Likes, saves, rating | `liked_routines`, `saved_routines`, `routines.average_rating / rating_count` |
| Profile section | the embedded-section pattern of To-do Lists and Posts in `profile.html` (nav entry, `#address`, Back/Forward) |
| Look | the panel's `--mc-*` colours (`my-content.css`), so the original and autumn designs both apply |

## Copy attribution

Before US-12 a copy did not record where it came from. Now `routines.copied_from_id` holds the
routine the member chose to copy:

* set in `saveCopy`, in the same transaction that saves the copy, its cloned habits and their
  links; a refused or failed copy saves nothing, so leaves no link;
* both copy paths go through `saveCopy`; no other path makes a routine from another one (the AI
  assistant, the seeders and Create make new routines; copying one activity — US-18 — adds a habit
  to a routine the member already has, which is not a routine copy);
* never changed afterwards (`updatable = false`): editing, publishing or deleting the copy keeps it;
* a routine is never its own source (`chk_routines_copied_from_not_self`); since a source never
  changes, no cycle can form either.

## Counting rules

A routine's **copiesCount** is the number of routines saved as a copy of it:

* by Copy or Adapt & copy;
* by another member — the creator's own copies are left out;
* who is not banned — like followers; if the member is unbanned, their copies count again;
* for its whole life — a copy its owner later deleted still counts (routines are only
  soft-deleted, so the row and its link stay);
* each copy — a member who copies a routine twice made two copies; copies made at the same moment
  are all counted, since each is its own row (no counter to lose an update);
* only the routine chosen — a copy of a copy counts for the copy that was copied, not again for
  the original.

Saving, liking, rating, viewing, commenting, opening the copy dialog and copying a single activity
are not copies.

**Published** routines are those other members can open and copy: public, not deleted and not
hidden by an administrator. Only those are listed. **totalCopies** is the sum of the listed
routines' copies, so it always matches the list. A routine made private or hidden leaves the list
with its copies; shown or published again, it comes back with them.

## Historical limitation

Copies made before this release recorded no source. Nothing is guessed for them — not from titles,
not from similar content — and every existing routine keeps `copied_from_id = NULL`. The counts
start at 0 for every routine and grow from the deploy on; they are not complete for the time
before. The section says so under the list. Production has no other record that would tell where
an older copy came from (checked in the 2026-11-16 backup: the routines table and the tables
around it hold no source reference).

## API

`GET /users/me/creator-stats` — signed in (any member; visitors get 401/403). Always the caller's
own numbers; there is no id parameter (`?userId=` is ignored, `/users/{id}/creator-stats` does not
exist), and no public page shows them.

```json
{
  "followersCount": 3,
  "totalCopies": 3,
  "totalPublicRoutines": 3,
  "routines": [
    {"routineId": 44, "title": "Morning flow", "copiesCount": 2,
     "likesCount": 1, "savesCount": 4, "averageRating": 4.5, "ratingCount": 2}
  ]
}
```

Every count is a number, `0` when there is nothing (never `null`); `routines` is `[]` when nothing
is published. Most copied first, then the newest. One query for the routines, with each number in
its own subquery, so no join multiplies another and there is no query per routine; one more for
followers.

## Frontend

`profile.html` has a **Creator Statistics** entry right after Friends (AI Assistant stays between
Progress and Friends), address `profile.html#creator-stats`. `creator-stats.js` (`CopyMyLifeCreatorStats.mount`) draws:

* the overview: Followers, Copies of your routines, Public routines, each with a hint when it is 0;
* one card per published routine: its title (a link to the routine), its copies, likes, saves and
  rating ("No ratings" when there is none);
* empty states: no public routine (with a link to My Routines), no copies yet;
* loading (`role=status`), and an error (`role=alert`) with Try again; opening the section again
  reloads the numbers.

Everything is built with DOM calls and `textContent`; a routine title is never read as HTML.
`creator-stats.js` and `creator-stats.css` are listed in `SecurityConfig` like the other static
files.

## Database

| Change | How it is made |
|---|---|
| `routines.copied_from_id` bigint, nullable | Hibernate `ddl-auto=update` on start |
| `fk_routines_copied_from` → `routines(id)` `ON DELETE SET NULL` | Hibernate (`@OnDelete(SET_NULL)`) |
| `idx_routines_copied_from` | Hibernate (`@Table(indexes)`) |
| `chk_routines_copied_from_not_self` | `config/RoutineCopyConstraints` on start (`NOT VALID`, then validated) |

`db/manual/routine_copied_from.sql` does the same by hand in one transaction with
`SET LOCAL lock_timeout = '5s'`; it can run any number of times and inserts, updates or deletes no
row. No cascade anywhere; existing rows, reports (US-24) and every other table are untouched.

## Privacy and security

* Only the signed-in member's own numbers; nothing by id; nothing on public profiles.
* Private, deleted and hidden routines are never listed, and their titles never appear.
* Copy rules unchanged: only routines the member may see can be copied (a private or missing one
  answers 404 and records nothing).
* Banned members cannot sign in (existing rule); their follows and copies are left out of others'
  numbers.
* Hostile titles stay text in the browser (checked).

## Tests

* `feature/CreatorStatisticsRegressionTest` (28 tests): AC2 zeros (numbers, not null) and an empty
  list; AC1 followers and per-routine copies; standard and adapted copies count for the routine
  copied and no other; several members and repeated copies; copying makes no follower; likes,
  saves, ratings, views, comments and copying one activity are not copies; refused copies (invalid
  adaptation, private, missing, visitor) leave no row and no count; private routines and the
  creator's own copies stay out; hidden and deleted routines are left out, deleted copies still
  count, unhiding brings the routine back; banned followers and copiers are left out like on the
  profile; copy of a copy; exact counts with many likes, saves, ratings and copies; 8 + 3 copies at
  the same moment all counted; the source routine is unchanged by copying; the copy answer and
  behaviour are unchanged and editing keeps the source; visitors refused; nobody reads another
  member's numbers; the database refuses a self-reference and an unknown source, the foreign key is
  SET NULL; starting on a pre-US-12 table (manual script twice, start-up check twice) keeps every
  row and guesses no source, not even for a routine with the same title.
* `src/test/e2e/creator-stats-check.mjs` (headless Chrome, started by the test): the menu entry
  and `#creator-stats`; the numbers, the order and that the private routine is not listed; a hostile
  title stays text; Copy and Adapt & copy on the routine page raise the numbers; zeros and the empty
  state; loading, error and Try again; the "no copies yet" note; every other profile section still
  opens alone, Back and Forward; both designs at 360, 390, 768, 1280, 1366 and 1920 px (and the
  empty state at 390): no sideways scrolling, numbers and menu entry on screen, text contrast ≥ 4.5;
  no script errors or native dialogs.
* RED on the previous release (`5493bb9`, browser part off): 19 of the 28 fail (404 for the
  endpoint, missing column and check); the two refusal tests pass there too, as they should.

Full verification on 2026-11-16/17 (isolated PostgreSQL 18 on 127.0.0.1, HEAD export + these
files): `mvnw clean package` **958/958** (930 earlier + 28 new; 0 failures, 0 errors, 0 skipped,
including the real-AI integration test), BUILD SUCCESS; Python `ai/tests` 73/73. A first full run
caught one regression — the new menu entry had been put between Progress and AI Assistant, which
the AI Assistant design forbids — so the entry now follows Friends and the browser test checks the
order.

## Migration rehearsal (local copies of the 2026-11-16 production backup only)

Backup `copymylife-before-us24-20261116.dump` (SHA-256 FC7180BB…D6BB) restored three times, with
the US-24 script applied (as production now is) and two synthetic members, one public routine and
one follow added.

* New jar, first start: only `ADD COLUMN copied_from_id`, `CREATE INDEX idx_routines_copied_from`,
  `ADD CONSTRAINT fk_routines_copied_from … ON DELETE SET NULL` and the check (`log_statement =
  all`); no INSERT, UPDATE or DELETE; every table's content unchanged (routines compared on their
  original columns); all 58 routines keep `copied_from_id` NULL; US-24 safeguards still in place.
  Second start: no DDL. Then over the API: 0 copies → one copy → 1; a visitor is refused.
* `routine_copied_from.sql`: one transaction, no warnings; run again, only "already exists"
  notices; routines unchanged; the check queries show the column, `confdeltype = 'n'`, the index
  and the validated check. With another session holding a lock on routines it stopped after the
  5 s lock timeout (with and without `ON_ERROR_STOP`) and left schema and rows exactly as they were.
* Previous release (5493bb9) on the migrated copy: starts, changes nothing in routines, copying
  works and its copy gets `copied_from_id` NULL (so it is not counted); the new release on the same
  copy then counts only the copies it made.

## Production deployment prerequisites

1. A fresh, verified production backup.
2. Recommended: run `db/manual/routine_copied_from.sql` in the Railway Postgres console while the
   current release keeps running, then the check queries at its end (column nullable, foreign key
   `confdeltype = 'n'`, index, check validated, every routine still `copied_from_id` NULL). Letting
   the application add it on start also works.
3. Deploy. Nothing needs to be in a particular state first: the column is optional for both
   releases.
4. After the deploy: open Creator Statistics with a real account (read only); do not make test
   copies or accounts in production.

## Rollback

The previous release works with the column in place: it never names it, so copies it makes get
NULL (and are not counted once US-12 is back). Going back needs no database step and loses no
data. The full manual removal is at the top of the SQL file.

## Known limits

* Copies made before this release are not counted (no source was recorded).
* A routine that is private or hidden is not listed, and its copies are not in the total while it
  is not published.
* The numbers are read when the section opens; they do not update live.
