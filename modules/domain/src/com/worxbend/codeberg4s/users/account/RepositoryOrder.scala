package com.worxbend.codeberg4s.users.account

/** How `GET /user/repos` should order the repositories it returns — the `order_by` query parameter.
  *
  * '''The spec enumerates all eighteen values''', which is why this is an enum and not a `String`: the endpoint answers
  * `422` for a spelling it does not know, and a typo that costs a round trip to discover is exactly what a closed set
  * prevents. `spec/swagger.v1.json` lists them on `userCurrentListRepos` and nowhere else, so this type is scoped to
  * this group rather than shared.
  *
  * ==The vocabulary is not symmetric, and that is Forgejo's doing==
  *
  * Some pairs are spelled with a `reverse` prefix (`alphabetically` / `reversealphabetically`), some with a `most` /
  * `fewest` prefix (`moststars` / `feweststars`), and the two date orderings are `newest` / `oldest` and `recentupdate`
  * / `leastupdate`. The cases below keep Forgejo's own pairing rather than imposing a scheme, because renaming them
  * would mean a reader could not find the value they saw in the API documentation.
  *
  * [[RepositoryOrder.Default]] is not a value the API declares: it is this library's name for "send no `order_by` at
  * all and take whatever the instance does", which is a request a caller has to be able to express and which no
  * enumerated value spells.
  */
enum RepositoryOrder:

  /** Send no ordering and accept the instance's own. Not a value the API declares; see the enum note. */
  case Default

  /** By name — `name`. */
  case Name

  /** By identifier, which is creation order — `id`. */
  case Id

  /** Newest first — `newest`. */
  case Newest

  /** Oldest first — `oldest`. */
  case Oldest

  /** Most recently updated first — `recentupdate`. */
  case RecentUpdate

  /** Least recently updated first — `leastupdate`. */
  case LeastUpdate

  /** Alphabetically ascending — `alphabetically`. */
  case Alphabetically

  /** Alphabetically descending — `reversealphabetically`. */
  case ReverseAlphabetically

  /** By total size ascending — `size`. */
  case Size

  /** By total size descending — `reversesize`. */
  case ReverseSize

  /** By Git repository size ascending — `gitsize`. */
  case GitSize

  /** By Git repository size descending — `reversegitsize`. */
  case ReverseGitSize

  /** By Git LFS size ascending — `lfssize`. */
  case LfsSize

  /** By Git LFS size descending — `reverselfssize`. */
  case ReverseLfsSize

  /** Most starred first — `moststars`. */
  case MostStars

  /** Fewest starred first — `feweststars`. */
  case FewestStars

  /** Most forked first — `mostforks`. */
  case MostForks

  /** Fewest forked first — `fewestforks`. */
  case FewestForks

object RepositoryOrder:

  extension (order: RepositoryOrder)

    /** The `order_by` value to send, absent for [[RepositoryOrder.Default]] — which is how "send nothing" is said. */
    def wireValue: Option[String] =
      order match
        case Default               => None
        case Name                  => Some("name")
        case Id                    => Some("id")
        case Newest                => Some("newest")
        case Oldest                => Some("oldest")
        case RecentUpdate          => Some("recentupdate")
        case LeastUpdate           => Some("leastupdate")
        case Alphabetically        => Some("alphabetically")
        case ReverseAlphabetically => Some("reversealphabetically")
        case Size                  => Some("size")
        case ReverseSize           => Some("reversesize")
        case GitSize               => Some("gitsize")
        case ReverseGitSize        => Some("reversegitsize")
        case LfsSize               => Some("lfssize")
        case ReverseLfsSize        => Some("reverselfssize")
        case MostStars             => Some("moststars")
        case FewestStars           => Some("feweststars")
        case MostForks             => Some("mostforks")
        case FewestForks           => Some("fewestforks")
