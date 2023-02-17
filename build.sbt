// format: off
import FreeGen2._

val catsVersion = "2.9.0"
val catsEffectVersion = "3.4.6"
val circeVersion = "0.14.3"
val fs2Version = "3.6.0"
val h2Version = "1.4.200"
val hikariVersion = "4.0.3"
val munitVersion = "1.0.0-M7"
val postGisVersion = "2.5.1"
val postgresVersion = "42.5.3"
val refinedVersion = "0.10.1"
val scalatestVersion = "3.2.15"
val shapelessVersion = "2.3.9"
val specs2Version = "4.19.2"
val slf4jVersion = "2.0.5"
val weaverVersion = "0.7.15"

// This is used in a couple places. Might be nice to separate these things out.
lazy val postgisDep = "net.postgis" % "postgis-jdbc" % postGisVersion

val Scala213 = "2.13.10"
val Scala3 = "3.2.2"

inThisBuild(Seq(
  organization := "io.github.guymers",
  homepage := Some(url("https://github.com/guymers/foobie")),
  licenses := Seq(License.MIT),
  developers := List(
    Developer("tpolecat", "Rob Norris", "@tpolecat", url("https://github.com/tpolecat")),
    Developer("guymers", "Sam Guymer", "@guymers", url("https://github.com/guymers")),
  ),
  scmInfo := Some(ScmInfo(url("https://github.com/guymers/foobie"), "git@github.com:guymers/foobie.git")),

  sonatypeCredentialHost := "s01.oss.sonatype.org",
  sonatypeRepository := "https://s01.oss.sonatype.org/service/local",
))

lazy val commonSettings = Seq(
  scalaVersion := Scala213,
  crossScalaVersions := Seq(Scala213, Scala3),
  versionScheme := Some("early-semver"),

  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-unchecked",
  ),
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) => Seq(
      "-explaintypes",
      "-language:existentials",
      "-language:higherKinds",
      "-Xsource:3",
    )
    case Some((3, _)) => Seq(
      "-explain-types",
      //"-no-indent",
//      "-source:future",
      "-Ykind-projector",
    )
    case _ => Seq.empty
  }),
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) => Seq(
      "-Ypartial-unification",
    )
    case Some((2, 13)) => Seq(
      "-Vimplicits",
      "-Vtype-diffs",
      "-Wdead-code",
      "-Wextra-implicit",
      "-Wnonunit-statement",
      "-Wnumeric-widen",
      "-Woctal-literal",
      "-Wunused:_",
      "-Wperformance",
      "-Wvalue-discard",

      "-Xlint:_,-byname-implicit", // exclude byname-implicit https://github.com/scala/bug/issues/12072
    )
    case _ => Seq.empty
  }),

  Compile / console / scalacOptions ~= filterScalacConsoleOpts,
  Test / console / scalacOptions ~= filterScalacConsoleOpts,

  Compile / doc / scalacOptions ++= Seq(
    "-groups",
    "-sourcepath", (LocalRootProject / baseDirectory).value.getAbsolutePath,
    "-doc-source-url", "https://github.com/guymers/twoobie/blob/v" + version.value + "€{FILE_PATH}.scala"
  ),

  libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) => Seq(compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full))
    case _ => Seq.empty
  }),

  libraryDependencies ++= Seq(
    "org.typelevel" %% "scalacheck-effect-munit" % "1.0.4" % Test,
    "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
  ),
  testFrameworks += new TestFramework("munit.Framework"),

  // For some reason tests started hanging with docker-compose so let's disable parallelism.
  Test / parallelExecution := false,
)

def filterScalacConsoleOpts(options: Seq[String]) = {
  options.filterNot { opt =>
    opt == "-Xfatal-warnings" || opt.startsWith("-Xlint") || opt.startsWith("-W")
  }
}

def module(name: String) = Project(name, file(s"modules/$name"))
  .settings(moduleName := s"foobie-$name")
  .settings(commonSettings)

