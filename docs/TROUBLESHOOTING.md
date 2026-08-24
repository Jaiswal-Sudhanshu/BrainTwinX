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

**Root cause.** A **local network condition that drops large sustained downloads** — not a
registry or Docker fault. Evidence:

| Observation | Implication |
|---|---|
| `testcontainers/ryuk:0.12.0` (29 MB) pulls successfully | Docker, TLS, DNS, and registry auth all work |
| `mysql:8.4` (~250 MB) fails from Docker Hub — 12 attempts | Not a transient blip |
| `public.ecr.aws/docker/library/mysql:8.4` fails **identically** | Two independent registries, two different CloudFront distributions (`production.cloudfront.docker.com` and `d2glxqk2uabbnd.cloudfront.net`) |
| Failure is always `EOF` mid-blob, never a 4xx/5xx | The connection is being severed during transfer, not refused |

Small transfers succeed and large ones are cut off, across unrelated hosts. That pattern points
at the local path — commonly an MTU/fragmentation problem, a TLS-inspecting middlebox or
corporate proxy, or ISP-level behaviour on long-lived connections — rather than anything
fixable in this repository.

**Fixes, in order of preference.**

1. **Pull on a different network.** A mobile hotspot or VPN is the fastest way to confirm the
   diagnosis and get the image cached. Once cached, it never needs downloading again.
2. **Lower Docker Desktop's MTU.** If the cause is fragmentation, Docker Desktop → Settings →
   Docker Engine, add `"mtu": 1400`, then Apply & Restart.
3. **Pull a smaller MySQL variant.** Any tag **≥ 8.0.16** satisfies the CHECK-constraint
   requirement (see T-5). If a smaller image pulls where 8.4 will not, update the tag in
   `AbstractIntegrationTest` and record the change here — do **not** drop below 8.0.16.
4. **Run integration tests against a local MySQL instead of Testcontainers.** A MySQL 8.0.46
   installer is already present in `~/Downloads`. This needs a small change to
   `AbstractIntegrationTest` to use an externally supplied datasource when one is configured,
   and it trades away the clean-database-per-run guarantee — so it is a fallback, not the
   preferred design.

**What is NOT the problem:** application code, the migration, the entity model, or the
Testcontainers configuration. `mvn verify` will run unchanged the moment the image is present
locally.



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


---

## T-7 — Flyway migrations never run; Hibernate reports "missing table"

**Symptom.** The application or an integration test fails at startup:

```
SchemaManagementException: Schema validation: missing table [analysis_jobs]
```

with **no Flyway log output at all** — no "Migrating schema", no "Successfully applied".

**Root cause.** `org.flywaydb:flyway-core` alone is **not sufficient on Spring Boot 4**. Boot 4
split the monolithic `spring-boot-autoconfigure` into per-technology modules, so Flyway's
auto-configuration now lives in its own artifact. Without it, Flyway is on the classpath but is
never activated: no migration runs, the schema stays empty, and Hibernate's `validate` then
correctly complains that the tables are missing.

The give-away is the package name in the stack trace — `org.springframework.boot.hibernate.autoconfigure.HibernateJpaConfiguration`
rather than the Boot 3 `org.springframework.boot.autoconfigure.orm.jpa.*`. That naming is the
visible sign of the module split.

**Fix.** Add the module alongside `flyway-core` (version managed by the parent):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-flyway</artifactId>
</dependency>
```

**Generalisation worth remembering:** on Spring Boot 4, a technology being on the classpath no
longer implies its auto-configuration is active. If a Boot feature silently does nothing after
an upgrade from 3.x, check whether it now needs its own `spring-boot-<tech>` module.

---

## T-8 — A CHECK-constraint test fails with `UncategorizedSQLException`

**Symptom.** A test asserting that the database rejects an invalid row fails — not because the
row was accepted, but because the exception type does not match:

```
Expecting actual throwable to be an instance of:
  org.springframework.dao.DataIntegrityViolationException
but was:
  org.springframework.jdbc.UncategorizedSQLException ... error code [3819]
  Check constraint 'ck_patients_archived_consistency' is violated.
