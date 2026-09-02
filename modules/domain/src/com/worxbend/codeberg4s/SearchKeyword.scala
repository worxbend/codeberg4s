package com.worxbend.codeberg4s

/** How the free-text `q` parameter of a search endpoint is checked, once, for every search this library offers.
  *
  * Forgejo's search endpoints all take the keyword under the same name and give it the same non-treatment: it is
  * matched against whatever that endpoint considers searchable and never validated. Two things are still worth
  * catching before a request is built.
  *
  * '''Surrounding whitespace is dropped.''' A keyword is routinely pasted out of a browser or read off a command line,
  * and `" forgejo "` is not a search anybody meant to run — Forgejo would match the spaces.
  *
  * '''A keyword that is only whitespace is not a keyword.''' Omitting `q` and sending `q=` ask the same question of
  * these endpoints — everything the credentials can see, page by page — so a blank one becomes `None` rather than an
  * empty parameter. That keeps a query string that says what it does.
  *
  * '''A control character is rejected.''' A newline or a NUL in a keyword is always a mistake in the calling program —
  * an unstripped line of input, most often — and answering it here costs nothing, where answering it remotely costs a
  * round trip and comes back as a `400` or `422` with no explanation.
  */
private[codeberg4s] object SearchKeyword:

  /** Normalises a raw keyword into what a query object should hold.
    *
    * @param field
    *   the lower-camel-case field name a rejection is reported under, so the caller is told which argument was wrong
    * @param value
    *   the keyword as the caller wrote it
    * @return
    *   `Some(trimmed)`, `None` for a blank keyword, or a [[ValidationError]] on `field`
    */
  def normalize(field: String, value: String): Either[ValidationError, Option[String]] =
    val trimmed = value.trim

    if trimmed.exists(_.isControl) then Left(ValidationError(field, "must not contain a control character"))
    else if trimmed.isEmpty then Right(None)
    else Right(Some(trimmed))
