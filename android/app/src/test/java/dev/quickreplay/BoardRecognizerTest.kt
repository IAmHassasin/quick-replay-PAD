package dev.quickreplay

import dev.quickreplay.pad.BoardRecognizer
import dev.quickreplay.pad.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Recognition on real screenshots (src/test/resources/pad):
 * - dawnglare-emulator.png: dawnGlare in Chrome on the QR_API35 emulator (1080x2400, desktop site),
 *   board set with ?patt=LGHLBBLRDDGLBRRLRRHBRGBLLBBRRD.
 * - ingame-watermelon.png: the owner's in-game screenshot (watermelon orb skin, enhanced orbs),
 *   cropped to the HP bar and the board.
 */
class BoardRecognizerTest {
    private class Image(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Decodes a PNG with the JDK's ImageIO. Called by reflection: unit tests compile against
     * android.jar, which has no javax.imageio / java.awt, but they run on a full JDK.
     */
    private fun load(name: String): Image {
        val file = listOf("src/test/resources/pad/$name", "app/src/test/resources/pad/$name").map(::File).first(File::isFile)
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
        val width = img.javaClass.getMethod("getWidth").invoke(img) as Int
        val height = img.javaClass.getMethod("getHeight").invoke(img) as Int
        val getRgb = img.javaClass.getMethod(
            "getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java,
        )
        val pixels = getRgb.invoke(img, 0, 0, width, height, null, 0, width) as IntArray
        return Image(pixels, width, height)
    }

    private fun recognize(name: String): Recognition.Found {
        val image = load(name)
        val started = System.nanoTime()
        val result = BoardRecognizer.recognize(image.pixels, image.width, image.height, rows = 5, cols = 6)
        println("$name: ${(System.nanoTime() - started) / 1_000_000} ms, $result")
        if (result is Recognition.Found) {
            println(result.board.format())
            println("grid=${result.grid} minConfidence=${result.confidence.min()}")
        }
        return result as? Recognition.Found ?: fail("$name: $result") as Nothing
    }

    @Test
    fun readsDawnGlareOnTheEmulator() {
        val found = recognize("dawnglare-emulator.png")
        assertEquals("LGHLBB\nLRDDGL\nBRRLRR\nHBRGBL\nLBBRRD", found.board.format())
        // Desktop-site page 980 CSS px wide on 1080 px: tiles of 90 CSS px = 99.2 px, the first tile
        // at 220 CSS px = 242.4 px.
        assertEquals(99.2f, found.grid.cell, 1f)
        assertEquals(242.4f, found.grid.left, 2f)
        assertTrue(found.confidence.all { it >= 0.5f })
    }

    @Test
    fun readsTheInGameWatermelonSkin() {
        val found = recognize("ingame-watermelon.png")
        assertEquals("LHDGHL\nHDLBHR\nGLBRGG\nGGBHBG\nHGLHBH", found.board.format())
        assertEquals(152f, found.grid.cell, 4f)
    }

    /** dawnglare-random-N.png: desktop dawnGlare at 720x1600 (90 px cells); truth from the page DOM in .txt. */
    @Test
    fun readsRandomDawnGlareBoards() {
        for (n in 1..3) {
            val name = "dawnglare-random-$n"
            val truth = listOf("src/test/resources/pad/$name.txt", "app/src/test/resources/pad/$name.txt")
                .map(::File).first(File::isFile).readText().replace("\r\n", "\n").trim() // CRLF after a Windows checkout
            val found = recognize("$name.png")
            assertEquals(name, truth, found.board.format())
            // Exact tile geometry from the page DOM: first tile at (90, 45), 90 px tiles.
            assertEquals(name, 90f, found.grid.cell, 0.7f)
            assertEquals(name, 90f, found.grid.left, 1.5f)
            assertEquals(name, 45f, found.grid.top, 1.5f)
        }
    }

    @Test
    fun failsWithoutABoard() {
        val width = 540
        val height = 1200
        // Plain grey screen.
        val pixels = IntArray(width * height) { 0xff808080.toInt() }
        val result = BoardRecognizer.recognize(pixels, width, height, rows = 5, cols = 6)
        assertTrue(result.toString(), result is Recognition.Failed)
    }
}
