package com.karumi.shot.compose

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.nhaarman.mockitokotlin2.whenever
import junit.framework.TestCase.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.io.File

class ScreenshotSaverTest {
    companion object {
        private val anyScreenshotMetadata: ScreenshotMetadata = ScreenshotMetadata("testName")
        private val anyOtherScreenshotMetadata: ScreenshotMetadata = ScreenshotMetadata("test2Name")
    }

    @get:Rule
    var permissionRule: GrantPermissionRule =
            GrantPermissionRule.grant(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE)

    private lateinit var saver: ScreenshotSaver

    @Mock
    private lateinit var screenshotToSave: ScreenshotToSave

    @Mock
    private lateinit var otherScreenshotToSave: ScreenshotToSave

    @Mock
    private lateinit var nodeGenerator: SemanticsNodeBitmapGenerator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(nodeGenerator.generateBitmap(screenshotToSave)).thenReturn(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888, true))
        whenever(nodeGenerator.generateBitmap(otherScreenshotToSave)).thenReturn(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888, true))
        whenever(screenshotToSave.data).thenReturn(anyScreenshotMetadata)
        whenever(otherScreenshotToSave.data).thenReturn(anyOtherScreenshotMetadata)
        val context = ApplicationProvider.getApplicationContext<Context>()
        saver = ScreenshotSaver(context.packageName, nodeGenerator)
        clearSdCardFiles()
    }

    @After
    fun tearDown() {
        clearSdCardFiles()
    }

    @Test
    fun savesTheBitmapObtainedFromTheNodeUsingTheScreenshotMetadata() {
        saver.saveScreenshot(screenshotToSave)

        val file = File("/sdcard/screenshots/com.karumi.shot.test/screenshots-default/${anyScreenshotMetadata.name}.png")
        assertTrue(file.exists())
    }

    @Test
    fun savesAllTheBitmapsObtainedFromTheNodeUsingTheScreenshotMetadata() {
        val testsMetadata = listOf(screenshotToSave, otherScreenshotToSave)

        testsMetadata.forEach { screenshotToSave ->
            saver.saveScreenshot(screenshotToSave)
        }

        assertTrue(testsMetadata.all {
            File("/sdcard/screenshots/com.karumi.shot.test/screenshots-default/${it.data.name}.png").exists()
        })
    }

    private fun clearSdCardFiles() = File("/sdcard/screenshots/com.karumi.shot.test/").deleteRecursively()
}