## Extensions for H2

In this chapter we discuss the extended support that **doobie** offers for users of [H2](http://www.h2database.com/html/main.html) . To use these extensions you must add an additional dependency to your project:

@@@ vars
```scala
libraryDependencies += "org.tpolecat" %% "doobie-h2" % "$version$"
```
@@@

This library pulls in H2 as a transitive dependency.

There are extensions available for dealing with JSON by using Circe, if you like to use those, include this dependency:

@@@ vars

```scala
libraryDependencies += "org.tpolecat" %% "doobie-h2-circe" % "$version$"
```

@@@

Then, you will be able to import the implicits for dealing with JSON:

@@@ vars

```scala
import doobie.h2.circe.json.implicits
```

@@@

### Array Types

**doobie** supports H2 arrays of the following types:

- `Boolean`
- `Int`
- `Long`
- `Float`
- `Double`
- `String`

In addition to `Array` you can also map to `List` and `Vector`.

See the previous chapter on **SQL Arrays** for usage examples.

### Other Nonstandard Types

- The `uuid` type is supported and maps to `java.util.UUID`.

### H2 Connection Pool

**doobie** provides a `Transactor` that wraps the connection pool provided by H2. Because the transactor has internal state, constructing one is a side-effect that must be captured (here by `IO`).

```scala mdoc:silent:reset
import cats.effect.*
import cats.implicits.*
import doobie.h2.H2Transactor
import doobie.syntax.connectionio.*
import doobie.syntax.string.*
import doobie.util.ExecutionContexts

object H2App extends IOApp {

  // Resource yielding a transactor configured with a bounded connect EC and an unbounded
  // transaction EC. Everything will be closed and shut down cleanly after use.
  val transactor: Resource[IO, H2Transactor[IO]] =
    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](32) // our connect EC
      xa <- H2Transactor.newH2Transactor[IO](
              "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", // connect URL
              "sa",                                   // username
              "",                                     // password
              ce,                                     // await connection here
            )
    } yield xa


  def run(args: List[String]): IO[ExitCode] =
    transactor.use { xa =>

      // Construct and run your server here!
      for {
        n <- sql"select 42".query[Int].unique.transact(xa)
        _ <- IO(println(n))
      } yield ExitCode.Success

    }

}
```
