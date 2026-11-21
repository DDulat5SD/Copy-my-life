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
| The first release from this repository removes the ten Halloween cover images that production serves today (`/img/halloween/01-billy.jpg` is 200 on production, 404 on staging) | Minor (cosmetic; letter placeholders appear) | **Decision required before tagging**: accept the placeholders, or add licensed images first |
| Video posts are not supported (images up to 5 MB); NFR-03 not met | Minor (documented limitation) | Accepted for the beta; outside the user stories |
| Performance and usability targets (NFR-01, -02, -06) not measured formally | Minor | Accepted for the beta |
| One old CI run (pull request #7, run 44891545923) is still shown as in progress although `ci.yml` was removed | Process, not product | Cancel it in GitHub → Actions |

## 4. Critical and Major release blockers

**Critical: 0 · Major: 0** open defects.

The production release is held back by setup, not by defects (section 5).

## 5. Beta release readiness

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

## 6. Remaining manual actions

1. **Review and merge** this pull request (documentation only).
2. **Cancel** the stuck run 44891545923 (GitHub → Actions → the run → *Cancel workflow run*).
3. **Halloween images:** decide (section 3).
4. **Release setup** ([DEPLOY.md → One-time setup](DEPLOY.md#one-time-setup-before-the-first-release)):
   - Railway project token for `production`;
   - GitHub environment `production` with the secret `RAILWAY_TOKEN`.
5. **Tag and push `v0.9.0`**, then watch the *Release* run: tests, deploy, smoke test.
6. **Only after it succeeds:** disconnect the old `Elsico12/CopyMyLife` source from the production
   services in Railway.
7. **Optional:**
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
