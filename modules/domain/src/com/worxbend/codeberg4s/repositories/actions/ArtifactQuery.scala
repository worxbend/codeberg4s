package com.worxbend.codeberg4s.repositories.actions

/** The filter both artifact listings take: a repository's artifacts, and one run's.
  *
  * Forgejo matches `name` exactly, not as a prefix or a pattern, and artifact names are not unique — a run that uploads
  * the same name from two jobs produces two artifacts — so this narrows a listing rather than identifying anything.
  *
  * As with [[ActionRunQuery]], only what the caller set is sent: [[ArtifactQuery.Empty]] renders to no parameters.
  *
  * @param name
  *   the artifact name to match exactly, absent to list them all
  */
final case class ArtifactQuery(name: Option[String]):

  /** Restricts the listing to artifacts carrying exactly this name. */
  def named(value: String): ArtifactQuery = copy(name = Some(value))

object ArtifactQuery:

  /** No filter — every artifact of the repository or the run. */
  val Empty: ArtifactQuery = ArtifactQuery(None)
