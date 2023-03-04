## Logging

If for some reason you want to log SQL you can extend `ConnectionInterpreter`.

```scala
case class User(name: String)
val currentUser = IOLocal[Option[User]](None).unsafeRunSync()

def logSQL[T](sql: String, result: Kleisli[IO, Connection, T]): Kleisli[IO, Connection, T] = {
  result.tapWithF { case (_, t) =>
    for {
      user <- currentUser.get
      _ <- IO.delay {
        println(s"user $user; sql: '$sql'")
      }
    } yield t
  }
}

val i = KleisliInterpreter[IO]
val LoggingConnectionInterpreter = new i.ConnectionInterpreter {
  override def prepareStatement(a: String) = logSQL(a, super.prepareStatement(a))
}

val loggingDb = db.copy(interpret0 = LoggingConnectionInterpreter)
```

### Caveats

Note that the invocation is part of your `ConnectionIO` program, and it is called synchronously. Most back-end loggers are asynchronous so this is unlikely to be an issue, but do take care not to spend too much time in your handler.

Further note that the handler is not transactional; anything your logger does stays done, even if the transaction is rolled back. This is only for diagnostics, not for business logic.
