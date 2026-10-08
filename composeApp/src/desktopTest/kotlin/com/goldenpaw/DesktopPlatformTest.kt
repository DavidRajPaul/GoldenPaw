package com.goldenpaw

import com.goldenpaw.platform.DesktopNotifier
import com.goldenpaw.platform.DesktopPageImageProcessor
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlatformTest {
    private val dir: File = kotlin.io.path.createTempDirectory("gp-desktop").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun image(w: Int, h: Int): String {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(200, 120, 40)
        g.fillRect(0, 0, w, h)
        g.dispose()
        return File(dir, "src.jpg").also { ImageIO.write(img, "jpg", it) }.absolutePath
    }

    @Test
    fun rotatingAQuarterTurnSwapsWidthAndHeight() = runBlocking {
        val src = image(300, 200)
        val out = assertNotNull(DesktopPageImageProcessor().process(src, dir.absolutePath, quarterTurns = 1, enhance = false))
        val img = ImageIO.read(File(out))
        assertEquals(200, img.width)
        assertEquals(300, img.height)
        val half = ImageIO.read(File(DesktopPageImageProcessor().process(src, dir.absolutePath, quarterTurns = 2, enhance = false)!!))
        assertEquals(300, half.width)
    }

    @Test
    fun enhanceMakesThePageGrey() = runBlocking {
        val out = DesktopPageImageProcessor().process(image(64, 64), dir.absolutePath, quarterTurns = 0, enhance = true)!!
        val rgb = ImageIO.read(File(out)).getRGB(32, 32)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        assertTrue(kotlin.math.abs(r - g) <= 3 && kotlin.math.abs(g - b) <= 3, "expected grey, got $r,$g,$b")
    }

    @Test
    fun unreadableImagesReturnNull() = runBlocking {
        val bad = File(dir, "bad.jpg").apply { writeText("not an image") }
        assertNull(DesktopPageImageProcessor().process(bad.absolutePath, dir.absolutePath, 1, false))
    }

    @Test
    fun appleScriptStringsAreEscaped() {
        val n = DesktopNotifier()
        assertEquals("\"Bruno's \\\"Rabies\\\" dose \\\\ 2\"", n.appleScriptString("Bruno's \"Rabies\" dose \\ 2"))
    }
}
