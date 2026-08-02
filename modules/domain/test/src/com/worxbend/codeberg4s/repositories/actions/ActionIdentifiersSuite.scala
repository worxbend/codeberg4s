package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** The smart constructors of every identifier this group addresses a resource by.
  *
  * The numeric ones exist to stop a run id, a job id and an artifact id being swapped; the string ones exist because
  * they are interpolated into a request path and a `/` in one would reach an endpoint the API surface never offered.
  * Both properties are asserted here rather than assumed.
  */
final class ActionIdentifiersSuite extends FunSuite:

  test("a run id accepts the smallest row id Forgejo issues"):
    assertEquals(RunId.from(1L).toOption.map(_.value), Some(1L))

  test("a run id rejects zero"):
    assertEquals(field(RunId.from(0L)), Some("runId"))

  test("a run id rejects a negative identifier"):
    assertEquals(field(RunId.from(-4L)), Some("runId"))

  test("a job id rejects zero, and says so on its own field"):
    assertEquals(field(JobId.from(0L)), Some("jobId"))

  test("an artifact id rejects zero, and says so on its own field"):
    assertEquals(field(ArtifactId.from(0L)), Some("artifactId"))

  test("a task id rejects zero, and says so on its own field"):
    assertEquals(field(TaskId.from(0L)), Some("taskId"))

  test("an attempt is one-based, so zero is rejected rather than read as 'the latest'"):
    assertEquals(field(JobAttempt.from(0L)), Some("jobAttempt"))

  test("an attempt accepts the first execution of a job"):
    assertEquals(JobAttempt.from(1L).toOption.map(_.value), Some(1L))

  test("a runner id can be built from the numeric id every runner object reports"):
    assertEquals(RunnerId.of(37L).value, "37")

  test("a runner id accepts a uuid-shaped value, because the path parameter is declared as a string"):
    assertEquals(
      RunnerId.from("3f7c1a2e-0b44-4f11-9a76-1d2c3e4f5a6b").toOption.map(_.value),
      Some(
        "3f7c1a2e-0b44-4f11-9a76-1d2c3e4f5a6b"
      ),
    )

  test("a runner id rejects a slash, which would reach an endpoint the API never offered"):
    assertEquals(field(RunnerId.from("12/../../admin")), Some("runnerId"))

  test("a runner id rejects a blank value"):
    assertEquals(field(RunnerId.from("   ")), Some("runnerId"))

  test("a secret name is trimmed and kept in the caller's own casing"):
    assertEquals(SecretName.from("  deploy_key \n").toOption.map(_.value), Some("deploy_key"))

  test("a secret name rejects a slash"):
    assertEquals(field(SecretName.from("a/b")), Some("secretName"))

  test("a secret name rejects a control character"):
    assertEquals(field(SecretName.from("KEY\nNAME")), Some("secretName"))

  test("a variable name reports its own field, not the secret's"):
    assertEquals(field(VariableName.from("")), Some("variableName"))

  test("a variable name accepts what Forgejo will upper-case"):
    assertEquals(VariableName.from("environment").toOption.map(_.value), Some("environment"))

  test("a workflow file name accepts both extensions Forgejo honours"):
    assertEquals(WorkflowFileName.from("build.yml").toOption.map(_.value), Some("build.yml"))
    assertEquals(WorkflowFileName.from("release.yaml").toOption.map(_.value), Some("release.yaml"))

  test("a workflow file name is a name and not a path"):
    assertEquals(field(WorkflowFileName.from(".forgejo/workflows/build.yml")), Some("workflowFileName"))

  test("a runner label rejects the comma it would otherwise be split on"):
    assertEquals(field(RunnerLabel.from("ubuntu-latest,docker")), Some("runnerLabel"))

  test("a runner label accepts an ordinary label"):
    assertEquals(RunnerLabel.from(" self-hosted ").toOption.map(_.value), Some("self-hosted"))

  test("a runner label rejects a blank value"):
    assertEquals(field(RunnerLabel.from("")), Some("runnerLabel"))

  test("a runner label rejects a control character"):
    assertEquals(field(RunnerLabel.from("dock\ner")), Some("runnerLabel"))

  private def field[A](result: Either[ValidationError, A]): Option[String] =
    result.swap.toOption.map(_.field)
