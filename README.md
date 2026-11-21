# CopyMyLife

CopyMyLife is a social web platform for sharing and copying productive daily routines. People
publish how they really organise their day: timed routines, habits, to-do lists and photo posts.
Others follow them, copy a whole routine or a single habit, adapt it to their own schedule and track
how consistently they keep it.

**Live:** production https://copymylife-production.up.railway.app · staging https://copymylife-staging-staging.up.railway.app

## Problem and objectives

Social media shows people's results, not the schedule behind them, so there is no simple way to see
*how* someone structures their day and turn it into a personal plan. CopyMyLife aims to:

- make routines visible: timed activities on a day or week timeline, not just a photo of the outcome;
- make them copyable: a full routine or one habit, adjusted to your own times and days;
- make them trackable: daily completion, streaks, progress and status levels;
- keep people in control: public or private content, reporting and administrator moderation.

The full requirements are in the [Software Requirements Specification](docs/SRS.md).

## Main features

- **Accounts and profiles:** sign up, sign in, sign out; profile with photo, profession, interests, goals and bio.
- **Discovery:** Explore public routines; search and filter by keyword, profession, goal, category or habit; routine details on a timeline; a Top Routines leaderboard by category and time of day.
- **Creating:** personal routines with timed activities, durations, repeat days and notes; add, edit, reorder, pause and delete activities; a weekly calendar.
- **Copying:** copy a full public routine, copy a single habit into one of your routines, adapt a copied routine's times, durations and days before saving.
- **Social:** follow and unfollow creators, friend requests, likes and comments on routines and posts, saved routines and saved posts, community voting on a routine's category.
- **Tracking:** mark today's activities completed, skipped or postponed; completion percentage, streaks and weekly progress; status levels; in-app reminders before timed activities.
- **Creators:** follower count and how often each public routine was copied.
- **Privacy and moderation:** public or private routines, to-do lists and posts; report a post, comment, routine or user; an administrator area for profiles, routines, posts, categories and reports.
- **AI (separate Python service):** *Find Similar People* (lifestyle matching) and a routine assistant that plans a day and suggests similar public routines. Without the service, the rest of the site keeps working.

## Technology stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Security, Data JPA, Validation, Mail), Hibernate, JJWT 0.13 |
| Frontend | HTML, CSS and plain JavaScript, served by Spring Boot from `src/main/resources/static` |
| Database | PostgreSQL (18 on Railway) |
| AI service | Python 3.13, FastAPI, Uvicorn, ONNX Runtime, the Apache-2.0 model `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` |
| E-mail | Mailjet API (password-reset e-mails) |
| Build and tests | Maven (wrapper included), JUnit 5, Spring Boot Test, Node.js + headless Chrome browser checks, pytest |
| Hosting and releases | Railway (production and staging), GitHub Actions for tagged production releases |

## Architecture

```
 browser ──HTTPS──▶ Spring Boot backend + static frontend ──JDBC──▶ PostgreSQL
                         │  uploads on a volume (/app/uploads)
                         └──HTTP + X-AI-Token──▶ Python AI service (FastAPI + ONNX model)
```

- The backend exposes a JSON API and serves the pages; authentication uses JWT tokens.
- On start, Hibernate creates and updates the database schema (`ddl-auto=update`). A few startup
  classes add constraints Hibernate cannot express, and seeders add the built-in routines and categories.
- The AI service is optional and private. The backend calls it for lifestyle matching (`/match`) and
  semantic search (`/similar`); see [ai/README.md](ai/README.md).

## Repository layout

| Path | Contents |
|---|---|
| `src/main/java/com/copymylife/copymylife/` | backend: `controller`, `service`, `repository`, `model`, `dto`, `config` (security, startup constraints, seeders), `assistant`, `exception` |
| `src/main/resources/static/` | the frontend that is served (HTML, CSS, JavaScript, images) |
| `src/main/resources/application.properties` | configuration; every credential comes from an environment variable |
| `src/test/java/`, `src/test/e2e/` | unit, integration and browser regression tests |
| `ai/` | Python AI service, its Dockerfile and tests |
| `db/manual/` | hand-written SQL for the schema changes the application also makes itself |
| `docs/` | SRS, ERD, traceability table, user flow and Figma link, technical notes |
| `*.html`, `*.css`, `static/` (repository root) | earlier frontend prototypes, kept for reference and **not** served (see [docs/INTEGRATION.md](docs/INTEGRATION.md)) |

## Requirements

- **Java 21** (JDK). Maven is not needed; use the included wrapper `mvnw` / `mvnw.cmd`.
- **PostgreSQL** 15 or newer (18 is used in production) and its `psql` client.
- **Git**.
- Optional, for the AI service: **Python 3.13**.
- Optional, for the browser regression tests: **Node.js 22 or newer** and **Google Chrome**.

## Local installation

### 1. Clone

```bash
git clone https://github.com/DDulat5SD/Copy-my-life.git
cd Copy-my-life
```

### 2. Create the database

Connect to your PostgreSQL server as an administrator (for example `psql -U postgres`) and run:

```sql
CREATE ROLE copymylife LOGIN PASSWORD 'change-me';
CREATE DATABASE copymylife OWNER copymylife
    TEMPLATE template0 ENCODING 'UTF8' LOCALE_PROVIDER icu ICU_LOCALE 'en-US' LOCALE 'C';
```

The ICU locale makes case-insensitive search work for non-Latin text (for example Kazakh and Russian).
Use your own password; it only has to match `DB_PASSWORD` below. The application creates every table
on first start.

### 3. Configure environment variables

```bash
cp .env.example .env
```

Edit `.env` (it is git-ignored; never commit it):

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: your database from step 2.
- `JWT_SECRET`: **replace the placeholder**. It must be Base64; generate one with
  `openssl rand -base64 64 | tr -d '\r\n'`.
