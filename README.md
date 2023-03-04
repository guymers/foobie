
**foobie** is a fork of [doobie](https://github.com/tpolecat/doobie) that aims to try and keep source compatability.

Currently contains the following changes:
- Java time instances are available without an explicit import
- removed Scala 2.12 support along with *IO implicits in `doobie.implicit.*` import
- PostGIS instances have been moved to the new `postgis` module and are available under `doobie.postgis.instances.{geography,geometry}`
- `LogHandler` has been removed. Override interpreters if you want to log things.
