package com.worxbend.codeberg4s.repositories.actions

/** A machine registered to execute this repository's workflows.
  *
  * '''Derived from `spec/swagger.v1.json`'s `ActionRunner` definition, not from a captured response'''; see
  * [[ActionArtifact]] for why.
  *
  * ==Which identifier addresses it==
  *
  * The wire object carries a numeric `id` and a string `uuid`, and the path parameter is declared `type: string`. This
  * model exposes [[id]] as a [[RunnerId]] built from the numeric `id`, because that is the value the spec's own
  * description ("ID of the runner") points at, and keeps [[uuid]] beside it so a caller who needs the other spelling
  * has it. See [[RunnerId]] for the full argument.
  *
  * @param id
  *   what `GET` and `DELETE` of `/repos/{owner}/{repo}/actions/runners/{runner_id}` are given
  * @param uuid
  *   the runner's self-assigned identifier, reported by the runner binary at registration
  * @param name
  *   the operator's label for the machine. '''Not unique''' — the spec says so outright — so it must not be used to
  *   tell two runners apart
  * @param status
  *   whether the runner is reachable and busy; absent when the instance sent a value outside the enumerated set
  * @param labels
  *   what the runner advertises, which is what a job's `runs-on` matches against. Plain strings for the reason
  *   [[ActionRunJob.runsOn]] gives
  * @param isEphemeral
  *   whether the runner de-registers itself after a single job
  * @param ownerId
  *   the account this runner belongs to; `0` on the wire, and absent here, when the runner belongs to a repository
  * @param repoId
  *   the repository this runner belongs to; `0` on the wire, and absent here, when it belongs to a user or an
  *   organisation
  */
final case class ActionRunner(
    id: RunnerId,
    uuid: Option[String],
    name: Option[String],
    description: Option[String],
    status: Option[RunnerStatus],
    labels: Vector[String],
    isEphemeral: Boolean,
    ownerId: Option[Long],
    repoId: Option[Long],
)
