# US-07 Progress Status

Local work only; not pushed or deployed.

## The story (SRS, Could Have)

As a user, I want to see my status based on completed tasks so that I can track my progress and
stay motivated.

* **AC1** — The user's current status is displayed based on completed tasks.
* **AC2** — The status automatically increases when the next level requirement is reached.
* **AC3** — Otherwise the current status stays the same, and the requirement for the next level
  is visible.

## Levels

The SRS gives no names or numbers; these are product defaults, kept in one list
(`ProgressStatusService.LEVELS`).

| Level | Status | Completed activities |
|---|---|---|
| 1 | Starter | 0–9 |
| 2 | Builder | 10–24 |
| 3 | Consistent | 25–49 |
| 4 | Achiever | 50–99 |
| 5 | Master | 100+ |

## What counts

One completed activity is one habit completed on one day, read from `habit_completions`, the
same records the US-21 streaks use. No new table or column: the status is worked out on each
request, so it rises the moment a habit is completed (AC2) and nothing can drift out of date.

* Counted: rows with status `COMPLETED`, and rows from before statuses existed that have a
  completion time (as `HabitCompletion.currentStatus()` reads them).
* Not counted: skipped, postponed or unfinished habits; views, likes, saves, copies.
* Once a day per habit: the same habit done in two routines, or in two runs, on one day counts
  once.
* Only the user's own rows (`user_id`).
* What was done stays done: deleting a habit from a routine or deleting the routine (both soft
  deletes) takes nothing away. Only an admin's hard delete of a habit removes its records, and
  with them its count.

## API

`GET /progress` gains a `status` object (all existing fields unchanged); `GET /progress/status`
returns the same object alone. Both need a signed-in user.

```json
{"level": 2, "name": "Builder", "completedActivities": 18, "levelStartsAt": 10,
 "nextLevel": 3, "nextName": "Consistent", "nextLevelAt": 25, "activitiesToNextLevel": 7,
 "progressPercent": 53, "maxLevel": false}
```

`progressPercent` is the way through the current level, rounded down so 100% only shows once
the next level is reached. At Master the `next*` fields and `activitiesToNextLevel` are `null`,
`progressPercent` is 100 and `maxLevel` is true. The status does not depend on the period or
day chosen.

## Profile

A status card at the top of Profile → Progress: level, status name, completed activities, a
progress bar, and "N more completed habits to reach Level X · Name"; at Master a highest-status
message. Original and Autumn designs; at phone width the count moves under the name.

## Fixed on the way

Habits whose routines were all deleted were still counted as expected in Progress →
"Completed habits" (the audit's finding): `ProgressService.pausedEverywhere` only looked at links
in live routines and, finding none, counted the habit. Such habits are now treated like habits
deleted from their routines — not expected, unless completed that day.

## Database

No schema change, no manual SQL.
