// Updated 2018 by Marcela Rodriguez for compatibility with newer Scala versions
// Copyright 2013 Sean Wellington
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.github.arilaen

import java.io.{File, FileWriter, PrintWriter}

import com.floreysoft.jmte._
import sbt.Keys._
import sbt._

import scala.collection.JavaConverters
import scala.sys.process._
import scala.xml.dtd.{DocType, SystemID}
import scala.xml._

object JOOQPlugin extends AutoPlugin {

  val JOOQ = config("jooq")

  val codegen = TaskKey[Unit]("codegen", "Generates code")

  object autoImport {

    val jooqOptions = SettingKey[Seq[(String, String)]]("jooq-options", "JOOQ options.")

    val jooqVersion = SettingKey[String]("jooq-version", "JOOQ version.")

    val jooqLogLevel = SettingKey[String]("jooq-log-level", "JOOQ log level.")

    val jooqOutputDirectory = SettingKey[File]("jooq-output-directory", "JOOQ output directory.")

    val jooqConfigFile = SettingKey[Option[File]]("jooq-config-file", "Specific config file to use in lieu of jooq-options")

    val jooqConfigTemplateValues = SettingKey[Option[() => Map[String, AnyRef]]]("jooq-config-template-values", "Values to be substituted into config value if both are provided")
  }

  import autoImport._

  override lazy val projectSettings:Seq[Setting[_]] = inConfig(JOOQ)(Seq(

    dependencyClasspath in JOOQ := (dependencyClasspath or (dependencyClasspath in Compile)).value,

    codegen := executeJooqCodegen(streams.value.log,
		 baseDirectory.value,
      (dependencyClasspath in JOOQ).value,
		 jooqOutputDirectory.value,
		 jooqOptions.value,
		 jooqLogLevel.value,
		 jooqConfigFile.value,
     jooqConfigTemplateValues.value)
  )) ++ Seq(

    jooqVersion := "3.10.6",

    jooqOptions := Seq(),

    jooqLogLevel := "info",

    jooqOutputDirectory := (sourceManaged in Compile)( _ / "java").value,

    jooqConfigFile := None,

    jooqConfigTemplateValues := None,

    sourceGenerators in Compile += Def.task {
      executeJooqCodegenIfOutOfDate(
        streams.value.log,
        baseDirectory.value,
        (dependencyClasspath in JOOQ).value,
        jooqOutputDirectory.value,
        jooqOptions.value,
        jooqLogLevel.value,
        jooqConfigFile.value,
        jooqConfigTemplateValues.value)
    },

    libraryDependencies ++= {
      scalaVersion.value
      Seq("org.jooq" % "jooq" % jooqVersion.value % JOOQ.name,
        "org.jooq" % "jooq" % jooqVersion.value, // also add this to the project's compile configuration
        "org.jooq" % "jooq-meta" % jooqVersion.value % JOOQ.name,
        "org.jooq" % "jooq-codegen" % jooqVersion.value % JOOQ.name,
        "org.slf4j" % "slf4j-api" % "1.7.2" % JOOQ.name,
        "org.slf4j" % "slf4j-log4j12" % "1.7.2" % JOOQ.name,
        "org.slf4j" % "jul-to-slf4j" % "1.7.2" % JOOQ.name,
        "log4j" % "log4j" % "1.2.17" % JOOQ.name)
    },

    ivyConfigurations += JOOQ

  )

  private def getOrGenerateJooqConfig(log:Logger, outputDirectory:File, options:Seq[(String,String)], jooqConfigFile:Option[File], templateValues:Option[()=>Map[String, AnyRef]]) : File = {

    (jooqConfigFile, templateValues) match {
      case (Some(template), Some(values)) =>
        val engine = new Engine
        val renderedConfig  = File.createTempFile("jooq-config", ".xml")
        renderedConfig.deleteOnExit()
        val writer = new PrintWriter(renderedConfig)
        val substitutions = JavaConverters.mapAsJavaMap(values.apply())
        writer.write(engine.transform(IO.read(template), substitutions))
        writer.close()
        renderedConfig
      case _ => jooqConfigFile.getOrElse(generateJooqConfig(log, outputDirectory, options))
    }
  }

  private def generateJooqConfig(log:Logger, outputDirectory:File, options:Seq[(String,String)]) : File = {
    val tmp = File.createTempFile("jooq-config", ".xml")
    tmp.deleteOnExit()
    val fw = new FileWriter(tmp)
    try {
      val replaced = Seq("generator.target.directory" -> outputDirectory.getAbsolutePath) ++ options.filter { kv => kv._1 != "generator.target.directory" }
      val xml = replaced.foldLeft(<configuration/>) {
	(xml, kv) => xmlify(kv._1.split("\\."), kv._2, xml)
      }
      XML.save(tmp.getAbsolutePath, xml, "UTF-8", xmlDecl = true)
    }
    finally {
      fw.close()
    }
    log.debug("Wrote JOOQ configuration to " + tmp.getAbsolutePath)
    tmp
  }

