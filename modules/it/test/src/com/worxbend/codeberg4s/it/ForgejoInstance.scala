package com.worxbend.codeberg4s.it

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.retry.RetryPolicy

import com.dimafeng.testcontainers.GenericContainer

import scala.concurrent.ExecutionContext

/** A started Forgejo container that has an administrator, a token and a repository, and a client wired to all three.
  *
  * This is what a suite actually wants: the container is only the means. Holding the client here rather than rebuilding
  * it per test also means the whole suite runs over one connection pool, which is the way the library is meant to be
  * used and therefore the way it should be exercised.
  *
  * @param baseUri
  *   the API root of the running container, on the host's mapped port
  * @param client
  *   the client under test, authenticated as [[ForgejoContainer.Admin]] with a token the suite issued
  * @param owner
  *   the administrator, who owns [[repository]]
  * @param repository
  *   the initialised repository every assertion in the suite works against
  */
final class ForgejoInstance(
    val baseUri: BaseUri,
    val client: CodebergClient,
    val owner: Owner,
    val repository: RepoName,
):

  /** Closes the client. The container itself is stopped by the suite that started it. */
  def close(): Unit = client.close()

/** How a started container becomes a [[ForgejoInstance]]. */
object ForgejoInstance:

  /** Prepares a container that has just answered its readiness probe, and builds the client for it.
    *
    * The order is forced by Forgejo: only the CLI can create the first user, only that user can issue a token, and only
    * a token can create a repository over the API.
    *
    * '''Error contract.''' Returns `Left` with a short reason and no partially built client when any step fails: the
    * container did not expose its port, the admin CLI call exited non-zero, the token could not be issued, or the
    * repository could not be created. Nothing is thrown, so a suite can render the reason through munit's `fail` and
    * report a failure that names the step rather than a `NullPointerException` three tests later. Nothing is logged,
    * and the admin password never reaches a reason — [[ForgejoContainer.createAdminCommand]] carries it, so its failure
    * is reported by label.
    *
    * @param container
    *   the started container, already past [[ForgejoContainer.ReadinessProbe]]
    */
  def bootstrap(container: GenericContainer)(using ExecutionContext): Either[String, ForgejoInstance] =
    for
      base       <- baseUriOf(container)
      _          <- exec(
                      container,
                      "creating the instance administrator",
                      ForgejoContainer.createAdminCommand(ForgejoContainer.Admin),
                    )
      token      <- ForgejoBootstrap.issueToken(base, ForgejoContainer.Admin, ForgejoContainer.TokenName)
      _          <- ForgejoBootstrap.createRepository(
                      base,
                      token,
                      ForgejoContainer.RepositoryName,
                      ForgejoContainer.DefaultBranch,
                    )
      owner      <- Owner.from(ForgejoContainer.Admin.username).left.map(Reasons.invalid)
      repository <- RepoName.from(ForgejoContainer.RepositoryName).left.map(Reasons.invalid)
    yield
      val config = IntegrationConfig.forInstance(base, Auth.Token(token), RetryPolicy.Off)
      new ForgejoInstance(base, CodebergClient(config), owner, repository)

  /** The API root on the host, built from the ephemeral port Docker mapped [[ForgejoContainer.HttpPort]] to. */
  private def baseUriOf(container: GenericContainer): Either[String, BaseUri] =
    for
      host <- Reasons.attempting("reading the container's host")(container.host)
      port <- Reasons.attempting("reading the container's mapped port")(container.mappedPort(ForgejoContainer.HttpPort))
      base <- BaseUri.from(s"http://$host:$port/api/v1").left.map(Reasons.invalid)
    yield base

  /** Runs a command inside the container, reporting it by `label` because a command line may carry a credential. */
  private def exec(container: GenericContainer, label: String, command: List[String]): Either[String, String] =
    for
      result <- Reasons.attempting(label)(container.execInContainer(command*))
      output <- result.getExitCode match
                  case 0      => Right(result.getStdout)
                  case status => Left(s"$label exited with $status: ${result.getStderr}")
    yield output
