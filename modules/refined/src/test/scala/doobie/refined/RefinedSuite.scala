// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.refined

import cats.Show
import cats.syntax.show.*
import doobie.refined.implicits.*
import doobie.syntax.string.*
import doobie.util.Write
import doobie.util.invariant.SecondaryValidationFailed
import doobie.util.meta.Meta
import doobie.util.update.Update0
import eu.timepit.refined.api.Refined
import eu.timepit.refined.api.RefinedTypeOps
import eu.timepit.refined.api.Validate
import eu.timepit.refined.numeric.Positive
import zio.test.TestAspect
import zio.test.assertCompletes
import zio.test.assertTrue

object RefinedSuite extends H2DatabaseSpec {
  import doobie.util.Read.Auto.*
  import doobie.util.Write.Auto.*

  type PositiveInt = Int Refined Positive
  object PositiveInt extends RefinedTypeOps.Numeric[PositiveInt, Int] {
    // apply doesn't work in Scala 3
    val `5` = PositiveInt.from(5).toOption.get
    val `123` = PositiveInt.from(123).toOption.get
  }

  case class Point(x: Int, y: Int)
  object Point {
    implicit val show: Show[Point] = Show.fromToString

    implicit val write: Write[Point] = Write.derived[(Int, Int)].contramap(p => (p.x, p.y))
  }

  case class Quadrant1()
  type PointInQuadrant1 = Point Refined Quadrant1

  implicit val quadrant1Validate: Validate.Plain[Point, Quadrant1] =
    Validate.fromPredicate(p => p.x >= 0 && p.y >= 0, p => show"($p is in quadrant 1)", Quadrant1())

  override val spec = suite("Refined")(
    test("Meta should exist for refined types") {
      val _ = Meta[PositiveInt]
      assertCompletes
    },
    test("Write should exist for refined types") {
      val _ = Write[PointInQuadrant1]
      assertCompletes
    },
    test("Write should exist for Option of a refined type") {
      val _ = Write[Option[PositiveInt]]
      assertCompletes
    },
    suite("query")(
      test("return a refined type when conversion is possible") {
        fr"select 123".query[PositiveInt].unique.transact.map { result =>
          assertTrue(result == PositiveInt.`123`)
        }
      },
      test("return an Option of a refined type when query returns null-value") {
        fr"select NULL".query[Option[PositiveInt]].unique.transact.map { result =>
          assertTrue(result == None)
        }
      },
      test("return an Option of a refined type when query returns a value and conversion is possible") {
        fr"select 123".query[Option[PositiveInt]].unique.transact.map { result =>
          assertTrue(result == Some(PositiveInt.`123`))
        }
      },
      suite("save optional refined type")(
        test("none") {
          val none: Option[PositiveInt] = None
          insertOptionalPositiveInt(none).transact.either.map { result =>
            assertTrue(result.isRight)
          }
        },
        test("some") {
          val somePositiveInt: Option[PositiveInt] = Some(PositiveInt.`5`)
          insertOptionalPositiveInt(somePositiveInt).transact.either.map { result =>
            assertTrue(result.isRight)
          }
        },
      ) @@ TestAspect.sequential,
      test("throw an SecondaryValidationFailed if value does not fit the refinement-type ") {
        fr"select -1".query[PositiveInt].unique.transact.either.map { result =>
          assertTrue(result == Left(SecondaryValidationFailed[PositiveInt]("Predicate failed: (-1 > 0).")))
        }
      },
      test("return a refined product-type when conversion is possible") {
        fr"select 1, 1".query[PointInQuadrant1].unique.transact.either.map { result =>
          assertTrue(result.isRight)
        }
      },
      test("throw an SecondaryValidationFailed if object does not fit the refinement-type ") {
        fr"select -1, 1".query[PointInQuadrant1].unique.transact.either.map { result =>
          val msg = "Predicate failed: (Point(-1,1) is in quadrant 1)."
          assertTrue(result == Left(SecondaryValidationFailed[PointInQuadrant1](msg)))
        }
      },
    ),
  )

  private def insertOptionalPositiveInt(v: Option[PositiveInt]) = for {
    _ <- Update0(s"CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_opt_post_int (v INT) NOT PERSISTENT", None).run
    _ <- fr"INSERT INTO test_opt_post_int VALUES ($v)".update.run
  } yield ()
}
