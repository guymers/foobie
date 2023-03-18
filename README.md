
**foobie** is a fork of [doobie](https://github.com/tpolecat/doobie) that aims to try and keep source compatability.

Currently contains the following changes:
- `Read` and `Write` derivation is now opt-in via `import doobie.util.Read.Auto.*` and `import doobie.util.Write.Auto.*`
- Java time instances are available without an explicit import
- removed Scala 2.12 support along with *IO implicits in `doobie.implicit.*` import
- PostGIS instances have been moved to the new `postgis` module and are available under `doobie.postgis.instances.{geography,geometry}`
- `LogHandler` has been removed. Override interpreters if you want to log things.
- removed `WeakAsync`, a `ConnectionIO` is pretty much always a database transaction and should not be mixed with an `IO` implementation
- Requires Java 11

To use add at least the core module to your project:
```
"io.github.guymers" %% "foobie-core" % <version>
```
