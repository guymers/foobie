## Unit Testing

The YOLO-mode query checking feature demonstrated in an earlier chapter is also available as a trait you can mix into your [ScalaTest](http://www.scalatest.org/), [MUnit](https://scalameta.org/munit) or [Weaver](https://disneystreaming.github.io/weaver-test/) unit tests.

### Setting Up

As with earlier chapters we set up a `Transactor` and YOLO mode. We will also use the `doobie-scalatest` module.

```scala mdoc:silent
import doobie.syntax.string.*
import doobie.util.Read
import doobie.util.Write
import doobie.util.transactor.Transactor
import doobie.util.update.Update0
import cats.*
import cats.data.*
import cats.effect.*
import cats.implicits.*

// This is just for testing. Consider using cats.effect.IOApp instead of calling
// unsafe methods directly.
import cats.effect.unsafe.implicits.global

// A transactor that gets connections from java.sql.DriverManager and executes blocking operations
// on an our synchronous EC. See the chapter on connection handling for more info.
val xa = Transactor.fromDriverManager[IO](
  "org.postgresql.Driver",     // driver classname
  "jdbc:postgresql:world",     // connect URL (driver-specific)
  "postgres",                  // user
  "password"                   // password
)
```

```scala mdoc:invisible
implicit val mdocColors: doobie.util.Colors = doobie.util.Colors.None
```

And again we are playing with the `country` table, given here for reference.

```sql
CREATE TABLE country (
  code        character(3)  NOT NULL,
  name        text          NOT NULL,
  population  integer       NOT NULL,
  gnp         numeric(10,2),
  indepyear   smallint
  -- more columns, but we won't use them here
)
```

So here are a few queries we would like to check. Note that we can only check values of type `Query0` and `Update0`; we can't check `Process` or `ConnectionIO` values, so a good practice is to define your queries in a DAO module and apply further operations at a higher level.

```scala mdoc:silent
case class Country(code: Int, name: String, pop: Int, gnp: Double)
object Country {
  implicit val read: Read[Country] = Read.derived
  implicit val write: Write[Country] = Write.derived
}

val trivial =
  sql"""
    select 42, 'foo'::varchar
  """.query[(Int, String)]

def biggerThan(minPop: Short) =
  sql"""
    select code, name, population, gnp, indepyear
    from country
    where population > $minPop
  """.query[Country]

val update: Update0 =
  sql"""
    update country set name = "new" where name = "old"
  """.update

```

### The ScalaTest Package

The `doobie-scalatest` add-on provides a mix-in trait that we can add to any `Assertions` implementation (like `AnyFunSuite`).

```scala mdoc:silent
import org.scalatest.*

class AnalysisTestScalaCheck extends funsuite.AnyFunSuite with matchers.must.Matchers with doobie.scalatest.IOChecker {

  override val colors = doobie.util.Colors.None // just for docs

  val transactor = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", "jdbc:postgresql:world", "postgres", "password"
  )

  test("trivial")    { check(trivial)        }
  test("biggerThan") { checkOutput(biggerThan(0))  }
  test("update")     { check(update) }

}
```

Details are shown for failing tests.

```scala mdoc
// Run a test programmatically. Usually you would do this from sbt, bloop, etc.
(new AnalysisTestScalaCheck).execute(color = false)
```

### The MUnit Package

The `doobie-munit` add-on provides a mix-in trait that we can add to any `Assertions` implementation (like `FunSuite`) much like the ScalaTest package above.

```scala mdoc:silent
import _root_.munit.*

class AnalysisTestSuite extends FunSuite with doobie.munit.IOChecker {

  override val colors = doobie.util.Colors.None // just for docs

  val transactor = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", "jdbc:postgresql:world", "postgres", "password"
  )

  test("trivial")    { check(trivial)        }
  test("biggerThan") { checkOutput(biggerThan(0))  }
  test("update")     { check(update) }

}
```

### The Weaver Package

The `doobie-weaver` add-on provides a mix-in trait what we can add to any effectful test Suite. 
The `check` function takes an implicit `Transactor[F]` parameter. Since Weaver has its own way 
to manage shared resources, it is convenient to use that to allocate the transcator. 

```scala mdoc:silent
import _root_.weaver.*
import doobie.weaver.*

object AnalysisTestSuite extends IOSuite with IOChecker {

  override type Res = Transactor[IO]
  override def sharedResource: Resource[IO,Res] = 
    Resource.pure(Transactor.fromDriverManager[IO](
      "org.postgresql.Driver", "jdbc:postgresql:world", "postgres", "password"
    ))

  test("trivial")    { implicit transactor => check(trivial)        }
  test("biggerThan") { implicit transactor => checkOutput(biggerThan(0))  }
  test("update")     { implicit transactor => check(update) }

}
```
