package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.ValidationError

/** What `POST /repos/{owner}/{repo}/keys` is told.
  *
  * {{{
  * CreateDeployKey.of("ci runner", "ssh-ed25519 AAAAC3Nz… deploy@ci").map(_.readOnly)
  * }}}
  *
  * `CreateKeyOption` is one of the 38 definitions `docs/HAZARDS.md` §1 found carrying a `required` list, and it names
  * both `title` and `key`. Both are therefore demanded by [[CreateDeployKey.of]] rather than left optional.
  *
  * ==The default grant is read-write, and this type does not quietly change that==
  *
  * `read_only` is a `Boolean` whose Go zero value is `false`, so a body that omits it creates a key that may
  * '''push'''. This command therefore always emits the field: a caller who never mentioned read-only gets
  * `"read_only": false` spelled out rather than left to a default, so that what the instance receives is what the
  * caller can read in the request. [[readOnly]] is the one-word way to ask for the safer grant, and it is the one most
  * CI keys want.
  *
  * The key material itself is public and is not treated as a credential; see [[DeployKey]] for the whole argument.
  *
  * @param title
  *   the label the key is listed under. Forgejo requires it to be unique within the repository and answers `422` when
  *   it is not
  * @param key
  *   the armoured public key, `ssh-ed25519 AAAA… comment`. Sent verbatim: Forgejo parses it, and a client that
  *   normalised whitespace here could change the key it is registering
  * @param isReadOnly
  *   whether the key may fetch but not push
  */
final case class CreateDeployKey(
    title: String,
    key: String,
    isReadOnly: Boolean,
):

  /** Registers the key with fetch-only access. */
  def readOnly: CreateDeployKey = copy(isReadOnly = true)

  /** Registers the key with push access — the API's own default, stated explicitly. */
  def readWrite: CreateDeployKey = copy(isReadOnly = false)

object CreateDeployKey:

  /** Starts a command from the two properties `CreateKeyOption` marks required.
    *
    * Trims the title and rejects a blank one. The key is trimmed of surrounding whitespace only — its interior is left
    * exactly as given, because an SSH key is whitespace-delimited and rewriting it would register something other than
    * what the caller holds — and a blank one is rejected. Both would be a `422` after a round trip, and finding that
    * out here is strictly better.
    *
    * The grant defaults to read-write, matching the API's own default; [[CreateDeployKey.readOnly]] narrows it.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"deployKeyTitle"` or `"deployKey"` field
    */
  def of(title: String, key: String): Either[ValidationError, CreateDeployKey] =
    val trimmedTitle = title.trim
    val trimmedKey   = key.trim

    if trimmedTitle.isEmpty then Left(ValidationError("deployKeyTitle", "must not be blank"))
    else if trimmedKey.isEmpty then Left(ValidationError("deployKey", "must not be blank"))
    else Right(CreateDeployKey(title = trimmedTitle, key = trimmedKey, isReadOnly = false))

/** The filters `GET /repos/{owner}/{repo}/keys` takes.
  *
  * Both narrow the listing; neither identifies anything on its own, because a repository may hold several keys and the
  * endpoint answers an array whatever the filter. As everywhere else in this library, only what the caller set is sent
  * — [[DeployKeyQuery.Empty]] renders to no parameters at all.
  *
  * @param keyId
  *   the `key_id` of the underlying SSH key row, '''not''' a [[DeployKeyId]]. The two are different numbers for the
  *   same key and passing one for the other yields an empty page rather than an error, which is exactly why
  *   [[DeployKey.keyId]] carries the name it does
  * @param fingerprint
  *   the instance's fingerprint of the key, matched exactly. The usual source is [[DeployKey.fingerprint]] or
  *   `ssh-keygen -lf`
  */
final case class DeployKeyQuery(keyId: Option[Long], fingerprint: Option[String]):

  /** Restricts the listing to the deploy key backed by the SSH key row `value`; see [[keyId]]. */
  def forKeyId(value: Long): DeployKeyQuery = copy(keyId = Some(value))

  /** Restricts the listing to the key carrying exactly this fingerprint. */
  def withFingerprint(value: String): DeployKeyQuery = copy(fingerprint = Some(value))

object DeployKeyQuery:

  /** No filter — every deploy key of the repository. */
  val Empty: DeployKeyQuery = DeployKeyQuery(None, None)
