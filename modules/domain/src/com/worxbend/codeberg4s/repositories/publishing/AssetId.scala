package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.PositiveId
import com.worxbend.codeberg4s.ValidationError

/** The instance-local identifier of a release attachment, as `/releases/{id}/assets/{attachment_id}` takes it.
  *
  * The same number that arrives as [[com.worxbend.codeberg4s.repositories.ReleaseAsset.id]]. That field is a plain
  * `Long` because it is a *reported* value — a decoder has nothing to validate against and refusing to decode a release
  * because one attachment carries a zero id would be the wrong trade. This type is the other direction: it is an
  * *argument*, it lands in a request path, and next to [[com.worxbend.codeberg4s.repositories.ReleaseId]] — which is
  * also a `Long` and also a path segment on the very same URL — an unwrapped number is one transposition away from a
  * plausible `404`. Lifting a reported id into an argument is [[AssetId.from]].
  *
  * ==Error contract==
  *
  * Construction produces [[ValidationError]] on the `"assetId"` field and nothing else; it performs no I/O.
  */
opaque type AssetId = Long

object AssetId:

  /** Parses an attachment identifier.
    *
    * Rejects zero and negatives, since attachment ids are database row ids that start at one. Nothing else is checked:
    * the value is rendered into a path segment as decimal digits, which cannot forge a path.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"assetId"` field
    */
  def from(value: Long): Either[ValidationError, AssetId] =
    PositiveId.from("assetId", value)

  extension (id: AssetId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id
