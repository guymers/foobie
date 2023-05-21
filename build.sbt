// format: off
import FreeGen2._

val catsVersion = "2.9.0"
val catsEffectVersion = "3.5.0"
val circeVersion = "0.14.5"
val fs2Version = "3.7.0"
val h2Version = "2.1.214"
val hikariVersion = "5.0.1"
val magnoliaVersion = "1.1.3"
val munitVersion = "1.0.0-M7"
val mysqlVersion = "8.0.33"
val postgisVersion = "2021.1.0"
val postgresVersion = "42.6.0"
val refinedVersion = "0.10.3"
val scalatestVersion = "3.2.16"
val shapelessVersion = "2.3.10"
val specs2Version = "4.20.0"
val slf4jVersion = "2.0.7"
val weaverVersion = "0.8.3"
val zioInteropCats = "23.0.0.4"
val zioVersion = "2.0.13"

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

// allow Test and IntegrationTest to compile together
val AllTests = sbt.config("tt") extend (Test, IntegrationTest)

lazy val commonSettings = Seq(
  scalaVersion := Scala213,
  crossScalaVersions := Seq(Scala213, Scala3),
  versionScheme := Some("pvp"),

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
      "-explain",
      "-explain-types",
      "-language:adhocExtensions",
      "-no-indent",
      "-source:future",
      "-Ykind-projector",
    )
    case _ => Seq.empty
  }),
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
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
  Test / scalacOptions --= Seq("-Wperformance"),

  Compile / console / scalacOptions ~= filterScalacConsoleOpts,
  Test / console / scalacOptions ~= filterScalacConsoleOpts,

  Compile / doc / scalacOptions ++= Seq(
    "-groups",
    "-sourcepath", (LocalRootProject / baseDirectory).value.getAbsolutePath,
    "-doc-source-url", "https://github.com/guymers/foobie/blob/v" + version.value + "€{FILE_PATH}.scala"
  ),

  libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) => Seq(compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full))
    case _ => Seq.empty
  }),

  Compile / compile / wartremoverErrors := (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) => Warts.all
    case _ => Seq.empty // increases Scala 3 compile times 10x
  }),
  Compile / compile / wartremoverErrors --= Seq(
    Wart.Any,
    Wart.DefaultArguments,
    Wart.Equals,
    Wart.ImplicitConversion, // TODO remove
    Wart.ImplicitParameter,
    Wart.Nothing,
    Wart.Overloading,
    Wart.PublicInference,
  ),
  Test / compile / wartremoverErrors := Seq(
    Wart.NonUnitStatements,
    Wart.Null,
    Wart.Return,
  ),
)

def filterScalacConsoleOpts(options: Seq[String]) = {
  options.filterNot { opt =>
    opt == "-Xfatal-warnings" || opt.startsWith("-Xlint") || opt.startsWith("-W")
  }
}

def module(name: String) = Project(name, file(s"modules/$name"))
  .settings(moduleName := s"foobie-$name")
  .settings(commonSettings)
  .settings(
    mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet
  )
  .configs(IntegrationTest)
  .settings(inConfig(IntegrationTest)(Defaults.testSettings))
  .settings(inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings))
  .configs(AllTests)
  .settings(inConfig(AllTests)(Defaults.testSettings))

lazy val foobie = project.in(file("."))
  .settings(commonSettings)
  .settings(publish / skip := true)
  .aggregate(
    free, core,
    h2, `h2-circe`,
    mysql,
    postgres, `postgres-circe`, postgis,
    hikari,
    refined,
    munit, scalatest, specs2, weaver,
    zio,
    example, bench, docs,
  )
  .configs(AllTests, IntegrationTest)
  .disablePlugins(MimaPlugin)

lazy val free = module("free")
  .settings(freeGen2Settings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-free" % catsVersion,
      "org.typelevel" %% "cats-effect-kernel" % catsEffectVersion,
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
    ),
  )

lazy val core = module("core")
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect-kernel" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.tpolecat" %% "typename" % "1.1.0",

      "com.h2database" % "h2" % h2Version % Test,
      "dev.zio" %% "zio-interop-cats" % zioInteropCats % Test,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    ),
    libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(
        "com.softwaremill.magnolia1_2" %% "magnolia" % magnoliaVersion,
        "com.chuusai" %% "shapeless" % shapelessVersion % Test,
      )
      case _ => Seq.empty
    }),
  )
  .dependsOn(free)