- `MAIL_*`, `MAILJET_*`: required at startup but may stay as placeholders locally; then only
  *Forgot password* cannot send its e-mail.
- `AI_*`: only if you run the AI service (step 6).

Spring Boot does not read `.env` by itself, so load it into the shell that starts the backend:

```bash
# Git Bash, Linux, macOS
set -a; . ./.env; set +a
```

```powershell
# PowerShell
Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object { $k, $v = $_ -split '=', 2; Set-Item "env:$k" $v }
```

Every variable is described in [.env.example](.env.example).

### 4. Start the backend and frontend

```bash
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

Open http://localhost:8080. The log ends with `Started CopyMyLifeApplication`. The frontend needs no
separate build or server.

To build a jar instead: `./mvnw -DskipTests package`, then `java -jar target/copymylife-0.0.1-SNAPSHOT.jar`
(with the same environment variables loaded).

### 5. Create an administrator (optional)

New accounts are ordinary users. To use the administrator area, sign up and then promote the account
in the database:

```sql
UPDATE users SET role = 'ADMIN' WHERE email = 'you@example.com';
```

### 6. Start the AI service (optional)

```bash
cd ai
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements-dev.txt        # Linux/macOS: .venv/bin/python
.venv/Scripts/python download_model.py models/multilingual-minilm  # about 128 MB, checked by SHA-256
.venv/Scripts/python app.py                                        # http://127.0.0.1:8001
```

The backend finds it at `AI_MATCHING_URL` (default `http://127.0.0.1:8001`). If you set `CML_AI_TOKEN`
for the AI service, set the same value as `AI_MATCHING_TOKEN` for the backend.

## Running tests

| What | Command | Needs |
|---|---|---|
| Build and unit tests | `./mvnw test` | the environment from step 3 and the database from step 2 (the application-context test starts the app) |
| Full backend suite (integration + browser) | see below | a separate `*_test` database, Node.js 22+, Chrome |
| AI service | `cd ai && env -u CML_AI_TOKEN .venv/Scripts/python -m pytest tests` | the model from step 6; run without `CML_AI_TOKEN` set (the tests send no token), e.g. in a shell where `.env` is not loaded |

The integration tests switch themselves off unless `TEST_DB_URL` points at a **local** database whose
name ends in `_test`, so they can never touch a real database:

```sql
CREATE DATABASE copymylife_test OWNER copymylife
    TEMPLATE template0 ENCODING 'UTF8' LOCALE_PROVIDER icu ICU_LOCALE 'en-US' LOCALE 'C';
```

```bash
export TEST_DB_URL=jdbc:postgresql://localhost:5432/copymylife_test
export TEST_DB_USERNAME=copymylife TEST_DB_PASSWORD=change-me
./mvnw verify
python .github/scripts/check_test_reports.py target/surefire-reports src/test/java \
    --allow-skip LifestyleMatchingAiIntegrationTest
```

Maven still passes when tests switch themselves off, so the last command checks that every test
class really ran. The browser checks find Chrome in its usual install location, or at `CHROME_BIN`.
The full suite takes a while, so GitHub does not run it on pull requests; it runs automatically only
for a production release ([`.github/workflows/release.yml`](.github/workflows/release.yml)).

## Deployment

Railway hosts two separate environments, each with its own backend, AI service, PostgreSQL database
and secrets. Merged pull requests deploy to staging; pushing a version tag (`v1.2.0`) tests that commit
and, if every test passes, deploys it to production. Deployment, the release process and rollback are
described in [docs/DEPLOY.md](docs/DEPLOY.md).

| Environment | URL |
|---|---|
| Production | https://copymylife-production.up.railway.app |
| Staging | https://copymylife-staging-staging.up.railway.app |

## Documentation

- [Software Requirements Specification](docs/SRS.md), with the implementation status of every user story
- [ERD](docs/CopyMyLife%20ERD.png)
- [Traceability table](docs/Traceability_Table.md)
- [User flow and Figma link](docs/UserFlowAndFigma)
- [Deployment, releases and rollback](docs/DEPLOY.md)
- Quality assurance: [Running tests](#running-tests), the tests run by the
  [release workflow](.github/workflows/release.yml), the per-story notes in `docs/us-*.md` and the
  [Week 13 final QA](docs/WEEK13_RELEASE.md)
- [Release notes for 0.9.0 beta](docs/RELEASE_NOTES.md)
- [How the application was integrated into this repository](docs/INTEGRATION.md)
- [AI service](ai/README.md)

## Team

- Zamanbek Sailau — Backend Developer
- Beknur Kurishbayev — Frontend Developer
- Daulet Akhmetzhanuly — UI/UX Designer
- Dulat Daniyar — Project Manager and Frontend Developer

## Known limitations

- **Video posts are not supported.** Posts take images up to 5 MB; the SRS performance target for
  50 MB videos (NFR-03) is not met.
- **Reminders appear only inside the app,** on a signed-in page that is open in the browser. There
  are no e-mail, SMS or push notifications.
- **E-mail** is used only for password reset and needs real Mailjet keys. Staging has mail disabled
  on purpose, so *Forgot password* fails there.
- **The AI features need the separate AI service.** Without it, *Find Similar People* and the
  assistant's similar-routine suggestions say they are unavailable; everything else works.
- **The built-in Halloween routines have no cover images** in this repository (the original images
  could not be redistributed); the pages show letter placeholders.
- **Performance and usability targets** in the SRS (page load times, first-routine time) have not
  been measured formally.
- **Legacy frontend prototypes** remain in the repository root and `static/`; they are not served
  and are listed in [docs/INTEGRATION.md](docs/INTEGRATION.md) for a team decision.
