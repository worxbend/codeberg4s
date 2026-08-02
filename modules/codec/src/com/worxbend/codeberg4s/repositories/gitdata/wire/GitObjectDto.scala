package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.gitdata.GitObjectKind
import com.worxbend.codeberg4s.repositories.gitdata.GitObjectRef

/** Forgejo's `GitObject`, and its identical twin `AnnotatedTagObject`.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' The two definitions declare the same three keys with
  * the same types and differ only in name, so one DTO reads both — inventing a second would be the duplication
  * `docs/LEDGER.md` forbids, and the domain models them as one
  * [[com.worxbend.codeberg4s.repositories.gitdata.GitObjectRef]] for the same reason.
  *
  * @param sha
  *   the `sha` key
  * @param objectType
  *   the `type` key, renamed because `type` is a Scala keyword
  * @param url
  *   the `url` key
  */
final case class GitObjectDto(sha: Option[String], objectType: Option[String], url: Option[String]):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `sha` and puts it through [[com.worxbend.codeberg4s.repositories.CommitSha.from]]: a pointer without the
    * id it points at is not a pointer. An unrecognised `type` becomes `None` rather than a failure — it is descriptive
    * and decides nothing about how the rest of the object is read, which is the argument
    * [[com.worxbend.codeberg4s.repositories.gitdata.GitObjectKind]] spells out.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, GitObjectRef] =
    Wire
      .validated(at, "sha", sha)(CommitSha.from)
      .map(id => GitObjectRef(sha = id, kind = objectType.flatMap(GitObjectKind.parse), url = url))

object GitObjectDto:

  /** Reads a `GitObject` or an `AnnotatedTagObject`. */
  given JsonDecoder[GitObjectDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the DTOs that embed this one. */
  def fromFields(fields: JsonFields): GitObjectDto =
    GitObjectDto(
      sha        = fields.text("sha"),
      objectType = fields.text("type"),
      url        = fields.text("url"),
    )
