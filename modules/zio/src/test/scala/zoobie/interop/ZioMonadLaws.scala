package zoobie.interop

import cats.Eq
import cats.laws.discipline.MonadTests
import munit.DisciplineSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import zio.Unsafe
import zio.ZIO

class ZioMonadLaws extends DisciplineSuite {
  import ZioMonadLaws.*

  checkAll("Monad", MonadTests[ZIO[Any, Nothing, *]].monad[Int, Int, Int])
}
object ZioMonadLaws {

  private def run[A](io: ZIO[Any, Nothing, A]): A = Unsafe.unsafe { implicit unsafe =>
    zio.Runtime.default.unsafe.run(io).getOrThrowFiberFailure()
  }

  implicit def eqZIO[A: Eq]: Eq[ZIO[Any, Nothing, A]] = Eq.by(run(_))

  implicit val arbitraryZIOInt: Arbitrary[ZIO[Any, Nothing, Int]] = Arbitrary {
    Arbitrary.arbInt.arbitrary.map(ZIO.succeed(_))
  }

  implicit val arbitraryZIOIntToInt: Arbitrary[ZIO[Any, Nothing, Int => Int]] = Arbitrary {
    Gen.function1[Int, Int](Arbitrary.arbInt.arbitrary).map(ZIO.succeed(_))
  }
}
