package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.IssueSubscription

/** Forgejo's `WatchInfo` model as the issue-subscription check returns it.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''': the endpoint answers about the
  * authenticated account and the harvest was anonymous. Five of the model's six declared properties are represented.
  *
  * The sixth, `reason`, is '''not decodable''': the spec gives that property an `x-go-name` and no `type`, `$ref`,
  * `format` or `enum` at all, so there is nothing to read it as. [[com.worxbend.codeberg4s.codec.JsonFields]] ignores a
  * key the DTO does not name, so a payload carrying it still decodes; inventing a type for it would be a guess.
  */
final case class IssueSubscriptionDto(
    subscribed: Option[Boolean],
    ignored: Option[Boolean],
    url: Option[String],
    repositoryUrl: Option[String],
    createdAt: Option[String],
):

  /** Converts to the domain.
    *
    * '''Cannot fail, and there is no `toDomainAt`.''' Nothing is required: the two flags default to `false`, which is
    * the honest reading of "the instance did not say this account is subscribed", and no endpoint returns this object
    * inside an array, so there is no nested path a failure could be reported at. The `Either` is kept so the DTO
    * composes with [[com.worxbend.codeberg4s.client.WireDecode]] exactly like every other one.
    */
  def toDomain: Either[DecodeFailure, IssueSubscription] =
    Right(
      IssueSubscription(
        isSubscribed  = subscribed.getOrElse(false),
        isIgnored     = ignored.getOrElse(false),
        url           = url,
        repositoryUrl = repositoryUrl,
        createdAt     = Timestamps.parseOptional(createdAt),
      )
    )

object IssueSubscriptionDto:

  /** Reads a `WatchInfo` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[IssueSubscriptionDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): IssueSubscriptionDto =
    IssueSubscriptionDto(
      subscribed    = fields.boolean("subscribed"),
      ignored       = fields.boolean("ignored"),
      url           = fields.text("url"),
      repositoryUrl = fields.text("repository_url"),
      createdAt     = fields.text("created_at"),
    )
