package com.karumi.shot

import com.karumi.shot.android.Adb
import com.karumi.shot.domain.Config
import com.karumi.shot.screenshots.{ScreenshotsComparator, ScreenshotsSaver}
import com.karumi.shot.tasks.{DownloadScreenshotsTask, ExecuteScreenshotTests, RemoveScreenshotsTask}
import com.karumi.shot.ui.Console
import org.gradle.api.artifacts.{Dependency, DependencyResolutionListener, ResolvableDependencies}
import org.gradle.api.{Plugin, Project}
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.build.BuildEnvironment

class ShotPlugin extends Plugin[Project] {

  def GRADLE_MIN_MAJOR = 3
  def GRADLE_MIN_MINOR = 4

  private lazy val shot: Shot =
    new Shot(new Adb,
      new Files,
      new ScreenshotsComparator,
      new ScreenshotsSaver,
      new Console)

  override def apply(project: Project): Unit = {
    configureAdb(project)
    addExtensions(project)
    addAndroidTestDependency(project)
    project.afterEvaluate { project => {
      addTasks(project)
    }
    }
  }

  private def configureAdb(project: Project): Unit = {
    val adbPath = AdbPathExtractor.extractPath(project)
    shot.configureAdbPath(adbPath)
  }


  private def addTasks(project: Project): Unit = {
    project.getTasks
      .create(RemoveScreenshotsTask.name, classOf[RemoveScreenshotsTask])
    val pullScreenshots = project.getTasks
      .create(DownloadScreenshotsTask.name, classOf[DownloadScreenshotsTask])
    val executeScreenshot = project.getTasks
      .create(ExecuteScreenshotTests.name, classOf[ExecuteScreenshotTests])
    executeScreenshot.dependsOn(RemoveScreenshotsTask.name)
    val extension =
      project.getExtensions.getByType[ShotExtension](classOf[ShotExtension])
    val instrumentationTask = extension.getOptionInstrumentationTestTask
    val packageTask = extension.getOptionPackageTestApkTask
    (instrumentationTask, packageTask) match {
      case (Some(instTask), Some(packTask)) =>
        executeScreenshot.dependsOn(instTask)
        pullScreenshots.dependsOn(packTask)
      case _ =>
        executeScreenshot.dependsOn(Config.defaultInstrumentationTestTask)
        pullScreenshots.dependsOn(Config.defaultPackageTestApkTask)
    }

    executeScreenshot.dependsOn(DownloadScreenshotsTask.name)
  }

  private def addExtensions(project: Project): Unit = {
    val name = ShotExtension.name
    project.getExtensions.add(name, new ShotExtension())
  }


  private def isLegacyGradleVersion(gradleVersion: String): Boolean = {
    val versionNumbers = gradleVersion.split('.')
    var major = 0
    var minor = 0

    if (versionNumbers.length > 0) {
      major = versionNumbers(0).toInt
    }
    if (versionNumbers.length > 1) {
      minor = versionNumbers(1).toInt
    }

    (major, minor) match {
      case (major, _) if major > GRADLE_MIN_MAJOR => false
      case (major, minor) if major == GRADLE_MIN_MAJOR && minor >= GRADLE_MIN_MINOR => false
      case _ => true
    }

  }

  private def androidDependencyMode(project: Project): String = {
    val connection = GradleConnector.newConnector.forProjectDirectory(project.getProjectDir).connect

    try {
      val gradleVersion = connection.getModel(classOf[BuildEnvironment]).getGradle.getGradleVersion

      if (isLegacyGradleVersion(gradleVersion)) {
        return Config.androidDependencyModeLegacy
      }

    } finally connection.close()

    Config.androidDependencyMode
  }

  private def addAndroidTestDependency(project: Project): Unit = {

    project.getGradle.addListener(new DependencyResolutionListener() {


      override def beforeResolve(resolvableDependencies: ResolvableDependencies): Unit = {
        var facebookDependencyHasBeenAdded = false

        project.getConfigurations.forEach(
          config => {
            facebookDependencyHasBeenAdded |= config.getAllDependencies.toArray(new Array[Dependency](0)).exists(
              dependency =>
                Config.androidDependencyGroup == dependency.getGroup
                  && Config.androidDependencyName == dependency.getName)
          })


        if (!facebookDependencyHasBeenAdded) {
          val dependencyMode = androidDependencyMode(project)
          val dependencyName = Config.androidDependency
          val dependenciesHandler = project.getDependencies

          val dependencyToAdd = dependenciesHandler.create(dependencyName)
          Option(project.getPlugins.findPlugin(Config.androidPluginName))
            .map(_ => project.getDependencies.add(dependencyMode, dependencyToAdd))
          project.getGradle.removeListener(this)
        }
      }

      override def afterResolve(resolvableDependencies: ResolvableDependencies): Unit = {}
    })
  }
}
