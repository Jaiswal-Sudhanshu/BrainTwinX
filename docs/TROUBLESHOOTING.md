# Troubleshooting

Reproducible solutions to problems actually encountered while building BrainTwinX. Each
entry records the **symptom**, the **root cause** (diagnosed, not guessed), and the **fix**.

---

## T-1 — Testcontainers: "Could not find a valid Docker environment"

**Symptom.** `mvn verify` fails during integration tests. Unit tests pass. The Docker CLI
works fine — `docker run hello-world` succeeds and `docker info` reports a healthy server —
yet Testcontainers reports:

```
Could not find a valid Docker environment. Please check configuration.
Attempted configurations were:
    NpipeSocketClientProviderStrategy: failed with exception
    BadRequestException (Status 400: {"ID":"", ... "ServerVersion":"" ...})
```

**Environment where this occurred.** Windows 11, Docker Desktop 4.84.0, Engine 29.6.2,
Engine API 1.55, Testcontainers 1.21.3.

**Diagnosis.** Two things were ruled out before finding the cause:

1. *Not a Docker startup race.* `docker run hello-world` was confirmed to work.
2. *Not the wrong named pipe.* Docker Desktop's active context is `desktop-linux`
   (`npipe:////./pipe/dockerDesktopLinuxEngine`), and Testcontainers initially probed the
   legacy `//./pipe/docker_engine`. Pointing it at the correct endpoint made
   `EnvironmentAndSystemPropertyClientProviderStrategy` run — **and it failed identically
   with HTTP 400.**

**Root cause.** The `docker-java` client bundled with Testcontainers **1.21.3** cannot
negotiate with Docker Engine API 1.55. Against *either* pipe it receives a 400 with an empty
`/info` payload (`"ServerVersion":""`), so Testcontainers concludes no valid environment
exists. The empty-but-well-formed body is the signature of API version negotiation failing
rather than a connectivity problem.

**Fix — upgrade Testcontainers to 1.21.4.**

```xml
<testcontainers.version>1.21.4</testcontainers.version>
```

This is the actual fix and it is committed in `backend/pom.xml`. Confirmed by behaviour: on
1.21.3 the test aborted in 0.8 s with no strategy succeeding; on 1.21.4 it proceeded to
pull images and start a container.

**Two things that were tried and did NOT fix it**, recorded so they are not repeated:

| Attempt | Result |
|---|---|
| Setting `docker.host` to the correct `dockerDesktopLinuxEngine` pipe in `~/.testcontainers.properties` | Made `EnvironmentAndSystemPropertyClientProviderStrategy` run, but it failed with the **identical** HTTP 400. Proved the pipe path was not the cause. |
| Pinning `api.version=1.44` in `~/.testcontainers.properties` | No effect. Ruled out simple version negotiation as the whole story. |
| Upgrading to Testcontainers **2.0.5** | Build could not read the POM: 2.x reorganised artifact coordinates, so `org.testcontainers:mysql` and `org.testcontainers:junit-jupiter` are no longer managed by its BOM. Migrating to 2.x is not a version bump and was deferred. |

Neither properties-file setting is *known* to be redundant on 1.21.4. When the connection
first succeeded, **both** the 1.21.4 upgrade and the properties file were in place, and the
two were not isolated from each other afterwards. The upgrade is the change with the
established causal link (1.21.3 aborted in 0.8 s; 1.21.4 proceeded to pull images), so it is
the committed fix — but if you remove `~/.testcontainers.properties` and the connection
breaks, restore it and please record that here.

**Note on `DOCKER_HOST`.** Exporting `DOCKER_HOST` in the shell does propagate to child
processes and is read by Testcontainers, but it does not persist across sessions, so the
properties file is the durable equivalent.

---

## T-1b — Testcontainers: `ContainerFetchException: Can't get Docker image`

**Symptom.** After the Docker connection works, an integration test fails while fetching an
image:

```
ContainerFetchException: Can't get Docker image: RemoteDockerImage(imageName=testcontainers/ryuk:0.12.0 ...)
Caused by: DockerClientException: Could not pull image: failed to copy: httpReadSeeker:
    failed open: failed to do request: Get "https://production.cloudfront.docker.com/..." : EOF
```

