package com.karumi.shot.reports

import com.karumi.shot.base64.Base64Encoder
import com.karumi.shot.domain._
import com.karumi.shot.ui.Console

class ConsoleReporter(console: Console) {

  def showBase64Error(comparision: ScreenshotsComparisionResult,
                      outputFolder: String): Unit = {
    console.show(
      "🤖  The option printBase64 is enabled. In order to see the generated diff images, run the following commands in your terminal:")
    console.lineBreak()
    comparision.screenshots.foreach(screenshot => {
      showScreenshotBase64Error(outputFolder, screenshot)
    })
  }

  def showErrors(comparision: ScreenshotsComparisionResult): Unit = {
    console.showError(
      "❌  Hummmm...the following screenshot tests are broken:\n")
    comparision.errors.foreach { error =>
      error match {
        case ScreenshotNotFound(screenshot) =>
          console.showError(
            "   🔎  Recorded screenshot not found for test: " + screenshot.name)
        case DifferentScreenshots(screenshot) =>
          console.showError(
            "   🤔  The application UI has been modified for test: " + screenshot.name)
          console.showError(
            "            💾  You can find the original screenshot here: " + screenshot.recordedScreenshotPath)
          console.showError(
            "            🆕  You can find the new recorded screenshot here: " + screenshot.temporalScreenshotPath)
        case DifferentImageDimensions(screenshot,
                                      originalDimension,
                                      newDimension) => {
          console.showError(
            "   📱  The size of the screenshot taken has changed for test: " + screenshot.name)
          console.showError(
            "            💾  Original screenshot dimension: " + originalDimension + ". You can find the original screenshot here: " + screenshot.recordedScreenshotPath)
          console.showError(
            "            🆕  New recorded screenshot dimension: " + newDimension + ". You can find the new recorded screenshot here: " + screenshot.temporalScreenshotPath)
        }

        case _ =>
          console.showError(
            "   😞  Ups! Something went wrong while comparing your screenshots but we couldn't identify the cause. If you think you've found a bug, please open an issue at https://github.com/karumi/shot.")
      }
      console.lineBreak()
    }
  }

  private def showScreenshotBase64Error(outputFolder: String,
                                        screenshot: Screenshot): Unit = {
    val encodedDiff: String =
      Base64Encoder.base64FromFile(
        screenshot.getDiffScreenshotPath(outputFolder))

    console.showError(s"Test ${screenshot.fileName}")
    console.lineBreak()
    console.show(
      s"\t> echo '$encodedDiff' | base64 -D > ${screenshot.fileName}")
    console.lineBreak()
  }
}
