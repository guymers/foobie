// format: off
val catsVersion = "2.13.0"
val catsEffectVersion = "3.6.3"
val circeVersion = "0.14.14"
val h2Version = "2.3.232"
val magnoliaVersion = "1.1.10"
val mysqlVersion = "9.4.0"
val openTelemetryVersion = "1.53.0"
val postgisVersion = "2025.1.1"
val postgresVersion = "42.7.7"
val shapelessVersion = "2.3.13"
val slf4jVersion = "2.0.17"
val weaverVersion = "0.8.4"
val zioInteropCats = "23.1.0.5"
val zioVersion = "2.1.19"

val Scala213 = "2.13.16"
val Scala3 = "3.3.6"

inThisBuild(Seq(
  organization := "io.github.guymers",
  homepage := Some(url("https://github.com/guymers/foobie")),
  licenses := Seq(License.MIT),
  developers := List(
    Developer("guymers", "Sam Guymer", "@guymers", url("https://github.com/guymers")),
  ),
  scmInfo := Some(ScmInfo(url("https://github.com/guymers/foobie"), "git@github.com:guymers/foobie.git")),
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

def moduleIT(name: String) = Project(s"$name-it", file(s"modules/$name-it"))
  .settings(moduleName := s"foobie-$name-it")
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    Test / fork := true,
    Test / javaOptions += "-Xmx1000m",
  )
  .settings(Seq(
    Compile / javaSource := baseDirectory.value / ".." / name / "src" / "main-it" / "java",
    Compile / scalaSource := baseDirectory.value / ".." / name / "src" / "main-it" / "scala",
    Test / javaSource := baseDirectory.value / ".." / name / "src" / "it" / "java",
    Test / scalaSource := baseDirectory.value / ".." / name / "src" / "it" / "scala",
  ))
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
    example, bench, /*docs,*/
  )

lazy val modules = project.in(file("project/.root"))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .aggregate(
    core, macros,
    h2, `h2-circe`,
    mysql,
    postgres, `postgres-circe`, postgis,
    weaver,
    zio,
  )
  .disablePlugins(MimaPlugin)

lazy val integrationTests = project.in(file("project/.root-integration"))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .aggregate(`zio-it`)

lazy val macros = module("macros")
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
    ),
    libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(scalaOrganization.value % "scala-reflect" % scalaVersion.value)
      case _ => Seq.empty
    }),
  )

lazy val core = module("core")
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-free" % catsVersion,
      "org.typelevel" %% "cats-effect-kernel" % catsEffectVersion % Optional,

      "com.h2database" % "h2" % h2Version % Test,
      "dev.zio" %% "zio-interop-cats" % zioInteropCats % Test,
      "dev.zio" %% "zio-managed" % zioVersion % Test,
      "org.typelevel" %% "cats-effect" % catsEffectVersion % Test,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    ),
    libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(
        "com.chuusai" %% "shapeless" % shapelessVersion % Test,
        "com.softwaremill.magnolia1_2" %% "magnolia" % magnoliaVersion,
      )
      case _ => Seq.empty
    }),
  )
  .dependsOn(macros)

lazy val postgres = module("postgres")
  .settings(
    libraryDependencies ++= Seq(
      "org.postgresql" % "postgresql" % postgresVersion,

      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    ),
    libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("com.chuusai" %% "shapeless" % shapelessVersion)
      case _ => Seq.empty
    }),
  )
  .dependsOn(core, core % "test->test", zio % "test->compile")

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
  .dependsOn(core, core % "test->test", zio % "test->compile")

lazy val h2 = module("h2")
  .settings(
    libraryDependencies ++= Seq(
      "com.h2database" % "h2" % h2Version,
      "org.typelevel" %% "cats-effect-kernel" % catsEffectVersion,

      "dev.zio" %% "zio-interop-cats" % zioInteropCats % Test,
      "dev.zio" %% "zio-managed" % zioVersion % Test,
      "org.typelevel" %% "cats-effect" % catsEffectVersion % Test,
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
      "dev.zio" %% "zio-interop-tracer" % zioInteropCats,

      "dev.zio" %% "zio-test" % zioVersion % Optional,
      "com.mysql" % "mysql-connector-j" % mysqlVersion % Optional,
      "org.postgresql" % "postgresql" % postgresVersion % Optional,
      "net.postgis" % "postgis-jdbc" % postgisVersion % Optional,
      "io.opentelemetry" % "opentelemetry-api" % openTelemetryVersion % Optional,

      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "org.typelevel" %% "cats-laws" % catsVersion % Test,
      "org.typelevel" %% "discipline-munit" % "2.0.0" % Test,
    ),
  )
  .dependsOn(core, core % "test->test")

lazy val `zio-it` = moduleIT("zio")
  .settings(moduleName := "zoobie-it")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "io.opentelemetry" % "opentelemetry-api" % openTelemetryVersion % Test,
    ),
  )
  .dependsOn(zio, postgres, postgres % "test->test")

lazy val example = project.in(file("modules/example"))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
    )
  )
  .settings(Compile / compile / wartremoverErrors := Nil)
  .settings(Test / compile / wartremoverErrors := Nil)
  .dependsOn(core, postgres, h2)

lazy val bench = project.in(file("modules/bench"))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
    )
  )
  .settings(Compile / compile / wartremoverErrors := Nil)
  .settings(mimaPreviousArtifacts := Set.empty)
  .enablePlugins(JmhPlugin)
  .dependsOn(core, postgres)

lazy val docs = project.in(file("modules/docs"))
  .dependsOn(core, postgres, postgis, h2, weaver)
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
      "shapelessVersion"         -> shapelessVersion,
      "h2Version"                -> h2Version,
      "postgresVersion"          -> postgresVersion,
      "scalaVersion"             -> scalaVersion.value,
    ),

    mdocIn := baseDirectory.value / "src" / "main" / "mdoc",
    Compile / paradox / sourceDirectory := mdocOut.value,
    makeSite := makeSite.dependsOn(mdoc.toTask("")).value,
  )
