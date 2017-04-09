packageArchetype.java_application

organization := "com.msilb"

name := "scalanda-trading-sample"

version := "1.0"

scalaVersion := "2.11.7"

scalacOptions := Seq("-unchecked", "-feature", "-deprecation", "-encoding", "utf8")

resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies += "com.msilb" %% "scalanda" % "0.3.7"