  private def xmlify(key:Seq[String], value:String, parent:Elem):Elem = {
    // convert a sequence of strings representing a XML path into a sequence
    // of nodes, and merge it in to the specified parent, reusing any nodes
    // that already exist, e.g. "value" at Seq("foo", "bar", "baz") becomes
    // <foo><bar><baz>value</baz></bar></foo>
    key match {
      case Seq(first) => Elem(null, parent.label, Null, TopScope, false, parent.child ++ Elem(null, first, Null, TopScope, minimizeEmpty = false, Text(value)):_*)
      case Seq(first, rest @ _*) =>
	val (pre, post) = parent.child.span { _.label != first }
	post match {
	  case Nil => xmlify(key, value, Elem(null, parent.label, Null, TopScope, false, parent.child ++ Elem(null, first, Null, TopScope, minimizeEmpty = false):_*))
	  case _   => Elem(null, parent.label, Null, TopScope, false, pre ++ xmlify(rest, value, Elem(null, post.head.label, Null, TopScope, false, post.head.child:_*)) ++ post.tail:_*)
	}
    }
  }

  private def generateLog4jConfig(log:Logger, logLevel:String) = {
    // shunt any messages at warn and higher to stderr, everything else to
    // stdout, thanks to http://stackoverflow.com/questions/8489551/logging-error-to-stderr-and-debug-info-to-stdout-with-log4j
    val tmp = File.createTempFile("log4j", ".xml")
    tmp.deleteOnExit()
    val configuration =
      <log4j:configuration>
    <appender name="stderr" class="org.apache.log4j.ConsoleAppender">
    <param name="threshold" value="warn" />
    <param name="target" value="System.err"/>
    <layout class="org.apache.log4j.PatternLayout">
    <param name="ConversionPattern" value="%m%n" />
    </layout>
    </appender>
    <appender name="stdout" class="org.apache.log4j.ConsoleAppender">
    <param name="threshold" value="debug" />
    <param name="target" value="System.out"/>
    <layout class="org.apache.log4j.PatternLayout">
    <param name="ConversionPattern" value="%m%n" />
    </layout>
    <filter class="org.apache.log4j.varia.LevelRangeFilter">
    <param name="LevelMin" value="debug" />
    <param name="LevelMax" value="info" />
    </filter>
    </appender>
    <root>
    <priority value={logLevel}></priority>
    <appender-ref ref="stderr" />
    <appender-ref ref="stdout" />
    </root>
    </log4j:configuration>
    XML.save(tmp.getAbsolutePath, configuration, "UTF-8", xmlDecl = true, DocType("log4j:configuration", SystemID("log4j.dtd"), Nil))
    log.debug("Wrote log4j configuration to " + tmp.getAbsolutePath)
    tmp
  }

  private def generateClasspathArgument(log:Logger, classpath:Seq[Attributed[File]], jooqConfigFile:File) = {
    val parent = jooqConfigFile.getParentFile.getAbsolutePath
    val cp = (classpath.map {
      _.data.getAbsolutePath
    } :+ parent).mkString(System.getProperty("path.separator"))
    log.debug("Classpath is " + cp)
    cp
  }

  private def executeJooqCodegenIfOutOfDate(log:Logger, baseDirectory:File, dependencyClasspath:Seq[Attributed[File]], outputDirectory:File, options:Seq[(String, String)], logLevel:String, jooqConfigFile:Option[File], templateValues:Option[()=>Map[String, AnyRef]]) = {
    // lame way of detecting whether or not code is out of date, user can always
    // run jooq:codegen manually to force regeneration
    val files = getOutputFiles(outputDirectory)
    if (files.isEmpty) executeJooqCodegen(log, baseDirectory, dependencyClasspath, outputDirectory, options, logLevel, jooqConfigFile, templateValues)
    else files
  }

  private def executeJooqCodegen(log:Logger, baseDirectory:File, dependencyClasspath:Seq[Attributed[File]], outputDirectory:File, options:Seq[(String, String)], logLevel:String, jooqConfigFile:Option[File], templateValues:Option[()=>Map[String, AnyRef]]) = {
    val jcf : File = getOrGenerateJooqConfig(log, outputDirectory, options, jooqConfigFile, templateValues)
    log.debug("Using jooq config " + jcf)
    val log4jConfig = generateLog4jConfig(log, logLevel)
    val classpathArgument = generateClasspathArgument(log, dependencyClasspath, jcf)
    val cmdLine = Seq("java", "-classpath", classpathArgument, "-Dlog4j.configuration=" + log4jConfig.toURI.toURL, "org.jooq.util.GenerationTool", "/" + jcf.getName)
    log.debug("Command line is " + cmdLine.mkString(" "))
    val rc = Process(cmdLine, baseDirectory) ! log
    rc match {
      case 0 => ;
      case x => log.error("Failed with return code: " + x)
    }
    getOutputFiles(outputDirectory)
  }

  private def getOutputFiles(outputDirectory: File) : Seq[File] = {

    val files : Seq[File] = if ((outputDirectory ** "*.java").get.isEmpty && (outputDirectory ** "*.scala").get.nonEmpty) {
      (outputDirectory ** "*.scala").get
    } else if ((outputDirectory ** "*.java").get.nonEmpty && (outputDirectory ** "*.scala").get.isEmpty) {
      (outputDirectory ** "*.java").get
    } else {
      Seq[File]()
    }

    files
  }

}
