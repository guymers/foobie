
## Build Commands

- `sbt compile` -> compile code
- `sbt Test/compile` -> compile test code
- `sbt scalafmtAll` -> format all code
- `sbt modules/test` -> run all unit tests (what CI runs)
- `sbt testFull` -> run all tests, including integration tests
- `sbt testIntegration` -> run the integration tests (requires PostgreSQL and MySQL)
- `sbt testOnly <fully qualified class name>` -> run tests in the given class, e.g. `sbt "zio/testOnly zoobie.ConnectionPoolSpec"`

### sbt Usage

- run one command per `sbt` call
- sbt 2 caches aggressively, if a command returns successfully everything is up to date
- `sbt test` is incremental and skips tests whose inputs have not changed, use `sbt testFull` to run everything
