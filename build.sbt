// format: off
import FreeGen2.*

val catsVersion = "2.13.0"
val catsEffectVersion = "3.6.1"
val circeVersion = "0.14.12"
val fs2Version = "3.12.0"
val h2Version = "2.3.232"
val hikariVersion = "6.3.0"
val magnoliaVersion = "1.1.10"
val munitVersion = "1.1.0"
val mysqlVersion = "9.2.0"
val openTelemetryVersion = "1.49.0"
val postgisVersion = "2024.1.0"
val postgresVersion = "42.7.5"
val scalatestVersion = "3.2.19"
val shapelessVersion = "2.3.12"
val slf4jVersion = "2.0.17"
val weaverVersion = "0.8.4"
val zioInteropCats = "23.1.0.5"
val zioVersion = "2.1.17"

val Scala213 = "2.13.16"
val Scala3 = "3.3.5"

inThisBuild(Seq(
  organization := "io.github.guymers",
  homepage := Some(url("https://github.com/guymers/foobie")),
  licenses := Seq(License.MIT),
  developers := List(
    Developer("guymers", "Sam Guymer", "@guymers", url("https://github.com/guymers")),
  ),
  scmInfo := Some(ScmInfo(url("https://github.com/guymers/foobie"), "git@github.com:guymers/foobie.git")),

  sonatypeCredentialHost := "s01.oss.sonatype.org",
  sonatypeRepository := "https://s01.oss.sonatype.org/service/local",
))

lazy val commonSettings = Seq(
  scalaVersion := Scala213,
  crossScalaVersions := Seq(Scala213, Scala3),
  versionScheme := Some("pvp"),

  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-release", "11",
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
      "-Wconf:cat=scala3-migration:silent", // these warnings dont stop compiling under Scala 3
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
    case _ => Seq(
      "-Wnonunit-statement",
      "-Wunused:all",
      "-Wvalue-discard"
    )
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
    case Some((2, _)) => Seq(compilerPlugin("org.typelevel" % "kind-projector" % "0.13.3" cross CrossVersion.full))
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

Global / excludeLintKeys += mimaPreviousArtifacts

lazy val noPublishSettings = Seq(
  publish / skip := true,
  mimaPreviousArtifacts := Set.empty,
)

lazy val runningInIntelliJ = System.getProperty("idea.managed", "false").toBoolean

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
  .settings(
    if (runningInIntelliJ) Seq(
      Test / unmanagedSourceDirectories += baseDirectory.value / "src" / "it" / "scala",
    ) else Seq.empty
  )

def moduleIT(name: String) = Project(s"$name-it", file(s"modules/$name-it"))
  .settings(moduleName := s"foobie-$name-it")
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    Test / fork := true,
    Test / javaOptions += "-Xmx1000m",
  )
  .settings(
    // intellij complains about shared content roots, so it gets the source appended in `module`
    if (runningInIntelliJ) Seq.empty else Seq(
      Compile / javaSource := baseDirectory.value / ".." / name / "src" / "main-it" / "java",
      Compile / scalaSource := baseDirectory.value / ".." / name / "src" / "main-it" / "scala",
      Test / javaSource := baseDirectory.value / ".." / name / "src" / "it" / "java",
      Test / scalaSource := baseDirectory.value / ".." / name / "src" / "it" / "scala",
    )
  )
  .disablePlugins(MimaPlugin)

lazy val foobie = project.in(file("."))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(
    addCommandAlias("testUnit", ";modules/test"),
    addCommandAlias("testIntegration", ";integrationTests/test"),
  )
  .aggregate(
    modules, integrationTests,
    example, bench, docs,
  )

lazy val modules = project.in(file("project/.root"))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .aggregate(
    free, core,
    h2, `h2-circe`,
    mysql,
    postgres, `postgres-circe`, postgis,
    hikari,
    munit, scalatest, weaver,
    zio,
  )
  .disablePlugins(MimaPlugin)

lazy val integrationTests = project.in(file("project/.root-integration"))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .aggregate(`zio-it`)

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
    freeGen2Classes := List[Class[?]](
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
      "co.fs2" %% "fs2-core" % fs2Version,
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
    freeGen2Classes := List[Class[?]](
      classOf[org.postgresql.copy.CopyIn],
      classOf[org.postgresql.copy.CopyManager],
      classOf[org.postgresql.copy.CopyOut],
      classOf[org.postgresql.largeobject.LargeObject],
      classOf[org.postgresql.largeobject.LargeObjectManager],
      classOf[org.postgresql.PGConnection]
    ),
    freeGen2Renames ++= Map(
      classOf[org.postgresql.copy.CopyDual] -> "PGCopyDual",
      classOf[org.postgresql.copy.CopyIn] -> "PGCopyIn",
      classOf[org.postgresql.copy.CopyManager] -> "PGCopyManager",
      classOf[org.postgresql.copy.CopyOut] -> "PGCopyOut",
    ),
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
      "io.opentelemetry" % "opentelemetry-api" % openTelemetryVersion % Optional,

      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    ),
  )
  .dependsOn(core)

lazy val `zio-it` = moduleIT("zio")
  .settings(moduleName := "zoobie-it")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "io.opentelemetry" % "opentelemetry-api" % openTelemetryVersion % Test,
    ),
  )
  .dependsOn(zio, postgres)

lazy val example = project.in(file("modules/example"))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(Compile / compile / wartremoverErrors := Nil)
  .settings(Test / compile / wartremoverErrors := Nil)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % fs2Version,
    )
  )
  .dependsOn(core, postgres, scalatest, hikari, h2)

lazy val bench = project.in(file("modules/bench"))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(Compile / compile / wartremoverErrors := Nil)
  .settings(mimaPreviousArtifacts := Set.empty)
  .enablePlugins(JmhPlugin)
  .dependsOn(core, postgres)

lazy val docs = project.in(file("modules/docs"))
  .dependsOn(core, postgres, postgis, h2, hikari, munit, scalatest, weaver)
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(Compile / compile / wartremoverErrors := Nil)
  .enablePlugins(GhpagesPlugin)
  .enablePlugins(ParadoxPlugin)
  .enablePlugins(ParadoxSitePlugin)
  .enablePlugins(MdocPlugin)
  .settings(
    scalacOptions := (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Xsource:3", "-Wconf:cat=scala3-migration:silent")
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
