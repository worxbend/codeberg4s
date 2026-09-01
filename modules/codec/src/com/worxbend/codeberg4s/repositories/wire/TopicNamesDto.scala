package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.publishing.Topic

/** Forgejo's `TopicNames` — the body of `GET /repos/{owner}/{repo}/topics`.
  *
  * A list endpoint that does not return a list: the body is `{"topics": ["forge", "forgejo", "git", "self-hosted"]}`,
  * captured verbatim in `golden/repository/topics.json`. That is a third envelope shape, alongside the bare array most
  * list endpoints use and the `{"ok", "data"}` wrapper the search endpoints use — a client that assumes "list endpoint,
  * therefore array" fails here.
  *
  * The endpoint does declare `page` and `limit`, and Forgejo sends the paging headers, so the result is still delivered
  * as a page: the envelope changes how the items are found, not how many arrive.
  *
  * @param topics
  *   the `topics` key; empty when absent, `null`, or an empty array — which is what a repository with no topics sends
  */
final case class TopicNamesDto(topics: Vector[String]):

  /** The topic names, each one validated as a [[com.worxbend.codeberg4s.repositories.publishing.Topic]].
    *
    * The write side of this endpoint group already speaks `Topic` — `PUT /repos/{owner}/{repo}/topics` takes a whole
    * set of them and `PUT`/`DELETE .../topics/{topic}` take one — so reading names back as bare strings meant a caller
    * that wanted to add to what it had just read had to put every name through `Topic.from` again.
    *
    * '''One rejected name fails the whole page''', with the offending element's own position reported: a topic that is
    * blank, that contains a slash or a control character, or that is `.` or `..` cannot be put back in a request path,
    * and dropping it silently would under-report a set the caller may be about to rewrite. Because the names sit inside
    * a `{"topics": [...]}` envelope rather than in a bare array, the failure reads `$.topics[2]` and not `$[2]`.
    */
  def toDomain: Either[DecodeFailure, Vector[Topic]] =
    ArrayElements.convert(JsonPath.Root.field(TopicNamesDto.EntriesKey), topics): (name, path) =>
      Topic.from(name).left.map(error => DecodeFailure(path, error.message))

object TopicNamesDto:

  /** The wire key the names sit under, so a rejected one is reported at `$.topics[2]` rather than at `$[2]`. */
  val EntriesKey: String = "topics"

  /** Reads a `TopicNames` object. */
  given JsonDecoder[TopicNamesDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): TopicNamesDto =
    TopicNamesDto(topics = fields.texts(EntriesKey))
