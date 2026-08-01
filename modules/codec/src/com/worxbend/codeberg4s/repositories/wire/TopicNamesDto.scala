package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.JsonFields

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

  /** The topic names. Cannot fail: a topic is free text, and an absent list means a repository with no topics. */
  def toDomain: Vector[String] =
    topics

object TopicNamesDto:

  /** Reads a `TopicNames` object. */
  given upickle.default.Reader[TopicNamesDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): TopicNamesDto =
    TopicNamesDto(topics = fields.texts("topics"))
