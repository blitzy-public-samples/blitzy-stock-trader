ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.11"

enablePlugins(GatlingPlugin)

val ScalaCheckVersion = "1.14.1"
val gatlingVersion = "3.9.3"

libraryDependencies ++= Seq(
  "org.scalacheck" %% "scalacheck" % ScalaCheckVersion,
  "io.gatling.highcharts" % "gatling-charts-highcharts" % gatlingVersion % "it, test",
  "io.gatling"            % "gatling-test-framework"    % gatlingVersion % "it, test"

)

lazy val root = (project in file("."))
  .settings(
    name := "loopr",
    idePackagePrefix := Some("com.kyndryl.cjot")
  )
