# Integration of the full application

This branch (`integration/full-application`) brings the complete, working CopyMyLife
application into the team repository while keeping the team's existing documents and history.

## Source

| | |
|---|---|
| Source repository | `Elsico12/CopyMyLife`, branch `main` |
| Source commit | `0790137` ("Hide banned members' routines from everyone else on every read path") |
| Production status | This is the commit currently deployed at https://copymylife-production.up.railway.app/ |
| Method | The committed tree of `0790137` was exported (`git archive`) and copied in as one new commit. The source repository's history was **not** merged, so the two unrelated histories stay separate. Its full history remains available in `Elsico12/CopyMyLife`. |

Uncommitted or untracked local files in the source working copy were not included, and neither
were the assets listed under [Excluded assets](#excluded-assets).

## What was added

| Path | Contents |
|---|---|
| `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Maven build (Spring Boot 4.1, Java 21) and wrapper |
| `src/main/java/` | Spring Boot backend: controllers, services, repositories, entities, security, seeders |
| `src/main/resources/application.properties` | Configuration; all credentials come from environment variables |
| `src/main/resources/static/` | The current frontend (HTML, CSS, JavaScript, images) served by Spring Boot |
| `src/test/java/`, `src/test/e2e/` | Regression tests and browser checks |
| `ai/` | Python AI service (lifestyle matching, routine assistant), its Dockerfile and `railway.json` |
| `db/manual/` | Hand-applied SQL migrations (schema, constraints and reference data only, no user data) |
| `docs/us-*.md`, `docs/ai-routine-assistant-v1.md` | Technical notes for the implemented user stories |
| `.env.example` | Every environment variable the application reads, with safe placeholders |
| `.gitattributes` | Line endings for `mvnw` / `*.cmd` |
| `.gitignore` | The team's rules, plus rules for secrets, uploads, dumps, backups and the AI model |

## Excluded assets

The ten Halloween routine cover images (`src/main/resources/static/img/halloween/01-billy.jpg` …
`10-jason-voorhees.jpg`) are **not** included. They depict copyrighted film characters and no
license or source is recorded for them, so they cannot be redistributed in a public repository.

Effect on the application:

- The built-in Halloween routines are still seeded (`HalloweenRoutineCatalog`, `HalloweenRoutineSeeder`)
  and still reference `img/halloween/*.jpg`, which now return 404.
- On Explore (Halloween mode) the cards fall back to a letter placeholder, the existing behavior for
  any image that fails to load.
- The routine detail page falls back to its colored tile with the routine's initial.
- Nothing else breaks; the routines can still be viewed, copied and tracked.
- Tests that mention `img/halloween/01-billy.jpg` only check URL validation, not the file, so they are unaffected.

To restore covers, add images the team has the right to publish under the same file names,
or change the paths in `HalloweenRoutineCatalog.java` and `halloween-data.js` / `Explore.html`.

## Team files kept unchanged

`README.md`, `docs/SRS.md`, `docs/CopyMyLife ERD.png`, `docs/Traceability_Table.md`,
`docs/UserFlowAndFigma`, and all existing commits.

## Legacy frontend files: kept, decision pending

The team's earlier static pages are still in the repository root and in `static/`:

| Legacy file | Current version in the application |
|---|---|
| `main.*`, `profile.*`, `forgot-password.*` (root) | `src/main/resources/static/` (same names, newer content) |
| `static/Explore.html`, `static/Explore_Style.css`, `static/login.*`, `static/signin.*`, `static/main.*`, `static/profile.*`, `static/forgot-password.*`, `static/routine.html` | `src/main/resources/static/` (same names, newer content) |
| `View-routine-page.html`, `View-routine-page.js`, `view-routine-page.css` | Early prototype of `src/main/resources/static/routine.html` |

**Decision:** they are not deleted in this integration. Spring Boot only serves files from
`src/main/resources/static/` (plus uploaded files under `/uploads/**`), and Maven does not package the
root or `static/` folders, so these files have no effect on the running application. They can be
confused with the real frontend, though. The team should agree in a separate pull request whether to
move them to a `legacy/` folder (keeping their history) or remove them.

## Repository cleanup audit (Week 12)

Checked on `main` before the Week 12 documentation update. Nothing was deleted.

| Check | Result |
|---|---|
| Build output, caches, logs, backups, IDE files tracked in Git | None (`target/`, `__pycache__/`, `*.class`, `*.jar`, `*.log`, `*.bak`, `.idea/` and the AI model are all git-ignored) |
| Secrets (database URLs with passwords, cloud or GitHub tokens, private keys) | None found in tracked files |
| Frontend files in `src/main/resources/static/` that nothing references | None: every page, script, stylesheet and image is referenced by name |
| Legacy pages referenced by the application | None: only this document mentions them (`Explore_Style.css` also exists in the served frontend under the same name) |
| Exact duplicates among the legacy pages | `forgot-password.html` and `forgot-password.css` in the root are byte-identical to the copies in `static/` |
| Root `main.*` and `profile.*` versus `static/main.*` and `static/profile.*` | Different versions of the same prototypes |

The legacy pages are the team's earlier deliverables, so they stay until the team decides; the two
byte-identical `forgot-password` files are the safest first removal if the team chooses to clean up.
