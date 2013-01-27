/**
 * Copyright (c) 2013 Orr Sella
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.orrsella.sbtsublime

import java.io.File
import sbt._
import sbt.IO._
import sbt.Keys._

object SbtSublimePlugin extends Plugin {
  lazy val sublimeExternalSourceDirectoryName = SettingKey[String](
    "sublime-external-source-directory-name", "The directory name for external sources")

  lazy val sublimeExternalSourceDirectoryParent = SettingKey[File](
    "sublime-external-source-directory-parent", "Parent dir of the external sources dir")

  lazy val sublimeExternalSourceDirectory = SettingKey[File](
    "sublime-external-source-directory", "Directory for external sources")

  lazy val sublimeTransitive = SettingKey[Boolean](
      "sublime-transitive",
      "Indicate whether to add sources for all dependencies transitively (including libraries that dependencies require)")

  lazy val sublimeProjectName = SettingKey[String]("sublime-project-name", "The name of the sublime project file")
  lazy val sublimeProjectDir = SettingKey[File]("sublime-project-dir", "The parent directory for the sublime project file")
  lazy val sublimeProjectFile = SettingKey[File]("sublime-project-file", "The sublime project file")

  override lazy val projectSettings = super.projectSettings ++ Seq(
    commands += statsCommand,
    sublimeExternalSourceDirectoryName := "External Libraries",
    sublimeExternalSourceDirectoryParent <<= target,
    sublimeExternalSourceDirectory <<= (sublimeExternalSourceDirectoryName, sublimeExternalSourceDirectoryParent) {
      (n, p) => new File(p, n)
    },
    sublimeTransitive := false,
    sublimeProjectName <<= (name) { name => name},
    sublimeProjectDir <<= baseDirectory,
    sublimeProjectFile <<= (sublimeProjectName, sublimeProjectDir) { (n, p) => new File(p, n + ".sublime-project") },
    cleanFiles <+= (sublimeExternalSourceDirectory) { d => d })

  def statsCommand = Command.command("gen-sublime") { state => doCommand(state) }

  def doCommand(state: State): State = {
    val log = state.log
    val extracted: Extracted = Project.extract(state)
    val structure = extracted.structure
    val currentRef = extracted.currentRef
    val projectRefs = structure.allProjectRefs
    // val rootDirectory = structure.root

    lazy val directory = sublimeExternalSourceDirectory in currentRef get structure.data get
    lazy val transitive = sublimeTransitive in currentRef get structure.data get
    lazy val projectFile = sublimeProjectFile in currentRef get structure.data get
    lazy val scalaVersion = Keys.scalaVersion in currentRef get structure.data get
    lazy val rootDirectory = Keys.baseDirectory in currentRef get structure.data get

    log.info("Generating Sublime project for root directory: " + rootDirectory)
    log.info("Getting dependency libraries sources transitively: " + transitive)
    log.info("Saving external sources to: " + directory)

    val dependencies: Seq[ModuleID] = projectRefs.flatMap {
      projectRef => Keys.libraryDependencies in projectRef get structure.data
    }.flatten.distinct

    val dependencyNames: Seq[String] = dependencies.map(d => d.name)

    val dependencyArtifacts: Seq[(Artifact, File)] = projectRefs.flatMap {
      projectRef => EvaluateTask(structure, Keys.updateClassifiers, state, projectRef) match {
        case Some((state, Value(report))) => report.configurations.flatMap(_.modules.flatMap(_.artifacts))
        case _ => Seq()
      }
    }.distinct

    // cleanup
    delete(directory)
    createDirectory(directory)

    // filter artifacts for transitive and sources only
    val filteredArtifacts =
      if (transitive) dependencyArtifacts
      // else dependencyArtifacts.filter(pair => dependencyNames.contains(pair._1.name.replace("_" + scalaVersion, "")))
      else dependencyArtifacts.filter(pair => dependencyNames.exists(name => pair._1.name.startsWith(name)))

    val sourceJars = filteredArtifacts.filter(pair => pair._1.`type` == Artifact.SourceType).map(_._2)
    log.info("Adding the following to external libraries:")
    sourceJars.foreach(jar => log.info("  " + jar.getName))

    // extract jars and make read-only
    log.info("Extracting jars to external sources directory")
    sourceJars.foreach(jar => unzip(jar, new File(directory, jar.getName.replace("-sources.jar", ""))))
    log.info("Marking all files in sources directory as read-only")
    setDirectoryTreeReadOnly(directory)

    // create project file
    val srcDir = new SublimeProjectFolder(directory.getPath)
    val projectFolder = new SublimeProjectFolder(rootDirectory.getPath)
    val project =
      if (projectFile.exists) {
        val existingProject = SublimeProject.fromFile(projectFile)
        if (existingProject.folders.exists(f => f.path == directory.getPath)) existingProject
        else new SublimeProject(existingProject.folders :+ srcDir, existingProject.settings, existingProject.build_systems)
      } else new SublimeProject(Seq(projectFolder, srcDir))

    log.info("Writing project to file: " + projectFile)
    project.toFile(projectFile)

    // return unchanged state
    state
  }

  private def setDirectoryTreeReadOnly(dir: File): Unit = {
    for (file <- dir.listFiles) {
      if (file.isDirectory) setDirectoryTreeReadOnly(file)
      else file.setReadOnly
    }
  }
}