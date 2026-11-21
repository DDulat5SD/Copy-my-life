# Deploying CopyMyLife

CopyMyLife runs on [Railway](https://railway.com) in the project `melodious-illumination`, with two
fully separate environments. Code reaches them only through GitHub:

| | Production | Staging |
|---|---|---|
| URL | https://copymylife-production.up.railway.app | https://copymylife-staging-staging.up.railway.app |
| Deploys | when a version tag (`v1.2.0`) is pushed (see [Releasing to production](#releasing-to-production)) | automatically, every time a pull request is merged into `main` |
| Database | its own PostgreSQL 18 | its own PostgreSQL 18 (`Postgres-oBp5`) |
| Secrets | its own | its own, never copied from production |

## Architecture

Each environment has three Railway services that talk over the environment's private network
(`*.railway.internal`). Staging and production are on different private networks and cannot reach
each other.

```
            browser
               │  HTTPS (public domain)
               ▼
   ┌───────────────────────┐   X-AI-Token over the private network   ┌──────────────────────┐
   │ Backend + frontend     │ ─────────────────────────────────────▶ │ AI service (Python)  │
   │ Spring Boot, Java 21   │   http://<ai>.railway.internal:8080    │ FastAPI + ONNX model │
   │ static pages served    │                                         │ no public domain     │
   │ from src/main/resources│                                         └──────────────────────┘
   │ volume: /app/uploads   │
   └──────────┬────────────┘
              │ JDBC over the private network
              ▼
   ┌───────────────────────┐
   │ PostgreSQL 18          │
   │ volume: its data       │
   └───────────────────────┘
```

| Service | Production | Staging | Source | Build |
|---|---|---|---|---|
| Backend + frontend | `CopyMyLife` | `CopyMyLife-staging` | repository root | Railpack (Java / Maven), port 8080 |
| AI service | `copymylife-ai` | `copymylife-ai-staging` | `/ai` (root directory) | `/ai/Dockerfile`, health check `/health` |
| Database | `Postgres` | `Postgres-oBp5` | Railway PostgreSQL template | image `postgres-ssl:18` |

The frontend has no service of its own: Spring Boot serves the HTML, CSS and JavaScript in
`src/main/resources/static`. The files in the repository root and in `static/` are earlier
prototypes and are not deployed (see [INTEGRATION.md](INTEGRATION.md)).

## Environment variables

[`.env.example`](../.env.example) lists every variable with a placeholder. On Railway they are set per
service and per environment.

**Backend** (`CopyMyLife`, `CopyMyLife-staging`):

| Variable | Value on Railway |
|---|---|
| `DB_URL` | `jdbc:postgresql://${{<db>.PGHOST}}:${{<db>.PGPORT}}/${{<db>.PGDATABASE}}` (a Railway reference to the environment's own database) |
| `DB_USERNAME`, `DB_PASSWORD` | `${{<db>.PGUSER}}`, `${{<db>.PGPASSWORD}}` |
| `JWT_SECRET` | new random value per environment, Base64, at least 32 bytes (see [Problems](#common-problems-and-solutions)) |
| `APP_BASE_URL` | the environment's public URL (used in password-reset links) |
| `PORT` | `8080` |
| `MAILJET_API_KEY`, `MAILJET_SECRET_KEY`, `MAILJET_SENDER_EMAIL` | real keys in production only |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | required at startup; SMTP is configured but not used to send |
| `AI_MATCHING_URL` | `http://${{<ai>.RAILWAY_PRIVATE_DOMAIN}}:${{<ai>.PORT}}` |
| `AI_MATCHING_TOKEN` | the same value as `CML_AI_TOKEN` on that environment's AI service |
| `AI_MATCHING_TIMEOUT_SECONDS` | `30` |

**AI service** (`copymylife-ai`, `copymylife-ai-staging`): `CML_AI_TOKEN` (random, 64 hex characters,
generate with `openssl rand -hex 32`) and `PORT=8080`. The Docker image already sets
`CML_AI_ALLOW_REMOTE=1`, `CML_AI_HOST=::` and the model directory.

**Staging differences:** the Mailjet and SMTP values are deliberately invalid
(`staging-mail-disabled`, `SPRING_MAIL_HOST=mail-disabled.invalid`), so staging can never email a
real person. As a result *Forgot password* answers with an error on staging; that is expected.

## Setting up an environment from scratch

1. In Railway, create the environment as an **empty** environment. Never use *Duplicate*: it copies
   production variables and secrets.
2. Add a PostgreSQL database (**New → Database → PostgreSQL**). Give it no public TCP proxy.
3. Add the backend service from the GitHub repository `DDulat5SD/Copy-my-life`, branch `main`:
   - add a volume mounted at `/app/uploads`;
   - set the backend variables above, using references to *this* environment's database;
   - generate a public domain on port 8080 and set `APP_BASE_URL` to it.
4. Add the AI service from the same repository and branch:
   - root directory `/ai`, Dockerfile path `/ai/Dockerfile`, watch paths `/ai/**`;
   - health check path `/health`, timeout 120 seconds;
   - variables `PORT=8080` and a new `CML_AI_TOKEN`; no public domain.
5. Deploy the AI service first. Its private hostname is only assigned after the first deploy, so
   redeploy the backend afterwards so `AI_MATCHING_URL` resolves.
6. Check: the backend log shows `Started CopyMyLifeApplication` and no `ERROR`; the AI log shows
   `model loaded` and `GET /health 200`.

The Railway GitHub app must have access to `DDulat5SD/Copy-my-life` (the repository owner installs it
under GitHub **Settings → Applications → Railway**). Without it Railway can still build the public
repository, but creates no deployment trigger, so nothing redeploys automatically.

## PostgreSQL

- The schema is created and updated by the application on startup (`spring.jpa.hibernate.ddl-auto=update`).
  A few constraints Hibernate cannot express are created by startup classes in
  `src/main/java/.../config` (`ReportConstraints`, `RoutineCopyConstraints`, `FriendshipPairIndex`).
  Built-in routines and the category list are seeded on startup.
- `db/manual/*.sql` are the hand-written versions of those changes. On a database created by the
  current application they are redundant except **one**:

  ```sql
  -- db/manual/users_email_unique.sql: case-insensitive unique e-mail (blocks duplicate sign-ups that race)
  CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_uq ON users (lower(email));
  ```

  Production has it. A new database (staging) needs it applied once, after the first start.
  Check first that no two users share an e-mail ignoring case, or the index cannot be built.
- Never point a staging service at the production database, and never restore production data into
  staging.

## Continuous integration

`.github/workflows/ci.yml` runs on every pull request into `main` and on every push to `main`
(the merged commit):

- **Backend build and tests**: PostgreSQL 18 test container, Java 21, Node 24 and headless Chrome;
  `mvnw verify` runs all unit, integration and browser tests. A report check fails the job if any
  test class was skipped, because skipped tests still let Maven pass.
- **AI service tests**: only when `ai/` or the backend's AI clients change; runs pytest with the
  real model and the Java test that talks to the Python service.

Every value in CI is test-only. No production credential is used.

**Branch protection** (repository owner, **Settings → Branches → main**): require a pull request,
and require the status check **Backend build and tests** to pass before merging. This is what keeps
untested code out of `main`, and therefore out of staging and production.

## Deploying to staging

1. Open a pull request into `main`. CI runs.
2. Merge it after CI passes. GitHub pushes the merge commit to `main`.
3. Railway starts staging deployments of the backend (and of the AI service when `/ai/**` changed).
   With **Wait for CI** on, they wait in `WAITING` until the CI run on the merged commit passes; a
   failed run skips the deployment.
4. Check https://copymylife-staging-staging.up.railway.app.

To turn on **Wait for CI**: open each staging service in Railway, go to its **Settings**, find the
connected repository and branch, and enable *Wait for CI*. Railway only offers the option once a
workflow runs on `push` to `main`, which `ci.yml` does from this release onward.

## Releasing to production

`.github/workflows/release.yml` runs only when a tag `vMAJOR.MINOR.PATCH` is pushed. It:

1. checks the tag points at a commit on `main`;
2. checks that `ci.yml` passed on that exact commit (the push run);
3. deploys the AI service with `railway up` when `ai/` changed since the previous release tag;
4. deploys the backend with `railway up`, then checks the login page and built-in routines answer.

The database, volumes and Railway variables are never changed by a release.

```bash
git switch main && git pull
# Wait until CI on this commit is green, then:
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin v1.0.0
```

### One-time setup (before the first release)

Production still deploys on every push to `main` of the original repository `Elsico12/CopyMyLife`.
Move it to tag releases in this order, so it always has a working deployment path:

1. **Railway token:** Railway → project → **Settings → Tokens** → create a *project token* for the
   `production` environment.
2. **GitHub secret:** in `DDulat5SD/Copy-my-life` → **Settings → Environments → New environment**
   `production` (optionally with required reviewers), then add the secret `RAILWAY_TOKEN` with that
   token. The workflow fails before deploying anything if the secret is missing.
3. **Decide about the Halloween images:** production currently shows the ten Halloween cover
   images, which this repository leaves out (see [INTEGRATION.md](INTEGRATION.md)). The first release
   from this repository replaces production with code that does not have them; the cards fall back to
   letter placeholders.
4. **First release:** tag a commit that passed CI on `main` and watch the *Release* run.
5. **Only after it succeeded:** in Railway production, disconnect the old GitHub source
   (`Elsico12/CopyMyLife`) from `CopyMyLife` and `copymylife-ai` (**Settings → Source → Disconnect**),
   so that only tags deploy.

## Rollback

- **Fastest (no rebuild):** Railway → production → the service → **Deployments** → the last good
  deployment → **⋮ → Redeploy**. Do it for the backend and, if it was released, the AI service.
- **From Git:** tag the last good commit again with a new patch version
  (`git tag -a v1.0.2 <good-sha> -m "Roll back to 1.0.0"`, then push the tag); it must still be on `main`
  with green CI.
- **Database:** releases do not migrate the database destructively (Hibernate only adds tables and
  columns), so rolling back code needs no database step. Restoring database data (from a Railway
  volume backup, if backups are enabled for the database service, or from a `pg_dump`) is a separate,
  explicit decision, never part of a routine rollback.
- **Staging:** revert the pull request on GitHub; merging the revert redeploys staging.

## Common problems and solutions

| Problem | Cause | Solution |
|---|---|---|
| Sign-up or login answers `500`; log shows `io.jsonwebtoken.io.DecodingException: Illegal base64 character` | `JWT_SECRET` contains a carriage return. OpenSSL on Windows (Git Bash) ends Base64 lines with `\r\n`; removing only `\n` leaves a `\r` inside the secret. | Generate it with `openssl rand -base64 64 \| tr -d '\r\n'` and check it with a strict decoder (Python `base64.b64decode(value, validate=True)`; the default decoder silently skips the bad character). |
| A staging service is connected to GitHub but never redeploys on merge | The Railway GitHub app has no access to the repository, so no deployment trigger exists. | The repository owner grants the Railway app access to `DDulat5SD/Copy-my-life`, then reconnect the service source in Railway. |
| *Wait for CI* is missing in Railway | Railway only waits for workflows that run on `push` to the deploy branch. | `ci.yml` runs on `push` to `main` (since this release). |
| Backend cannot reach the AI service (`examples` status `UNAVAILABLE`) | The AI service had no private hostname yet when the backend started, so `AI_MATCHING_URL` resolved to `http://:8080`. | Deploy the AI service first, then redeploy the backend. |
| `railway volume add --mount-path /app/uploads` fails with "Mount path must start with a /" | Git Bash on Windows rewrites arguments that start with `/` into Windows paths. | Prefix the command with `MSYS_NO_PATHCONV=1`. |
| `railway environment edit --service-config …` prints "No changes to apply" | The CLI command did not apply service settings in our tests. | Set them in the dashboard, or with `railway api` and the `serviceInstanceUpdate` mutation (set `dockerfilePath`; the Builder enum has no Dockerfile value). |
| *Forgot password* fails on staging | Mail is disabled on purpose. | Expected; test e-mail only where real Mailjet keys are configured. |
| Staging backend uses over 1 GB of memory | The JVM sizes its heap from the container limit (8 GB). | Optionally cap the heap on staging to lower cost, e.g. `JAVA_TOOL_OPTIONS=-Xmx512m` (not yet tested; watch for `OutOfMemoryError` in the log). |
| A Halloween routine has no cover image | The images are not in this repository (copyright). | Add licensed images under `src/main/resources/static/img/halloween/`. |
