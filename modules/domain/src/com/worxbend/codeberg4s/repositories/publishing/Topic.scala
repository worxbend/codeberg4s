package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.PathSegment

/** One repository topic — `forge`, `forgejo`, `git`, `self-hosted` on `golden/repository/topics.json`.
  *
  * A topic is put in a request path by `PUT` and `DELETE /repos/{owner}/{repo}/topics/{topic}`, so a raw `String` would
  * be a path-forging hazard exactly as it is for [[com.worxbend.codeberg4s.repositories.Owner]]. That, and only that,
  * is what this type guarantees: the value is one safe URI path segment.
  *
  * '''It deliberately does not encode Forgejo's own topic grammar.''' The pinned spec declares no `pattern` and no
  * `maxLength` for a topic name anywhere — neither on `RepoTopicOptions.topics` nor on the `topic` path parameter — so
  * any lowercase-only or length rule stated here would be this library guessing at a server rule and rejecting values a
  * differently configured instance would have accepted. An instance that dislikes a name answers `422`, which reaches
  * the caller as [[com.worxbend.codeberg4s.CodebergError.Api]] carrying Forgejo's own explanation.
  *
  * ==Error contract==
  *
  * Construction produces [[ValidationError]] on the `"topic"` field and nothing else; it performs no I/O.
  */
opaque type Topic = String

object Topic:

  /** Parses a topic name.
    *
    * Trims surrounding whitespace. Rejects a blank name, a name containing `/`, and a name containing a control
    * character — the three things that would let a value escape its path segment. See the type's own note for what is
    * deliberately '''not''' checked.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"topic"` field
    */
  def from(value: String): Either[ValidationError, Topic] =
    PathSegment.from("topic", value)

  extension (topic: Topic)

    /** The name as Forgejo spells it, ready to be used as one path segment or as one element of a `topics` array. */
    def value: String = topic
