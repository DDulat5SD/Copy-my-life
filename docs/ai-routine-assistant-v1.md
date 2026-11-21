# Personal AI Routine Assistant — version 1

Local work only; not pushed or deployed. This note is for review before any production deployment.

## What it does

| Feature | Endpoint (all need a signed-in member; administrators get 403) |
|---|---|
| A. Generate a day plan (preview only) | `POST /assistant/plans` |
| A. Save a previewed plan as a **new** routine | `POST /assistant/plans/save` |
| Quick fill of the form from one sentence (word patterns, not an LLM) | `POST /assistant/parse` |
| B. Saved planning preferences | `GET` / `PUT /assistant/preferences` |
| C. Review a routine (suggestions only) | `GET /assistant/routines/{id}/review` |
| C. Preview / apply the changes the user approved | `POST /assistant/routines/{id}/changes/preview`, `.../changes/apply` |
| D. Planned versus measured length, per activity | `GET /assistant/insights/durations[?routineId=]` |
| D. Keep the planned length (suggestion rests until 3 new measured runs) | `POST /assistant/insights/durations/{routineHabitId}/keep` |
| D. Enter the actual length of a completed habit | `PUT /assistant/runs/{runId}/habits/{routineHabitId}/actual-minutes` |
| E. Daily summary | `GET /assistant/summary[?date=YYYY-MM-DD]` |

UI: the profile's **AI Assistant** section (menu between Progress and Friends), drawn by
`assistant.js` the first time it opens, with tabs Generate My Routine, Review My Routine, Find
Similar People (US-26, `lifestyle-matches.js`), Daily Summary, AI Suggestions and My Preferences.
Each tab has an address: `profile.html#ai-generate`, `#ai-review` (`?review=<routine id>` reviews
that routine), `#ai-matches`, `#ai-summary`, `#ai-suggestions`, `#ai-preferences`; Back and Forward
move between them. Links: Explore's one "AI Assistant" button, the routine page for its owner, and
the run page once a run is over. The former standalone pages `/assistant.html` and
`/lifestyle-matches.html` no longer exist; the server redirects them (302) to the matching tab.

## How it decides

* **Scheduling, review, duration analysis and summaries are fixed rules** in
  `com.copymylife.copymylife.assistant` (pure Java, unit-tested without a database):
  `RoutinePlanningEngine`, `RoutineReviewEngine`, `HabitDurationAnalyzer`, `DailySummaryEngine`,
  `PersonalizationEngine`, `RequestParser`. The same input always gives the same plan.
* **Personalisation order** for every preference: this request → saved preference → what the
  user's own tracked runs show (median start of meals/exercise, at least 3 completed runs) →
  default. Every value in a reply says which one it came from.
* **MiniLM** (the US-26 ONNX model, same Python service) is used only through the new
  `POST /similar`: public routine examples close in meaning to the user's goals and activities,
  and a check of each goal against the routine's activities. It is an embedding model: it
  measures closeness of meaning, it does not write text. Without the service the plan, review,
  insights and summary still work; the reply says what was not checked.
* **Actual length** is known only from the run timer (Start → Complete) or a length the user
  entered. A habit only marked done has no length. The timer cannot show a habit shorter than
  planned (Complete unlocks after the planned time) and includes any wait before Complete; a
  reading far beyond the plan (more than 3× and 60 min over) is left out as a forgotten timer.
  A suggestion needs at least 3 of the latest 10 measured runs, and most of them (60%) must differ
  from the plan by at least max(5 min, 20%).
* **User control**: plans are previews; saving always creates a new private routine (public only
  if ticked); review changes are applied only when approved, all-or-nothing, refused while the
  routine runs or when they would overlap; durations never change by themselves.

## Privacy

Examples come only from routines anyone may see: built-in ones and members' public routines that
are not hidden or deleted, whose author is not banned and whose profile is not hidden; never the
user's own. Only titles, categories, descriptions and activity titles are sent to the AI service
(the regression test checks that private, hidden, deleted, banned and hidden-profile content and
e-mail addresses are never sent). Reviews, insights, runs and summaries answer only about the
caller's own routines; another member's routine answers 404.

## Database (additive; Hibernate `ddl-auto=update` creates it on start)

`db/manual/routine_assistant.sql` has the same DDL and rollback notes:

