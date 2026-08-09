package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.PathSegment

/** The identifier of a registered runner, as the runner endpoints take it in a path.
  *
  * '''A string, not a number, because the API says so.''' `spec/swagger.v1.json` declares `runner_id` as `type: string`
  * on both `GET` and `DELETE` of `/repos/{owner}/{repo}/actions/runners/{runner_id}`, while the `ActionRunner` model
  * carries both a numeric `id` and a string `uuid`. Rather than guess which one the route resolves — the spec does not
  * say, and no fixture was harvested for this group — the type carries whatever the caller has and validates it as a
  * path segment. [[RunnerId.of]] is the bridge from the numeric `id` every runner object reports.
  */
opaque type RunnerId = String

object RunnerId:

  /** Parses a runner identifier from its string spelling.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..` — see
    * [[com.worxbend.codeberg4s.repositories.PathSegment]] for why that is a security boundary and not a convenience.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"runnerId"` field
    */
  def from(value: String): Either[ValidationError, RunnerId] =
    PathSegment.from("runnerId", value)

  /** The identifier of the runner whose numeric `id` is `value`.
    *
    * Total rather than validated: a `Long` renders to digits, which are always a legal path segment.
    */
  def of(value: Long): RunnerId =
    value.toString

  extension (id: RunnerId)

    /** The identifier as a string, ready to be used as one path segment. */
    def value: String = id

/** The name of an Actions secret — the `{secretname}` of `/repos/{owner}/{repo}/actions/secrets/{secretname}`.
  *
  * '''A name is all a client ever learns about a secret.''' See [[ActionSecret]] for why the value is not part of this
  * group's read model at all.
  *
  * Forgejo upper-cases secret names on the way in and reports them upper-cased on the way out, so `deploy_key` and
  * `DEPLOY_KEY` address the same secret. This type does '''not''' normalise, because doing so would make a name the
  * caller passed differ from the name they get back for reasons this library invented; it only rejects what cannot be a
  * path segment.
  */
opaque type SecretName = String

object SecretName:

  /** Parses a secret name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..`. Forgejo applies further rules of its own — it rejects
    * a name that starts with a digit, and one that uses a reserved `GITHUB_` or `GITEA_` prefix — which arrive as a
    * `400`, not as a [[ValidationError]].
    *
    * @return
    *   the name, or a [[ValidationError]] on the `"secretName"` field
    */
  def from(value: String): Either[ValidationError, SecretName] =
    PathSegment.from("secretName", value)

  extension (name: SecretName)

    /** The name as a string, ready to be used as one path segment. */
    def value: String = name

/** The name of an Actions variable — the `{variablename}` of `/repos/{owner}/{repo}/actions/variables/{variablename}`.
  *
  * Unlike a [[SecretName]] this addresses something a caller can read back: a variable's value is returned by the API,
  * because a variable is configuration and not a credential.
  */
opaque type VariableName = String

object VariableName:

  /** Parses a variable name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..`. As with [[SecretName]], Forgejo's own naming rules are
    * enforced remotely and arrive as a `400`.
    *
    * @return
    *   the name, or a [[ValidationError]] on the `"variableName"` field
    */
  def from(value: String): Either[ValidationError, VariableName] =
    PathSegment.from("variableName", value)

  extension (name: VariableName)

    /** The name as a string, ready to be used as one path segment. */
    def value: String = name

/** The file name of a workflow, as `.forgejo/workflows/` holds it — `build.yml`, `release.yaml`.
  *
  * This is what Forgejo calls a workflow's id: the `workflow_id` of a run object and the `{workflowfilename}` of the
  * dispatch endpoint are the same string, which is why one type serves both. It is the file's own name, never a path,
  * so a `/` is rejected.
  */
opaque type WorkflowFileName = String

object WorkflowFileName:

  /** Parses a workflow file name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..`. The extension is not checked: Forgejo accepts both
    * `.yml` and `.yaml`, and a name this library refused would be a workflow the caller could not dispatch.
    *
    * @return
    *   the name, or a [[ValidationError]] on the `"workflowFileName"` field
    */
  def from(value: String): Either[ValidationError, WorkflowFileName] =
    PathSegment.from("workflowFileName", value)

  extension (name: WorkflowFileName)

    /** The name as a string, ready to be used as one path segment. */
    def value: String = name

/** One label a runner advertises and a job's `runs-on` selects — `ubuntu-latest`, `docker`, `self-hosted`.
  *
  * Only the '''filter''' side of this concept is typed. `GET /repos/{owner}/{repo}/actions/runners/jobs` joins its
  * `labels` parameter with commas and Forgejo offers no escape for one, so a label carrying a comma would silently
  * become two filters — exactly the hazard [[com.worxbend.codeberg4s.issues.LabelName]] guards against on the issue
  * side. A label '''read back''' from a runner or a job stays a plain `String`, because dropping one this constructor
  * dislikes would cost the caller data the instance actually holds.
  */
opaque type RunnerLabel = String

object RunnerLabel:

  /** Parses a runner label.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing a comma — which is the
    * separator, and has no escape — and a value containing a control character.
    *
    * @return
    *   the label, or a [[ValidationError]] on the `"runnerLabel"` field
    */
  def from(value: String): Either[ValidationError, RunnerLabel] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError("runnerLabel", "must not be blank"))
    else if trimmed.contains(',') then Left(ValidationError("runnerLabel", "must not contain a comma"))
    else if trimmed.exists(_.isControl) then
      Left(ValidationError("runnerLabel", "must not contain a control character"))
    else Right(trimmed)

  extension (label: RunnerLabel)

    /** The label as a string. */
    def value: String = label
