package com.worxbend.codeberg4s.core

/** A decoded list that arrived inside an envelope object, together with the collection size the envelope itself
  * reported.
  *
  * Most Forgejo listings answer a bare JSON array and say how large the whole collection is in the `X-Total-Count`
  * header. A few wrap the array in an object and put the size in the body instead — a tree listing's `total_count`, a
  * wiki page's revision listing's `count` — and on those routes the header may be absent, so the body's number is the
  * only total there is. This type carries it out of the decoder so that [[Pages.fromEnvelope]] can use it exactly as it
  * uses the header: for [[com.worxbend.codeberg4s.paging.Page.totalCount]], and to work out whether another page exists
  * when the response offered no `Link` header to say so.
  *
  * @param items
  *   the decoded items, in the order the server returned them
  * @param totalCount
  *   how many items the whole collection holds according to the body, absent when the body did not say
  * @tparam A
  *   the decoded item type
  */
final case class ListEnvelope[A](items: Vector[A], totalCount: Option[Int])

object ListEnvelope:

  /** A wire count as a total, absent when the wire carried nothing usable.
    *
    * The wire DTOs read numbers as `Long` because JSON numbers have no width. A total is only meaningful as a
    * non-negative `Int` here — it is compared against a page index times a page size — so a negative count, and a count
    * beyond `Int.MaxValue`, are treated as "the body did not say" rather than as a failure of the page.
    */
  def countOf(value: Option[Long]): Option[Int] =
    value.filter(count => count >= 0L && count <= Int.MaxValue.toLong).map(_.toInt)