```

**Root cause.** The constraint is working perfectly. MySQL raises error **3819**
(`ER_CHECK_CONSTRAINT_VIOLATED`) for a violated CHECK, and 3819 is **absent** from Spring's
MySQL data-integrity error-code list. Spring therefore translates it to
`UncategorizedSQLException` rather than `DataIntegrityViolationException`. UNIQUE violations
(error 1062) *are* in that list, which is why they translate as expected — hence the confusing
asymmetry where some constraint tests pass and others do not.

**Fix.** Assert on the common parent plus the constraint name:

```java
assertThatThrownBy(() -> jdbcTemplate.update(...))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_patients_archived_consistency");
```

This is also **strictly stronger** than asserting an exception type alone: it proves the
*intended* constraint rejected the write, rather than merely that something went wrong. A test
that only checks the type would keep passing if an unrelated foreign key happened to fail first.


---

## T-9 — `package com.fasterxml.jackson.databind does not exist` on Spring Boot 4

**Symptom.** Code using `ObjectMapper` fails to compile even though `spring-boot-starter-web` is
present:

```
package com.fasterxml.jackson.core does not exist
package com.fasterxml.jackson.databind does not exist
cannot find symbol: class ObjectMapper
```

**Root cause.** **Spring Boot 4 ships Jackson 3, whose packages are `tools.jackson.*`.** Jackson
*is* on the classpath — it arrives via `starter-web` → `spring-boot-starter-jackson` → 
`tools.jackson.core:jackson-databind:3.x`. The old `com.fasterxml.jackson.*` packages are simply
gone, apart from **annotations**, which remain at `com.fasterxml.jackson.annotation` 
(`jackson-annotations` is still a 2.x artifact).

Confirm with:

```bash
./mvnw dependency:tree | grep -i jackson
```

Expect to see `tools.jackson.core:jackson-databind` at **compile** scope. A
`com.fasterxml.jackson.core:jackson-databind` line at **runtime** scope may also appear, pulled in
by a library such as `jjwt-jackson` — that one is not compile-visible, which is why the error
occurs despite the name appearing in the tree.

**Fix.** Migrate the imports; no dependency change is needed.

| Boot 3 / Jackson 2 | Boot 4 / Jackson 3 |
|---|---|
| `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.ObjectMapper` |
| `com.fasterxml.jackson.core.JsonProcessingException` | `tools.jackson.core.JacksonException` |
| `com.fasterxml.jackson.annotation.JsonInclude` | **unchanged** |

Note also that Jackson 3 made its exceptions **unchecked**: `JacksonException` extends
`RuntimeException`, so `writeValueAsString` no longer forces a `catch`.

**A wrong turn worth recording.** Adding `spring-boot-starter-json` looks like the fix and is not
— it changes nothing, because Jackson was never missing. Diagnosing this properly requires reading
the dependency tree rather than assuming a module split (contrast with T-7, where a module
genuinely *was* missing). Beware `./mvnw -q dependency:list`: `-q` suppresses the list itself, so
an empty `grep` proves nothing.

---

## T-10 — Second integration-test class fails with "Communications link failure"

**Symptom.** The first integration-test class passes. A later one fails on every test that touches
the database:

```
CJCommunicationsException: Communications link failure
Caused by: java.net.ConnectException: Connection refused: getsockopt
SQLTransientConnectionException: braintwinx-pool - Connection is not available,
    request timed out after 10000ms
```

**Root cause.** A `static @Container` field declared on a shared `@Testcontainers` **abstract base
class**. The JUnit extension ties that container's lifecycle to the test class, so it is
**stopped after the first subclass completes**. Every subsequent class inherits a reference to a
stopped container. Spring has meanwhile cached the application context with the original JDBC URL,
so the symptom presents as a connectivity fault rather than a lifecycle one — which is what makes
it confusing.

The 10-second delays in the failure output are the HikariCP connection timeout, not slow tests.

**Fix.** Use the documented **singleton container** pattern: start it once in a static
initialiser and never stop it.

```java
@SpringBootTest                       // note: NO @Testcontainers
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource")     // never closed on purpose
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4") ...;

    static { MYSQL.start(); }
}
```

One container is then shared by every integration test in the JVM, which is also faster than a
per-class restart. Cleanup is handled by the Testcontainers **Ryuk** sidecar at JVM exit, so
nothing is leaked by not calling `stop()`.