lazy val foobie = project.in(file("."))
  .settings(commonSettings)
  .settings(publish / skip := true)
  .aggregate(
    free, core,
    h2, `h2-circe`,
    postgres, `postgres-circe`,
    hikari,
    refined,
    munit, scalatest, specs2, weaver,
    example, bench, docs,
  )
  .disablePlugins(MimaPlugin)

lazy val free = module("free")
  .settings(freeGen2Settings)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-free" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
    ),
    libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(scalaOrganization.value %  "scala-reflect" % scalaVersion.value) // for macros
      case _ => Seq.empty
    }),
    freeGen2Dir := (Compile / scalaSource).value / "doobie" / "free",
    freeGen2Package := "doobie.free",
    freeGen2Classes := List[Class[_]](
      classOf[java.sql.NClob],
      classOf[java.sql.Blob],
      classOf[java.sql.Clob],
      classOf[java.sql.DatabaseMetaData],
      classOf[java.sql.Driver],
      classOf[java.sql.Ref],
      classOf[java.sql.SQLData],
      classOf[java.sql.SQLInput],
      classOf[java.sql.SQLOutput],
      classOf[java.sql.Connection],
      classOf[java.sql.Statement],
      classOf[java.sql.PreparedStatement],
      classOf[java.sql.CallableStatement],
      classOf[java.sql.ResultSet]
    )
  )

lazy val core = module("core")
  .dependsOn(free)
  .settings(
    libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("com.chuusai" %% "shapeless" % shapelessVersion)
      case _ => Seq.empty
    }),
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "typename" % "1.0.0",
      "com.h2database" % "h2" % h2Version % "test",
    ),
  )

lazy val postgres = module("postgres")
  .dependsOn(core % "compile->compile;test->test")
  .settings(freeGen2Settings)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.postgresql" % "postgresql" % postgresVersion,
      postgisDep % "provided",
    ),
    freeGen2Dir := (Compile / scalaSource).value / "doobie" / "postgres" / "free",
    freeGen2Package := "doobie.postgres.free",
    freeGen2Classes := List[Class[_]](
      classOf[org.postgresql.copy.CopyIn],
      classOf[org.postgresql.copy.CopyManager],
      classOf[org.postgresql.copy.CopyOut],
      classOf[org.postgresql.largeobject.LargeObject],
      classOf[org.postgresql.largeobject.LargeObjectManager],
      classOf[org.postgresql.PGConnection]
    ),
    freeGen2Renames ++= Map(
      classOf[org.postgresql.copy.CopyDual]     -> "PGCopyDual",
      classOf[org.postgresql.copy.CopyIn]       -> "PGCopyIn",
      classOf[org.postgresql.copy.CopyManager]  -> "PGCopyManager",
      classOf[org.postgresql.copy.CopyOut]      -> "PGCopyOut",
    ),
    initialCommands := """
      import cats._, cats.data._, cats.implicits._, cats.effect._
      import doobie._, doobie.implicits._
      import doobie.postgres._, doobie.postgres.implicits._
      implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
      val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver", "jdbc:postgresql:world", "postgres", "password")
      val yolo = xa.yolo
      import yolo._
      import org.postgis._
      import org.postgresql.util._
      import org.postgresql.geometric._
    """,
    consoleQuick / initialCommands := "",
  )

lazy val `postgres-circe` = module("postgres-circe")
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
    )
  )
  .dependsOn(core, postgres)

lazy val h2 = module("h2")
  .settings(
    libraryDependencies += "com.h2database" % "h2" % h2Version,
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val `h2-circe` = module("h2-circe")
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
    )
  )
  .dependsOn(core, h2)

