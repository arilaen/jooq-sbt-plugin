// Updated 2018 by Marcela Rodriguez for compatibility with newer Scala versions

sbtPlugin := true
publishMavenStyle := false

version := "1.7.2-SNAPSHOT"

organization := "com.github.arilaen"

name := "jooq-sbt-plugin"

libraryDependencies += "com.floreysoft" % "jmte" % "4.0.0"

publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

licenses := Seq("Apache 2" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
homepage := Some(url("http://github.com/arilaen/jooq-sbt-plugin"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/arilaen/jooq-sbt-plugin"),
    "scm:git@github.com:arilaen/jooq-sbt-plugin.git"
  )
)
developers := List(
  Developer(
    id    = "arilaen",
    name  = "Marcela Rodriguez",
    email = "marcelar@alum.mit.edu",
    url   = url("http://github.com/arilaen")
  )
)