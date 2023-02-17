// Required for the freegen definition for postgres in ../build.sbt
val postgresVersion = "42.5.4"
libraryDependencies += "org.postgresql" % "postgresql" % postgresVersion

libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always,
)
