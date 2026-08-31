package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.actions.{ActionStatus, WorkflowFileName}

/** The three readings this group's DTOs share, written once because getting any of them wrong twice would be silent.
  *
  * None of these can fail. Every one of them turns a value the domain cannot use into absence, which is the contract
  * `docs/HAZARDS.md` §1 forces on a spec that declares nothing required: a field that arrives in a shape this library
  * does not recognise must cost the caller that field and nothing else.
  */
private[actions] object ActionWire:

  /** Forgejo's `0`-for-absent convention on the identifier fields of this group.
    *
    * `ActionRunner.owner_id` is `0` when the runner belongs to a repository, `ActionRunner.repo_id` is `0` when it
    * belongs to an account, and `ActionRun.approved_by` is `0` when nobody approved the run. All three are documented
    * that way in `spec/swagger.v1.json`, and all three would otherwise reach the domain as a row id that resolves to
    * nothing. Negative values are folded away too; no Forgejo row id is one.
    */
  def identifier(value: Option[Long]): Option[Long] =
    value.filter(_ > 0L)

  /** The `status` field of a run, a job or a task, as [[ActionStatus.parse]] reads it — `None` for a value outside the
    * enumerated set.
    */
  def status(value: Option[String]): Option[ActionStatus] =
    value.flatMap(ActionStatus.parse)

  /** The `workflow_id` field, which is a workflow's file name. `None` when it cannot be one. */
  def workflow(value: Option[String]): Option[WorkflowFileName] =
    value.flatMap(name => WorkflowFileName.from(name).toOption)

  /** A commit field, as [[com.worxbend.codeberg4s.repositories.CommitSha.from]] reads it — `None` when the value is not
    * an object id.
    */
  def commit(value: Option[String]): Option[CommitSha] =
    value.flatMap(sha => CommitSha.from(sha).toOption)

  /** The elements of a string array, in wire order; empty when the key is absent, `null` or not an array. */
  def strings(fields: JsonFields, name: String): Vector[String] =
    fields.texts(name)
