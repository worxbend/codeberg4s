package com.worxbend.codeberg4s.repositories.publishing.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.repositories.publishing.Topic

/** Forgejo's `RepoTopicOptions` request model — the body of `PUT /repos/{owner}/{repo}/topics`.
  *
  * An object rather than a case class, for the reason [[CreateReleaseOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`. The '''response''' envelope with the same shape is captured verbatim in
  * `golden/repository/topics.json`, which is what makes the key name `topics` ground truth rather than a guess.
  *
  * '''`topics` is always emitted, empty vector included''', which is the one place in this library where an empty array
  * is a statement rather than silence. This is a `PUT` that replaces the repository's whole topic set, so
  * `{"topics": []}` is how a caller removes every topic; omitting the key would ask Forgejo to replace the set with
  * nothing at all, which it answers with a `422`.
  */
private[codeberg4s] object RepoTopicOptionsDto:

  /** Renders the complete replacement set as the JSON body to `PUT`. */
  def render(topics: Vector[Topic]): String =
    Json.render(JsonValue.Obj("topics" -> JsonValue.Arr.from(topics.map(topic => JsonValue.Str(topic.value)))))