lazy val hikari = module("hikari")
  .settings(
    libraryDependencies ++= Seq(
      "com.zaxxer" % "HikariCP" % hikariVersion,
      "org.slf4j" % "slf4j-api" % slf4jVersion,
      "org.slf4j" % "slf4j-nop" % slf4jVersion % "test",
      "com.h2database" % "h2" % h2Version % "test",
    )
  )
  .dependsOn(core, postgres % "test")

lazy val refined = module("refined")
  .settings(
    libraryDependencies ++= Seq(
      "eu.timepit" %% "refined" % refinedVersion,
      "com.h2database" % "h2" % h2Version % "test",
    )
  )
  .dependsOn(core)

lazy val munit = module("munit")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion,
      "com.h2database" % "h2" % h2Version % "test",
    )
  )
  .dependsOn(core)

lazy val scalatest = module("scalatest")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalatest"  %% "scalatest" % scalatestVersion,
      "com.h2database" % "h2" % h2Version % "test",
    )
  )
  .dependsOn(core)

lazy val specs2 = module("specs2")
  .settings(
    libraryDependencies ++= Seq(
      "org.specs2" %% "specs2-core" % specs2Version,
      "com.h2database" % "h2" % h2Version % "test",
    )
  )
  .dependsOn(core)

lazy val weaver = module("weaver")
  .settings(
    libraryDependencies ++= Seq(
      "com.disneystreaming" %% "weaver-cats" % weaverVersion,
      "com.h2database" % "h2" % h2Version % "test",
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  )
  .dependsOn(core)

lazy val example = project.in(file("modules/example"))
  .settings(commonSettings)
  .settings(publish / skip := true)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % fs2Version,
    )
  )
  .dependsOn(core, postgres, specs2, scalatest, hikari, h2)

lazy val bench = project.in(file("modules/bench"))
  .settings(commonSettings)
  .settings(publish / skip := true)
  .enablePlugins(JmhPlugin)
  .dependsOn(core, postgres)

lazy val docs = project.in(file("modules/docs"))
  .dependsOn(core, postgres, h2, hikari, munit, scalatest, specs2, weaver)
  .settings(commonSettings)
  .settings(publish / skip := true)
  .enablePlugins(GhpagesPlugin)
  .enablePlugins(ParadoxPlugin)
  .enablePlugins(ParadoxSitePlugin)
  .enablePlugins(MdocPlugin)
  .settings(
    scalacOptions := Nil,

    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
    ),
    // postgis is `provided` dependency for users, and section from book of doobie needs it
    libraryDependencies += postgisDep,
    Test / fork := true,

    version := version.value.takeWhile(_ != '+'), // strip off the +3-f22dca22+20191110-1520-SNAPSHOT business

    git.remoteRepo := "git@github.com:guymers/foobie.git",
    ghpagesNoJekyll := true,
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    paradoxProperties ++= Map(
      "scala-versions" -> {
        val crossVersions = (core / crossScalaVersions).value.flatMap(CrossVersion.partialVersion)
        val scala2Versions = crossVersions.filter(_._1 == 2).map(_._2).mkString("2.", "/", "") // 2.12/13
        val scala3 = crossVersions.find(_._1 == 3).map(_ => "3") // 3
        List(Some(scala2Versions), scala3).flatten.filter(_.nonEmpty).mkString(" and ") // 2.12/13 and 3
      },
      "org"                      -> organization.value,
      "scala.binary.version"     -> CrossVersion.binaryScalaVersion(scalaVersion.value),
      "version"                  -> version.value,
      "catsVersion"              -> catsVersion,
      "fs2Version"               -> fs2Version,
      "shapelessVersion"         -> shapelessVersion,
      "h2Version"                -> h2Version,
      "postgresVersion"          -> postgresVersion,
      "scalaVersion"             -> scalaVersion.value,
    ),

    mdocIn := baseDirectory.value / "src" / "main" / "mdoc",
    Compile / paradox / sourceDirectory := mdocOut.value,
    makeSite := makeSite.dependsOn(mdoc.toTask("")).value,
  )
