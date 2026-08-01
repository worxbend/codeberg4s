package com.worxbend.codeberg4s.it

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitStrategy

import java.time.Duration

/** How a disposable Forgejo is started, and what it is called once it is up.
  *
  * ==Why the install page is never seen==
  *
  * A stock Forgejo image boots into its web installer and answers the install form on every path, including
  * `/api/v1/version`, which would make a naive readiness probe succeed against a server that cannot serve the API.
  * Rather than driving that form — a multipart POST whose field names change between releases — the container is
  * configured through the image's own `FORGEJO__section__KEY` environment protocol and told the install is already
  * done. That makes the first-run deterministic: the instance comes up with SQLite, no registration, and no installer.
  *
  * The one thing environment variables cannot do is create the first user, because Forgejo only ever creates it from
  * the installer or from its own CLI. [[createAdminCommand]] is that CLI call, run inside the container.
  *
  * ==The image==
  *
  * `FORGEJO_IT_IMAGE` overrides [[DefaultImage]], which is how this suite is pointed at the tag a CI environment has
  * already cached, or at `docker.io/codeberg/forgejo` when the Codeberg registry is unreachable. The default is pinned
  * rather than `:latest` so that a green run stays reproducible.
  */
object ForgejoContainer:

  /** The port Forgejo serves HTTP on inside the container. Mapped to an ephemeral port on the host. */
  val HttpPort: Int = 3000

  /** The environment variable that overrides [[DefaultImage]]. */
  val ImageVariable: String = "FORGEJO_IT_IMAGE"

  /** The image used when [[ImageVariable]] is not set. Pinned, because `:latest` makes a rerun a different test. */
  val DefaultImage: String = "codeberg.org/forgejo/forgejo:12"

  /** How long the instance is given to migrate its database and answer `GET /api/v1/version`.
    *
    * Generous on purpose: the first run on a cold machine also pays for pulling the image, and a startup timeout that
    * fires during a pull produces a failure that says nothing about this library.
    */
  val StartupTimeout: Duration = Duration.ofMinutes(5)

  /** The administrator the suite creates and then works as. Throwaway, for a container that outlives one suite. */
  val Admin: ForgejoBootstrap.AdminAccount = ForgejoBootstrap.AdminAccount(
    username = "codeberg4s",
    password = "codeberg4s-integration-password",
    email    = "codeberg4s@example.invalid",
  )

  /** The repository the suite creates and exercises. */
  val RepositoryName: String = "integration"

  /** The branch the repository's initial commit lands on. */
  val DefaultBranch: String = "main"

  /** The name given to the access token the suite issues for itself. */
  val TokenName: String = "codeberg4s-integration"

  /** The image to start, from [[ImageVariable]] or [[DefaultImage]]. */
  def image: String = sys.env.getOrElse(ImageVariable, DefaultImage)

  /** The instance's configuration, in the `FORGEJO__section__KEY` form the image reads before it writes `app.ini`.
    *
    * `INSTALL_LOCK` is what skips the installer. `OFFLINE_MODE` stops the instance reaching for avatars and release
    * notes on a network a test machine may not have. `DISABLE_REGISTRATION` keeps the only account the one the suite
    * creates. The log level is lowered because Testcontainers pipes container output into the test report.
    */
  val Environment: Map[String, String] = Map(
    "USER_UID"                               -> "1000",
    "USER_GID"                               -> "1000",
    "FORGEJO__database__DB_TYPE"             -> "sqlite3",
    "FORGEJO__database__PATH"                -> "/data/gitea/forgejo.db",
    "FORGEJO__security__INSTALL_LOCK"        -> "true",
    "FORGEJO__security__SECRET_KEY"          -> "codeberg4s-integration-secret",
    "FORGEJO__server__ROOT_URL"              -> s"http://localhost:$HttpPort/",
    "FORGEJO__server__OFFLINE_MODE"          -> "true",
    "FORGEJO__server__SSH_DOMAIN"            -> "localhost",
    "FORGEJO__service__DISABLE_REGISTRATION" -> "true",
    "FORGEJO__repository__DEFAULT_BRANCH"    -> DefaultBranch,
    "FORGEJO__cron__ENABLED"                 -> "false",
    "FORGEJO__log__LEVEL"                    -> "Warn",
  )

  /** Ready when the API root answers, not when the port merely accepts connections.
    *
    * A listening socket appears well before the database migrations finish, so a port probe would hand the suite an
    * instance that answers `503` to its first call. `GET /api/v1/version` is the cheapest thing that is only true once
    * the API router is mounted.
    */
  val ReadinessProbe: WaitStrategy = Wait
    .forHttp("/api/v1/version")
    .forPort(HttpPort)
    .forStatusCode(200)
    .withStartupTimeout(StartupTimeout)

  /** The container definition handed to `TestContainerForAll`. */
  def definition: GenericContainer.Def[GenericContainer] = GenericContainer.Def(
    dockerImage  = GenericContainer.DockerImage(Left(image)),
    exposedPorts = Seq(HttpPort),
    env          = Environment,
    waitStrategy = ReadinessProbe,
  )

  /** The CLI call that creates the first user, run inside the container.
    *
    * Two details are load-bearing. `su-exec git` runs the binary as the account that owns `/data`, which the image's
    * own entrypoint does and which Forgejo refuses to proceed without. `--must-change-password=false` matters because
    * the default for an admin is `true`, and a user who must change their password cannot issue a token.
    *
    * The returned list carries a password, so a caller must render a label rather than the command when it fails.
    *
    * @param admin
    *   the account to create
    */
  def createAdminCommand(admin: ForgejoBootstrap.AdminAccount): List[String] = List(
    "su-exec",
    "git",
    "forgejo",
    "admin",
    "user",
    "create",
    "--admin",
    "--username",
    admin.username,
    "--password",
    admin.password,
    "--email",
    admin.email,
    "--must-change-password=false",
  )
