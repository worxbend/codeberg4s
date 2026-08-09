package com.worxbend.codeberg4s.issues

import java.time.Instant

/** The deadline an issue carries, as `POST /repos/{owner}/{repo}/issues/{index}/deadline` reports it back.
  *
  * '''Derived from `spec/swagger.v1.json`''' — Forgejo's `IssueDeadline`, a one-property object. No golden capture
  * exists, because setting a deadline needs a token and the harvest was anonymous.
  *
  * A one-field wrapper rather than a bare `Option[Instant]`, because the endpoint's response is a JSON '''object''' and
  * this is what the object is. Returning the naked option would make the two indistinguishable at a call site from the
  * `unset_due_date` flag on [[EditIssue]], which is a different mechanism with different semantics.
  *
  * [[dueDate]] is optional even though the request that produced it required one: Forgejo answers with whatever it
  * stored, and an instance that echoes nothing is a decoding question, not a domain one.
  *
  * @param dueDate
  *   the deadline the instance now holds, absent when it reported none
  */
final case class IssueDeadline private[codeberg4s] (dueDate: Option[Instant])