lazy val postgres = module("postgres")
  .settings(freeGen2Settings)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.postgresql" % "postgresql" % postgresVersion,

      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    ),
    libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("com.chuusai" %% "shapeless" % shapelessVersion)
      case _ => Seq.empty
    }),
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
  .dependsOn(core % "compile->compile;test->test", zio % "test->compile")

lazy val postgis = module("postgis")
  .settings(
    libraryDependencies ++= Seq(
      "net.postgis" % "postgis-jdbc" % postgisVersion,
    ),
  )
  .dependsOn(core, postgres % "test->test")

lazy val `postgres-circe` = module("postgres-circe")
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
    ),
  )
  .dependsOn(core, postgres % "compile->compile;test->test")

lazy val mysql = module("mysql")
  .settings(
    libraryDependencies ++= Seq(
      "com.mysql" % "mysql-connector-j" % mysqlVersion,

      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    ),
  )
  .dependsOn(core % "compile->compile;test->test", zio % "test->compile")

lazy val h2 = module("h2")
  .settings(
    libraryDependencies ++= Seq(
      "com.h2database" % "h2" % h2Version,

      "dev.zio" %% "zio-interop-cats" % zioInteropCats % Test,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    )
  )
  .dependsOn(core)

lazy val `h2-circe` = module("h2-circe")
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
    )
  )
  .dependsOn(core, h2 % "compile->compile;test->test")

lazy val hikari = module("hikari")
  .settings(
    libraryDependencies ++= Seq(
      "com.zaxxer" % "HikariCP" % hikariVersion,
      "org.slf4j" % "slf4j-api" % slf4jVersion,

      "org.slf4j" % "slf4j-nop" % slf4jVersion % Test,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    )
  )
  .dependsOn(core)

lazy val refined = module("refined")
  .settings(
    libraryDependencies ++= Seq(
      "eu.timepit" %% "refined" % refinedVersion,

      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    )
  )
  .dependsOn(core, h2 % "test->test")

lazy val munit = module("munit")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion,

      "com.h2database" % "h2" % h2Version % Test,
    )
  )
  .dependsOn(core)

lazy val scalatest = module("scalatest")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % scalatestVersion,

      "com.h2database" % "h2" % h2Version % Test,
    )
  )
  .dependsOn(core)

lazy val specs2 = module("specs2")
  .settings(
    libraryDependencies ++= Seq(
      "org.specs2" %% "specs2-core" % specs2Version,

      "com.h2database" % "h2" % h2Version % Test,
    ),
    Test / compile / wartremoverErrors --= Seq(Wart.NonUnitStatements),
  )
  .dependsOn(core)

lazy val weaver = module("weaver")
  .settings(
    libraryDependencies ++= Seq(
      "com.disneystreaming" %% "weaver-cats" % weaverVersion,
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  )
  .dependsOn(core, h2 % "test->test")

lazy val zio = module("zio")
  .settings(moduleName := "zoobie")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-interop-cats" % zioInteropCats,

      "dev.zio" %% "zio-test" % zioVersion % Optional,
      "com.mysql" % "mysql-connector-j" % mysqlVersion % Optional,
      "org.postgresql" % "postgresql" % postgresVersion % Optional,
      "net.postgis" % "postgis-jdbc" % postgisVersion % Optional,

      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test" % zioVersion % IntegrationTest,
      "dev.zio" %% "zio-test-sbt" % zioVersion % IntegrationTest,
    ),
  )
  .dependsOn(core)

lazy val example = project.in(file("modules/example"))
  .settings(commonSettings)
  .settings(publish / skip := true)
  .settings(Compile / compile / wartremoverErrors := Nil)
  .settings(Test / compile / wartremoverErrors := Nil)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % fs2Version,
    )
  )
  .dependsOn(core, postgres, specs2, scalatest, hikari, h2)

lazy val bench = project.in(file("modules/bench"))
  .settings(commonSettings)
  .settings(publish / skip := true)
  .settings(Compile / compile / wartremoverErrors := Nil)
  .enablePlugins(JmhPlugin)
  .dependsOn(core, postgres)

lazy val docs = project.in(file("modules/docs"))
  .dependsOn(core, postgres, postgis, h2, hikari, munit, scalatest, specs2, weaver)
  .settings(commonSettings)
  .settings(publish / skip := true)
  .settings(Compile / compile / wartremoverErrors := Nil)
  .enablePlugins(GhpagesPlugin)
  .enablePlugins(ParadoxPlugin)
  .enablePlugins(ParadoxSitePlugin)
  .enablePlugins(MdocPlugin)
  .settings(
    scalacOptions := (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Xsource:3")
      case Some((3, _)) => Seq("-Ykind-projector")
      case _ => Seq.empty
    }),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
    ),
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
