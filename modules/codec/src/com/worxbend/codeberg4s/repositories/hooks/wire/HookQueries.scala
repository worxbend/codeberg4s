package com.worxbend.codeberg4s.repositories.hooks.wire

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API classes for the reason
  * [[com.worxbend.codeberg4s.repositories.actions.wire.ActionQueries]] gives: `limit` and `ref` are wire spellings, and
  * rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. It also
  * means the shape of a request can be asserted on directly, without a stub backend.
  */
private[codeberg4s] object HookQueries:

  /** The `ref` parameter of the webhook test route.
    *
    * Empty when the caller named no reference, which asks Forgejo to load the commit it would have chosen itself.
    */
  def hookTest(ref: Option[String]): List[(String, String)] =
    ref.toList.map(value => "ref" -> value)
