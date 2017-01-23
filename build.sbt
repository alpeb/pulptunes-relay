organization := "com.pulptunes"

name := "pulptunes-relay"

version := "2.0.0-SNAPSHOT"

scalaVersion := "2.12.1"

libraryDependencies ++= Seq(
  evolutions,
  ws,
  "com.typesafe.play" %% "play-slick" % "2.0.0",
  "com.typesafe.play" %% "play-slick-evolutions" % "2.0.0",
  "mysql" % "mysql-connector-java" % "5.1.36",
  "org.typelevel" %% "cats" % "0.4.1"
)

sources in (Compile, doc) := Seq.empty

publishArtifact in (Compile, packageDoc) := false

lazy val root = (project in file(".")).enablePlugins(PlayScala)