**Root cause.** A transient network failure pulling from Docker Hub's CDN — an `EOF`
mid-blob-download. Not a configuration or code fault. Testcontainers pulls two images on a
cold cache: `testcontainers/ryuk` (its container reaper) and the database image.

**Fix.** Pre-pull the images so the test run does not depend on a download succeeding
mid-test:

```bash
docker pull testcontainers/ryuk:0.12.0
docker pull mysql:8.4
```

Once cached locally, subsequent runs do not hit the network. If a pull itself keeps failing,
retry it — the failure is in the CDN transfer, not in Docker or the build.


---

## T-2 — `mvn` not found, or builds behave differently between machines

**Symptom.** `mvn` is unavailable, or a build succeeds locally and fails elsewhere.

**Root cause.** On this host Maven runs from
`C:\Users\jsudh\Downloads\apache-maven-3.9.16-bin\...` — an unpacked archive in a downloads
folder, not a stable installation. Anything depending on that path is not reproducible
(recorded as risk R-6 in [`CURRENT_STATE.md`](./CURRENT_STATE.md)).

**Fix.** Use the committed Maven Wrapper instead of a host Maven:

```bash
cd backend
./mvnw verify        # Linux/macOS/Git Bash
.\mvnw.cmd verify    # Windows cmd/PowerShell
```

The wrapper downloads its own pinned Maven, so every machine builds with the same version.

---

## T-3 — Application fails to start: `Could not resolve placeholder 'MYSQL_USERNAME'`

**Symptom.** Startup fails on a missing property placeholder.

**Root cause.** This is intended. `application.yml` deliberately provides **no default** for
credentials, so the application fails fast rather than starting with an insecure fallback.

**Fix.**

```bash
cp .env.example .env
```

Then set at minimum `MYSQL_USERNAME`, `MYSQL_PASSWORD`, and `JWT_SECRET`. Generate the JWT
secret rather than inventing one:

```bash
openssl rand -base64 48
```

Integration tests do **not** need these variables — `AbstractIntegrationTest` supplies
datasource properties via `@DynamicPropertySource`, which takes precedence over the YAML.

---

## T-4 — Startup fails with `Schema-validation: missing column` / `wrong column type`

**Symptom.** The application context fails to load with a Hibernate schema-validation error.

**Root cause.** Not a bug — a guard working as designed. Hibernate runs with
`ddl-auto=validate` in **every** profile, including `dev`. An entity field was added or
changed without a corresponding Flyway migration.

**Fix.** Add a forward migration in
`backend/src/main/resources/db/migration/V{n}__description.sql`.

Do **not**:

- set `ddl-auto` to `update` or `create` — that lets Hibernate mutate a medical schema
  silently, and permits entity/migration drift to go unnoticed until it reaches an
  environment where it matters;
- edit an already-applied migration — `validate-on-migrate` will reject the checksum change
  on the next run, which is the intended behaviour.

---

## T-5 — MySQL CHECK constraints appear not to work

**Symptom.** An integration test asserting that an invalid row is rejected passes even when
the constraint is removed, or invalid data inserts successfully.

**Root cause.** MySQL only **enforces** CHECK constraints from **8.0.16**. On earlier
versions they parse without error and are silently ignored — so the safety tests would pass
vacuously while the invariants they claim to verify were absent.

**Fix.** Use MySQL 8.0.16 or later. `AbstractIntegrationTest` already asserts the server
version and fails loudly if it is too old, and the Testcontainers image is pinned to
`mysql:8.4`. If you change that image tag, do not go below 8.0.16.

---

## T-6 — Port already in use

**Symptom.** `Web server failed to start. Port 8080 was already in use.`

**Fix.** Either free the port or override it:

```bash
# Find the owning process (Windows)
netstat -ano | findstr :8080

# Or run on a different port
BACKEND_PORT=8081 ./mvnw spring-boot:run
```

Default ports: backend `8080`, AI service `8001`, frontend dev server `5173`, MySQL `3306`.

---

## Entries pending

The following will be documented as the relevant phases land, rather than pre-written from
guesswork:

- AI service model-loading failures (Phase 6–7)
- `/ready` reporting NOT READY (expected while no weights exist — Phase 7)
- CORS failures between frontend and backend (Phase 12)
- Python dependency resolution on Python 3.14 (Phase 6)
- Docker Compose startup ordering (Phase 15)
