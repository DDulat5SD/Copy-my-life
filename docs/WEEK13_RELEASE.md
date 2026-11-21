# Week 13 — Final QA, Feature Freeze and Beta Release Preparation

Date: 2026-11-21 · Branch checked: `main` at `a392597` (after pull requests #4–#7)

## 1. Feature freeze

**Status: in effect from 2026-11-21.** The functionality on `main` at `a392597` is frozen:

- all 27 user stories in the [SRS](SRS.md#7-implementation-status) are implemented;
- from now on only fixes for documented defects are accepted, each in its own pull request that
  names the defect (GitHub issue or section 3 below);
- no new features, refactoring or dependency upgrades before the beta release.

Week 11 ([DEPLOY.md](DEPLOY.md)) and Week 12 ([README](../README.md), [SRS](SRS.md)) documentation is
unchanged by this week's work except where noted.

## 2. Final QA results

**Basis.** No Week 10 manual QA test cases exist in this repository or the original application
repository, so the cases below follow the SRS acceptance criteria. They complement the automated
regression suite (about 1,000 tests), which last ran in full on GitHub Actions for pull request #5
([run 44877455405](https://github.com/DDulat5SD/Copy-my-life/actions/runs/44877455405), success, no
skipped test classes) and now runs before every production release. It was not rerun for this report.

**Method.** Run on 2026-11-21 against the live services through their public HTTP API and pages.
Staging: real writes with dedicated QA accounts (`qa-week13-*@invalid.test`). Production: **GET
requests only**; no account was created and no data was changed, so cases that need an account are
NOT VERIFIED there.

| ID | Area | Staging | Production |
|---|---|---|---|
| QA-01 | Homepage and navigation | **PASS**: `/`, `/main.html`, `/Explore.html`, `/login.html`, `/signin.html`, `/profile.html`, `/routine.html`, `/forgot-password.html` all 200 | **PASS**: same 8 pages, all 200 |
| QA-02 | Registration | **PASS**: two new accounts 200; the same e-mail in other letter case 409; invalid name, e-mail and password 400 | NOT VERIFIED (would create a production account) |
| QA-03 | Login and authentication | **PASS**: wrong password 401 with the generic message "Invalid email or password. Please try again."; correct login 200 with a token; `/users/me` 200 with the token, 403 without | NOT VERIFIED (needs an account) |
| QA-04 | Logout | **PASS**: logout 204; the same token is then refused on `/users/me` (403) | NOT VERIFIED |
| QA-05 | Profile | **PASS**: `PUT /users/me` 200; name, profession, bio, interests and goals all read back unchanged | NOT VERIFIED |
| QA-06 | Routine management | **PASS**: create 200; add a timed activity with repeat days 200; blank title refused 400; edit 200; the activity is listed | NOT VERIFIED |
| QA-07 | Explore and public routines | **PASS**: a routine made public appears on Explore for a visitor and opens without signing in (200); 43 built-in routines | **PASS**: `/routines/public` 200 (4 public routines), `/routines/built-in` 200 (43), `/site-stats` 200 |
| QA-08 | Copying routines | **PASS**: a second member copies the public routine (200, new id, activity included); the original still opens | NOT VERIFIED |
| QA-09 | Habit tracking and progress | **PASS**: start a routine, start an activity, complete it after its duration, skip the next; after reload the run shows COMPLETED and SKIPPED; `/progress` 50 % (1 of 2), streak 1 day; status level counts 1 completed activity | NOT VERIFIED |
| QA-10 | Privacy and access control | **PASS**: after making it private, another member gets 404 on view, copy and edit; a visitor 404; the owner 200; it leaves Explore; a member gets 403 on the admin API | **PASS**: without a token `/users/me`, `/admin-api/reports` and `/progress` all 403 |
| QA-11 | AI service health and integration | **PASS**: `/assistant/plans` 200 with `examples.status = OK` (3 similar routines ranked by the AI service over the private network); `/users/me/lifestyle-matches` 200 (`NO_CANDIDATES`, as the QA accounts have no matching data) | NOT VERIFIED (the AI service is private and the endpoints need an account) |

**Counts:** 14 PASS · 0 FAIL · 8 NOT VERIFIED (all production cases that need an account or write data).

Three first-run results were wrong *test inputs*, not application defects, and were rerun with valid data:
a routine can only start when its activities add up to exactly its duration; an activity can be
completed only after its duration has passed and only in order; my automated leak check matched the
word "password" in the generic error message.

## 3. Bug and issue summary

**GitHub Issues:** none open or closed in `DDulat5SD/Copy-my-life` (checked 2026-11-21).

**New defects found in Week 13 QA:** none.

**Defects resolved during Weeks 11–13** (with evidence):

| Defect | Severity | Resolution |
|---|---|---|
| Routines of banned members were still visible on some read paths | Major | Fixed before the integration (`RoutineAccessService.canViewRoutine`; `BannedAuthorRoutineVisibilityRegressionTest`) |
| Staging sign-up answered 500 (`Illegal base64 character`) | Major (staging only) | The staging `JWT_SECRET` contained a carriage return from OpenSSL on Windows; regenerated; documented in [DEPLOY.md](DEPLOY.md#common-problems-and-solutions) |
| `.env.example` switched the integration tests on for a plain `mvnw test` | Minor | Fixed in pull request #7 |
| Staging backend could not reach the AI service after the first deploy | Minor (setup) | Redeploy order documented in DEPLOY.md |

**Open items (no fix in the freeze):**

| Item | Severity | Release decision |
|---|---|---|
| Staging database lacks the case-insensitive unique e-mail index `users_email_lower_uq` (production has it); simultaneous duplicate sign-ups could succeed on staging | Minor (staging only) | Not a release blocker. Apply `CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_uq ON users (lower(email));` on staging after approval |
| The first release from this repository removes the ten Halloween cover images that production serves today (`/img/halloween/01-billy.jpg` is 200 on production, 404 on staging) | Minor (cosmetic; letter placeholders appear) | **Accepted** by the project owner on 2026-11-21; released with placeholders |
| Video posts are not supported (images up to 5 MB); NFR-03 not met | Minor (documented limitation) | Accepted for the beta; outside the user stories |
| Performance and usability targets (NFR-01, -02, -06) not measured formally | Minor | Accepted for the beta |
| One old CI run (pull request #7, run 44891545923) was shown as in progress although `ci.yml` was removed | Process, not product | Resolved: the run has completed |

## 4. Critical and Major release blockers

**Critical: 0 · Major: 0** open defects.

The production release is held back by setup, not by defects (section 5).

## 5. Beta release readiness (before the release)

*This section records the state before the release; the outcome is in [section 8](#8-release-result).*

**Code and staging: ready.** Every QA case run passed and no defect is open above Minor.

**Production release: not yet possible.** The release workflow is correct, but its prerequisites are missing:

- `.github/workflows/release.yml` runs only for tags matching `vMAJOR.MINOR.PATCH`
  (`v[0-9]+.[0-9]+.[0-9]+`). A pre-release suffix such as `v1.0.0-beta` would **not** trigger it.
- It checks the tag is on `main`, runs the full backend suite (and the AI tests when `ai/` changed),
  and deploys only if they pass. It uses the pinned Railway CLI 5.71.0 (`railway up --ci`), then
  runs a read-only smoke test. Releases run one at a time and are never cancelled midway.
- It fails safely: without the `RAILWAY_TOKEN` secret, the deploy jobs stop before any upload.
- **Missing:** the GitHub environment `production` does not exist yet (only `melodious-illumination /
  staging`, created by Railway), so `RAILWAY_TOKEN` is not configured. No tag exists.

**Prepared beta tag:** `v0.9.0`, the first public beta (major version 0). It will be created on `main`
after this pull request is merged. It has **not** been created or pushed, and no beta has been
published.

```bash
git switch main && git pull
git tag -a v0.9.0 -m "CopyMyLife 0.9.0 beta"
git push origin v0.9.0     # only after the one-time setup in DEPLOY.md
```

## 6. Manual actions

Done on 2026-11-21: this pull request merged (#8); the stuck run finished; Halloween placeholders
accepted; release setup (Railway project token, GitHub environment `production` with `RAILWAY_TOKEN`);
`v0.9.0` tagged and released; the old `Elsico12/CopyMyLife` source disconnected from production.

Still open (none blocks the beta):

1. **Optional:**
   - approve applying `users_email_lower_uq` on staging;
   - remove the staging QA accounts (`*@invalid.test`) with an administrator account;
   - repository owner: protect `main` (require a pull request; no status check, since PR CI was removed).

## 7. Evidence for the instructor

| Evidence | Link |
|---|---|
| Production | https://copymylife-production.up.railway.app |
| Staging | https://copymylife-staging-staging.up.railway.app |
| Last full automated test run (CI, pull request #5) | https://github.com/DDulat5SD/Copy-my-life/actions/runs/44877455405 |
| Integration of the application (PR #4) | https://github.com/DDulat5SD/Copy-my-life/pull/4 |
| CI and test gate (PR #5) | https://github.com/DDulat5SD/Copy-my-life/pull/5 |
| Tag-based release workflow and deployment docs (PR #6) | https://github.com/DDulat5SD/Copy-my-life/pull/6 |
| README, SRS implementation status (PR #7) | https://github.com/DDulat5SD/Copy-my-life/pull/7 |
| Requirements and implementation status | [SRS.md](SRS.md) |
| Deployment, release and rollback | [DEPLOY.md](DEPLOY.md) |
| Release notes | [RELEASE_NOTES.md](RELEASE_NOTES.md) |
| Week 13 QA and release preparation (PR #8) | https://github.com/DDulat5SD/Copy-my-life/pull/8 |
| Release workflow fixes (PR #9) | https://github.com/DDulat5SD/Copy-my-life/pull/9 |
| Release run for `v0.9.0` | https://github.com/DDulat5SD/Copy-my-life/actions/runs/44900413857 |
| Beta release | https://github.com/DDulat5SD/Copy-my-life/releases/tag/v0.9.0 |

## 8. Release result

**`v0.9.0` is live on production (2026-11-21).**

| | |
|---|---|
| Tag | `v0.9.0` (annotated) → `2ad3722` on `main` (merge of pull request #9) |
| Release run | [44900413857](https://github.com/DDulat5SD/Copy-my-life/actions/runs/44900413857): **success** |
| GitHub release | https://github.com/DDulat5SD/Copy-my-life/releases/tag/v0.9.0 (pre-release) |

**Workflow jobs** (all success): tagged commit verified (on `main`) · AI service tests (1 min) ·
backend build and full test suite with no skipped test classes (52 min) · AI service deployed and
waited for until live · backend and frontend deployed, waited for until live, smoke test passed.

Pull request #9 fixed two gaps found during the preflight: `railway up` now names the Railway project
explicitly (a fresh runner has no linked project), and each deploy job waits for Railway to report the
new deployment `SUCCESS`, so the backend never deploys before the AI service is live and the smoke test
cannot pass against the previous deployment.

**Railway production after the release**

| Service | Deployment | Before |
|---|---|---|
| `CopyMyLife` (backend and frontend) | `4e49fe67` SUCCESS; started in 6.2 s; no schema change, no data change, no errors in the startup log | `a473aefb` (`0790137`) |
| `copymylife-ai` | `328f88fe` SUCCESS; model loaded (revision `e8f8c211`); `/health` 200 | `cc48b292` (`38ad257`) |

**Verification** (read-only; no account created, no data changed):

- All 8 pages 200; `/routines/built-in` 200 (43 routines), `/routines/public` 200 (4), `/site-stats`,
  `/categories` and `/top-routines` 200; `/users/me`, `/admin-api/reports` and `/progress` 403 without a token.
- `/img/halloween/01-billy.jpg` is now 404, as expected; the Halloween routines show letter placeholders.
- The PostgreSQL volume (`/var/lib/postgresql/data`, 118 MB) and the uploads volume (`/app/uploads`,
  32 MB) are the same volumes as before and still attached.
- Railway variables of the production backend (14), the AI service (2) and staging are unchanged
  (compared by fingerprint, without printing values). No database credentials were changed.
- Staging is unaffected: its own database, volume, variables and private network; it still deploys
  `main` automatically (now `2ad3722`).

**Deployment sources.** The first Railway token, exposed in a screenshot, no longer exists; the only
project token was created after it, is scoped to `production`, and is the one the release used. After
the verification the old `Elsico12/CopyMyLife` GitHub source was disconnected from both production
services (settings such as the AI service's root directory `/ai` and its health check kept; nothing
redeployed). Production now changes only through a tested version tag; staging keeps its
`DDulat5SD/Copy-my-life` triggers.

**Known limitations** are listed in [RELEASE_NOTES.md](RELEASE_NOTES.md#known-limitations).

**Rollback.** Railway → production → the service → **Deployments** → the previous good deployment →
**Redeploy**, the backend and, if needed, the AI service (before `v0.9.0`: backend `a473aefb`, AI
`cc48b292`). Or tag the last good commit on `main` as the next patch version (for example `v0.9.1`);
the workflow tests it again before deploying. A rollback needs no database step. See
[DEPLOY.md](DEPLOY.md#rollback).
