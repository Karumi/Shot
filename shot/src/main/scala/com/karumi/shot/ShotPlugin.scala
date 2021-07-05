package com.karumi.shot

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.{AppExtension, LibraryExtension}
import com.android.builder.model.{BuildType, ProductFlavor}
import com.karumi.shot.android.Adb
import com.karumi.shot.base64.Base64Encoder
import com.karumi.shot.domain.Config
import com.karumi.shot.exceptions.ShotException
import com.karumi.shot.reports.{ConsoleReporter, ExecutionReporter}
import com.karumi.shot.screenshots.{
  ScreenshotsComparator,
  ScreenshotsDiffGenerator,
  ScreenshotsSaver
}
import com.karumi.shot.system.EnvVars
import com.karumi.shot.tasks.{
  DownloadScreenshotsTask,
  ExecuteScreenshotTests,
  ExecuteScreenshotTestsForEveryFlavor,
  RemoveScreenshotsTask
}
import com.karumi.shot.ui.Console
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.{Plugin, Project}

import scala.util.Try
class ShotPlugin extends Plugin[Project] {

  private val console = new Console
  private lazy val shot: Shot =
    new Shot(
      new Adb,
      new Files,
      new ScreenshotsComparator,
      new ScreenshotsDiffGenerator(new Base64Encoder),
      new ScreenshotsSaver,
      console,
      new ExecutionReporter,
      new ConsoleReporter(console),
      new EnvVars()
    )

  override def apply(project: Project): Unit = {
    addExtensions(project)
    addAndroidTestDependency(project)
    project.afterEvaluate { project =>
      {
        configureAdb(project)
        addTasks(project)
      }
    }
  }

  private def configureAdb(project: Project): Unit = {
    val adbPath = AdbPathExtractor.extractPath(project)
    shot.configureAdbPath(adbPath)
  }

  private def addTasks(project: Project): Unit = {
    if (isAnAndroidProject(project)) {
      addTasksToAppModule(project)
    } else if (isAnAndroidLibrary(project)) {
      addTasksToLibraryModule(project)
    }
  }

  private def addTasksToLibraryModule(project: Project) = {
    val libraryExtension =
      getAndroidLibraryExtension(project)
    val baseTask =
      project.getTasks
        .register(Config.defaultTaskName, classOf[ExecuteScreenshotTestsForEveryFlavor])
    libraryExtension.getLibraryVariants.all { variant =>
      addTaskToVariant(project, baseTask, variant)
    }
  }

  private def addTasksToAppModule(project: Project) = {
    val appExtension =
      getAndroidAppExtension(project)
    val baseTask =
      project.getTasks
        .register(Config.defaultTaskName, classOf[ExecuteScreenshotTestsForEveryFlavor])
    appExtension.getApplicationVariants.all { variant =>
      addTaskToVariant(project, baseTask, variant)
    }
  }

  private def addTaskToVariant(
      project: Project,
      baseTask: TaskProvider[ExecuteScreenshotTestsForEveryFlavor],
      variant: BaseVariant
  ) = {
    val flavor = variant.getMergedFlavor
    checkIfApplicationIdIsConfigured(project, flavor)
    val completeAppId = composeCompleteAppId(variant)
    val appTestId =
      Option(flavor.getTestApplicationId).getOrElse(completeAppId)
    addTasksFor(project, variant.getFlavorName, variant.getBuildType, appTestId, baseTask)
  }

  private def composeCompleteAppId(variant: BaseVariant) =
    variant.getApplicationId + ".test"

  private def checkIfApplicationIdIsConfigured(project: Project, flavor: ProductFlavor) =
    if (isAnAndroidLibrary(project) && flavor.getTestApplicationId == null) {
      throw ShotException(
        "Your Android library needs to be configured using an testApplicationId in your build.gradle defaultConfig block."
      )
    }

  private def addExtensions(project: Project): Unit = {
    val name = ShotExtension.name
    project.getExtensions.add(name, new ShotExtension())
  }

