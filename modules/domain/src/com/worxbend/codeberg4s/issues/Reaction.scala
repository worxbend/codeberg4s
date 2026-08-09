package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** One account's reaction to an issue or to a comment.
  *
  * '''Derived from `spec/swagger.v1.json`''' — Forgejo's `Reaction`, which declares exactly three properties. No golden
  * fixture covers a reaction: the harvest was anonymous and no captured issue carried one.
  *
  * A reaction is per account and per content, so the listing endpoints return one element for each account that reacted
  * with each emoji rather than a tally. Counting is the caller's job, and grouping by [[content]] is how it is done.
  *
  * @param content
  *   what the reaction says; an open vocabulary, see [[ReactionContent]]
  * @param user
  *   who reacted, absent when the instance sent no account — the same allowance [[Comment.author]] makes for imported
  *   content
  * @param createdAt
  *   when the reaction was recorded
  */
final case class Reaction private[codeberg4s] (content: ReactionContent, user: Option[User], createdAt: Option[Instant])
