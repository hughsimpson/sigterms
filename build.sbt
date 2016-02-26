name := "sigterms"

organization := "org.silasmariner"

version := "0.1.0"

scalaVersion := "2.11.7"

libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value

libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value

licenses += ("BSD New", url("http://choosealicense.com/licenses/bsd-3-clause/"))

publishMavenStyle := true

resolvers += Resolver.jcenterRepo