* new table `assistant_preferences` (one row per user, `ON DELETE CASCADE`)
* new table `assistant_decisions` (user's answers to length suggestions, `ON DELETE CASCADE`)
* `habit_completions.planned_minutes` (nullable; set for new runs when a habit starts)
* `habit_completions.reported_minutes` (nullable; set only by the user)

No existing row is changed. The previous release ignores the new tables and columns.

## Production as checked on 2026-11-15 (read-only)

Railway project melodious-illumination / production runs three services:

| Service | Source | Deployed commit | Notes |
|---|---|---|---|
| CopyMyLife | GitHub Elsico12/CopyMyLife, repo root, Railpack | 0f36220 (2df6afe5, SUCCESS) | public domain copymylife-production.up.railway.app |
| copymylife-ai | same repo, root `/ai`, Dockerfile `/ai/Dockerfile`, healthcheck `/health` | 0f36220 (b8bcf399, SUCCESS) | no public domain; serves `POST /match` with 200 (US-26 works) |
| Postgres | postgres-ssl:18 | — | |

GitHub `origin/main` is 0f36220; the assistant is the local commits after it. The backend reaches
the AI service on the private network: `AI_MATCHING_URL` is an `http://….railway.internal:<PORT>`
address whose port is the AI service's `PORT`, and `AI_MATCHING_TOKEN` equals the AI service's
`CML_AI_TOKEN` (32+ characters). Both checked without printing the values.

Both services deploy from `main`, and the deployed manifests show no watch patterns (the
`/ai/**` pattern in `ai/railway.json` is not in effect), so one push rebuilds both. That is safe
in either order:

* new AI service, old backend: `/similar` is unused; `/match` is unchanged;
* new backend, old AI service: `/similar` answers 404, which the backend treats like an
  unavailable service, so examples and the goal check say "not available" for those minutes and
  everything else works.

Railway keeps the old container serving until the new one passes its healthcheck, and
`requirements.lock.txt`, `model-manifest.json` and `download_model.py` are unchanged, so the AI
image build reuses its package and model layers; only the code layer is new.

## Before a production deployment

1. A fresh production backup made by the owner (pg_dump custom format) into
   `C:\Users\ASUS\CopyMyLife-backups\copymylife-before-ai-assistant-<date>.dump`; then locally:
   SHA-256 noted, `pg_restore --list` readable, restored into a `*_test` database on the throwaway
   cluster, row counts recorded, and the new jar started on it to confirm the startup DDL is
   exactly the four items above with no row changed (as already rehearsed on the US-26 backup).
2. Java service: no new environment variable is required. The assistant reuses
   `AI_MATCHING_URL` / `AI_MATCHING_TOKEN`; `AI_ASSISTANT_TIMEOUT_SECONDS` (default 10) is optional.
3. Python service: no variable changes. The image must include `semantic_search.py` (it is in
   `ai/Dockerfile`; `test_the_image_contains_every_module_the_service_imports` guards it).
4. Push `main` (fast-forward from 0f36220), then watch both deployments: copymylife-ai SUCCESS
   with "model loaded" in its log; CopyMyLife SUCCESS with only the expected DDL and no INSERT,
   UPDATE or DELETE at startup.
5. After deploying: `/match` still answers 200 in the AI log when Find Similar People is used;
   open `profile.html#ai-generate` in both designs, generate a plan and see public examples with status OK
   (that proves `/similar` on the private network); check the run page and Explore.

## Rollback

* Code: redeploy 0f36220 on both services (Railway "Redeploy" of the previous deployment). The old
  backend never calls `/similar` and ignores the new tables and columns; the old AI service has
  no `/similar`, which the new backend tolerates.
* Database: nothing to undo for a code rollback. Dropping the new tables and columns
  (`db/manual/routine_assistant.sql`) is only for a permanent removal and loses users' saved
  preferences, decisions and entered lengths; restore from the backup only if data were damaged.

## Known limits of version 1

* The quick fill understands a fixed set of English (some Russian/Kazakh) phrases only.
* Activity kinds of existing habits are recognised from title words (EN/RU/KZ); the review says so.
* A postponed and resumed habit is timed from its last Start only.
* The generator plans one day pattern for the chosen days; commitments count on all chosen days.
* Summary counts habits of routines started that day; untouched routines are not counted.
* No LLM: explanations are fixed sentences filled with real values. The engines are separated so
  an LLM could later phrase them, without changing how times are calculated.