  private def addTasksFor(
      project: Project,
      flavor: String,
      buildType: BuildType,
      appId: String,
      baseTask: TaskProvider[ExecuteScreenshotTestsForEveryFlavor]
  ): Unit = {
    val extension =
      project.getExtensions.getByType[ShotExtension](classOf[ShotExtension])
    val instrumentationTaskName = if (extension.useComposer) {
      Config.composerInstrumentationTestTask(flavor, buildType.getName)
    } else {
      Config.defaultInstrumentationTestTask(flavor, buildType.getName)
    }
    val tasks = project.getTasks
    // Some projects configure different build types and only one of them is allowed to run instrumentation tasks
    // Based on this, we need to first check if the instrumentation task is available or not. This let us use Shot
    // for different build types even if it is not the default one
    val instrumentationTaskProvider =
      try {
        tasks.named(instrumentationTaskName)
      } catch {
        case e: Throwable => return
      }

    val removeScreenshotsAfterExecution = tasks
      .register(
        RemoveScreenshotsTask.name(flavor, buildType, beforeExecution = false),
        classOf[RemoveScreenshotsTask]
      )
    val removeScreenshotsBeforeExecution = tasks
      .register(
        RemoveScreenshotsTask.name(flavor, buildType, beforeExecution = true),
        classOf[RemoveScreenshotsTask]
      )

    removeScreenshotsAfterExecution.configure { task =>
      task.setDescription(RemoveScreenshotsTask.description(flavor, buildType))
      task.flavor = flavor
      task.buildType = buildType
      task.appId = appId
    }
    removeScreenshotsBeforeExecution.configure { task =>
      task.setDescription(RemoveScreenshotsTask.description(flavor, buildType))
      task.flavor = flavor
      task.buildType = buildType
      task.appId = appId
    }

    val downloadScreenshots = tasks
      .register(DownloadScreenshotsTask.name(flavor, buildType), classOf[DownloadScreenshotsTask])
    downloadScreenshots.configure { task =>
      task.setDescription(DownloadScreenshotsTask.description(flavor, buildType))
      task.flavor = flavor
      task.buildType = buildType
      task.appId = appId
    }
    val executeScreenshot = tasks
      .register(ExecuteScreenshotTests.name(flavor, buildType), classOf[ExecuteScreenshotTests])
    executeScreenshot.configure { task =>
      task.setDescription(ExecuteScreenshotTests.description(flavor, buildType))
      task.flavor = flavor
      task.buildType = buildType
      task.appId = appId
    }

    if (runInstrumentation(project, extension)) {
      executeScreenshot.configure { task =>
        task.dependsOn(instrumentationTaskProvider)
        task.dependsOn(downloadScreenshots)
        task.dependsOn(removeScreenshotsAfterExecution)
      }

      downloadScreenshots.configure { task =>
        task.mustRunAfter(instrumentationTaskProvider)
      }
      instrumentationTaskProvider.configure { task =>
        task.dependsOn(removeScreenshotsBeforeExecution)
      }
      removeScreenshotsAfterExecution.configure { task =>
        task.mustRunAfter(downloadScreenshots)
      }
    }
    baseTask.configure { task =>
      task.dependsOn(executeScreenshot)
    }
  }

  private def addAndroidTestDependency(project: Project): Unit = {
    val configs = project.getConfigurations
    val shotConfig = configs
      .create(Config.shotConfiguration)
    shotConfig.defaultDependencies((dependencies: DependencySet) => {
      val dependencyName  = Config.androidDependency
      val dependencyToAdd = project.getDependencies.create(dependencyName)
      dependencies.add(dependencyToAdd)
    })
    configs
      .named(Config.androidDependencyMode)
      .configure { config =>
        config.extendsFrom(shotConfig)
      }
  }

  private def runInstrumentation(project: Project, extension: ShotExtension): Boolean = {
    val property = project.findProperty("runInstrumentation").asInstanceOf[String]

    if (property != null) {
      if (Try(property.toBoolean).getOrElse(null) == null) {
        throw ShotException("runInstrumentation value must be true|false")
      }

      return property.toBoolean
    }

    extension.runInstrumentation
  }

  private def isAnAndroidLibrary(project: Project): Boolean =
    try {
      getAndroidLibraryExtension(project)
      true
    } catch {
      case _: Throwable => false
    }

  private def isAnAndroidProject(project: Project): Boolean =
    try {
      getAndroidAppExtension(project)
      true
    } catch {
      case _: Throwable => false
    }

  private def getAndroidLibraryExtension(project: Project) = {
    project.getExtensions
      .getByType[LibraryExtension](classOf[LibraryExtension])
  }

  private def getAndroidAppExtension(project: Project) = {
    project.getExtensions.getByType[AppExtension](classOf[AppExtension])
  }
}
