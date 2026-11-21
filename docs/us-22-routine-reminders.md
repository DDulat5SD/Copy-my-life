# US-22 Routine Reminders

Local work only; not pushed or deployed.

## The story (SRS, Could Have)

As a user, I want to receive reminders for upcoming activities so that I am less likely to forget
my routine.

* **AC1** — Given reminders are enabled, when the activity time approaches, then a reminder about
  that activity is shown.
* **AC2** — Given reminders are disabled, when the activity time comes, then no reminder is sent.

## What is reminded

Only the signed-in user's own activities that have a time:

* **Timed habits of their routines** (`routine_habits.start_time` + repeat days, the plan set in
  the routine's timeline), on the days they repeat. Not when the habit is paused or deleted from
  the routine, or the routine is deleted.
* **Routines on their calendar** (`calendar_events`), on each day of the series. Not on a day
  deleted from the series, and not when the routine is deleted.

Everything is read fresh on each request, so a change takes effect at once: a moved habit or a
calendar day moved to another time is reminded at its new time; a cancelled or deleted one is not
reminded at all.

## When and where

* In the app, on every signed-in page (`reminders.js`, loaded after `session.js`). No email, SMS,
  browser push or outside service.
* Due from the chosen lead time (5, 10, 15, 30 or 60 minutes; default 15) before the activity
  until 5 minutes after it starts, so a page opened as it begins still shows it.
* Times are the user's own wall-clock times, read in the zone the browser sends (`X-Time-Zone`,
  as Progress does); yesterday, today and tomorrow are checked, so windows across midnight and
  daylight-saving changes work.
* The reminder card says when it starts, what it is and which routine; **Open routine** and
  **Dismiss**. At most three at a time; fixed at the bottom right (full width at phone size).

## Delivery mechanism

No background job. While a signed-in page is in view, the browser calls `POST /reminders/due`
once a minute (and when the tab comes back into view). The server works out what is due and
records each reminder in `reminder_deliveries` with
`INSERT … ON CONFLICT (user_id, reminder_key) DO NOTHING`; only the call whose insert succeeded
returns it. So each occurrence is shown once, even with many tabs or devices asking at once, and
a reload does not bring it back. The key is the activity, its day and its time, so a rescheduled
activity is reminded again at its new time. Delivery rows older than three days are deleted by
the same call. Hidden tabs do not ask, so a background tab never takes a reminder from the tab in
view. With nothing open, nothing is shown (in-app reminders only).

With reminders off, `POST /reminders/due` returns nothing and writes nothing (AC2).

## Settings

Profile → Settings has a Reminders card under the theme choice: **Remind me** on/off, the lead
time, and today's remaining timed activities with their state (Will remind / Reminded / Off).
Reminders are **on by default** (15 minutes): a product default, not from the SRS.

## API (signed-in user only)

| Call | Does |
|---|---|
| `GET /reminders/settings` | `{enabled, leadMinutes, leadOptions}` |
| `PUT /reminders/settings` | `{enabled, leadMinutes?}`; 400 for a missing `enabled` or another lead time |
| `POST /reminders/due` | the reminders due now and not shown yet; records them as shown |
| `GET /reminders/upcoming` | today's remaining timed activities, with `shown` |

## Database

Two new tables, created by Hibernate on start (`ddl-auto=update`); the same DDL is in
`db/manual/routine_reminders.sql` (idempotent, not run automatically). No existing table changes.
The previous release ignores both tables, so rolling back needs no database step.
