# CopyMyLife 0.9.0 beta — Release Notes

**Status: released on 2026-11-21.** Tag `v0.9.0` on commit `2ad3722` passed the release workflow
([run 44900413857](https://github.com/DDulat5SD/Copy-my-life/actions/runs/44900413857)) and is live on production. Details are in
[WEEK13_RELEASE.md](WEEK13_RELEASE.md#8-release-result).

| | |
|---|---|
| Version | 0.9.0 (first public beta) |
| Tag | `v0.9.0` → `2ad3722` on `main` |
| GitHub release | https://github.com/DDulat5SD/Copy-my-life/releases/tag/v0.9.0 (pre-release) |
| Production | https://copymylife-production.up.railway.app |
| Staging | https://copymylife-staging-staging.up.railway.app |

## Summary

CopyMyLife is a social web platform for sharing, copying and tracking daily routines. This beta
contains every user story of the [SRS](SRS.md#7-implementation-status) (27 of 27, all Must, Should and
Could stories). It is the first release built and deployed from the official team repository
through the tag-based release workflow.

## Implemented features

- **Accounts:** sign up, sign in, sign out with token revocation; profile with photo, profession,
  interests, goals and bio.
- **Discovery:** Explore public routines; search and filter by keyword, profession, goal, category or
  habit; routine timeline; Top Routines leaderboard.
- **Routines:** create routines with timed activities, durations, repeat days and notes; edit,
  reorder, pause and delete activities; weekly calendar.
- **Copying:** copy a full routine or a single habit; adapt a copy's times, durations and days.
- **Social:** follow, friends, likes and comments on routines and posts, saved routines and posts,
  community category voting, creator statistics.
- **Tracking:** today's run with completed, skipped and postponed activities; completion
  percentage, streaks, weekly progress; status levels; in-app reminders.
- **Privacy and moderation:** public or private routines, to-do lists and posts; reports;
  administrator area.
- **AI service:** lifestyle matching (*Find Similar People*) and a routine assistant with similar
  public routines.

## QA results

Final QA on 2026-11-21 ([details and evidence](WEEK13_RELEASE.md#2-final-qa-results)):
**14 PASS · 0 FAIL · 8 NOT VERIFIED.** All NOT VERIFIED cases are on production, where only read-only
checks were allowed. The release workflow then ran the full backend suite (about 1,000 tests) and the
AI service tests on the tagged commit: all passed, with no skipped test classes ([run 44900413857](https://github.com/DDulat5SD/Copy-my-life/actions/runs/44900413857)).

## Resolved bugs

- Routines of banned members were visible on some read paths (fixed; covered by
  `BannedAuthorRoutineVisibilityRegressionTest`).
- Staging sign-up failed with HTTP 500 because of a carriage return in the JWT secret (configuration
  fixed; prevention documented in [DEPLOY.md](DEPLOY.md#common-problems-and-solutions)).
- `.env.example` enabled the integration tests for a plain `mvnw test` (fixed).

## Known limitations

- Video posts are not supported; posts take images up to 5 MB.
- Reminders appear only inside the app, on an open signed-in page.
- E-mail is used only for password reset; staging has mail disabled on purpose.
- AI features need the separate AI service; without it the rest of the site works.
- The ten built-in Halloween routines have no cover images in this repository, so they show letter
  placeholders (accepted for the beta). Since this release production no longer serves the old images.
- Performance and usability targets in the SRS have not been measured formally.

## Unresolved issues

No Critical or Major defects are open. Minor items and their release decisions are listed in
[WEEK13_RELEASE.md](WEEK13_RELEASE.md#3-bug-and-issue-summary).

## How to release

```bash
git switch main && git pull
git tag -a v0.9.0 -m "CopyMyLife 0.9.0 beta"
git push origin v0.9.0
```

The one-time setup in [DEPLOY.md](DEPLOY.md#one-time-setup-before-the-first-release) is done: a
Railway project token for `production` is stored as `RAILWAY_TOKEN` on the GitHub environment
`production`. Production now deploys only from tested version tags.

## Rollback

- **Fastest:** Railway → production → the service → **Deployments** → the previous good deployment →
  **Redeploy**. Do it for the backend and, if it was released, the AI service. Before `v0.9.0` the
  backend ran deployment `a473aefb` (commit `0790137`) and the AI service `cc48b292` (`38ad257`).
- **From Git:** tag the last good commit on `main` with a new patch version (for example `v0.9.1`); the
  release workflow tests it again before deploying.
- Releases do not change the database, volumes or Railway variables, so a code rollback needs no
  database step. Details are in [DEPLOY.md](DEPLOY.md#rollback).
