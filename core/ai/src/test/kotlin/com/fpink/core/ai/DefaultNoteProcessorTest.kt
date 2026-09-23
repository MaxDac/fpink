package com.fpink.core.ai

import com.fpink.core.model.ImagePoint
import com.fpink.core.model.InkColorOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultNoteProcessorTest {
    private val processor = DefaultNoteProcessor()
    private val blue = 0xFF2058AF.toInt()
    private val red = 0xFFC42030.toInt()

    @Test
    fun `largest ink pixel area wins even when other color has more words`() = runTest {
        val page = Page(180, 40)
        page.fill(8, 8, 52, 12, blue)
        page.fill(8, 16, 52, 20, blue)
        val regions = mutableListOf(page.region("one", 4, 4, 58, 28, "p"))
        repeat(6) { word ->
            val x = 62 + word * 18
            page.fill(x + 4, 10, x + 9, 16, red)
            regions += page.region("red$word", x, 4, x + 14, 28, "p")
        }
        assertEquals(352, page.pixels.count { it == blue })
        assertEquals(180, page.pixels.count { it == red })
        assertTrue(page.pixels.count { it == -1 } > 6_000)

        val draft = process(page, regions).single()
        assertEquals("one red0 red1 red2 red3 red4 red5", draft.text)
        assertEquals("#2058AF", draft.inkColorHex)
        assertEquals("Blue", draft.inkColorName)
        assertEquals(InkColorOrigin.DETECTED, draft.colorOrigin)
    }

    @Test
    fun `thick majority strokes do not become local background and lose to sparse words`() = runTest {
        val page = Page(180, 40)
        page.fill(8, 8, 40, 20, blue)
        val regions = mutableListOf(page.region("bold", 4, 4, 48, 28, "p"))
        repeat(6) { word ->
            val x = 52 + word * 18
            page.fill(x + 4, 8, x + 9, 18, red)
            regions += page.region("word$word", x, 4, x + 14, 28, "p")
        }
        assertEquals(384, page.pixels.count { it == blue })
        assertEquals(300, page.pixels.count { it == red })
        assertEquals("#2058AF", process(page, regions).single().inkColorHex)
    }

    @Test
    fun `long thin ruling is not the dominant ink even when it has more pixels`() = runTest {
        val page = Page(160, 80)
        page.fill(4, 18, 156, 19, blue)
        page.fill(4, 52, 156, 53, blue)
        page.fill(12, 10, 32, 15, red)
        page.fill(12, 44, 32, 49, red)
        assertEquals(304, page.pixels.count { it == blue })
        assertEquals(200, page.pixels.count { it == red })

        val draft = process(page, listOf(
            page.region("first line", 4, 8, 156, 28, "p"),
            page.region("second line", 4, 42, 156, 62, "p"),
        )).single()
        assertEquals("#C42030", draft.inkColorHex)
        assertEquals(InkColorOrigin.DETECTED, draft.colorOrigin)
    }

    @Test
    fun `nearby ink shades combine before comparison with another color`() = runTest {
        val page = Page(160, 40)
        val shades = listOf(0xFF123F8C.toInt(), blue, 0xFF587EBB.toInt())
        shades.forEachIndexed { index, shade -> page.fill(8 + index * 28, 8, 23 + index * 28, 12, shade) }
        page.fill(104, 8, 130, 13, red)
        assertEquals(130, page.pixels.count { it == red })
        assertTrue(shades.all { shade -> page.pixels.count { it == shade } == 60 })

        val draft = process(page, listOf(page.region("shaded pen", 4, 4, 150, 28))).single()
        assertEquals("#2E5CA7", draft.inkColorHex)
        assertEquals("Blue", draft.inkColorName)
    }

    @Test
    fun `tinted paper and faint ink still produce foreground rather than paper`() = runTest {
        val page = Page(120, 40, 0xFFE8D9BB.toInt())
        page.fill(8, 8, 30, 12, blue)
        assertEquals("#2058AF",
            process(page, listOf(page.region("on cream paper", 4, 4, 110, 28))).single().inkColorHex)

        val faint = Page(120, 40)
        faint.fill(8, 8, 30, 12, 0xFFA3B5DA.toInt())
        val draft = process(faint, listOf(faint.region("faint", 4, 4, 110, 28))).single()
        assertEquals("#A3B5DA", draft.inkColorHex)
        assertEquals(InkColorOrigin.DETECTED, draft.colorOrigin)
    }

    @Test
    fun `gradual shadows are suppressed with a local paper estimate`() = runTest {
        val page = Page(160, 40)
        for (x in 0 until page.width) {
            val level = 130 + x * 125 / (page.width - 1)
            page.fill(x, 0, x + 1, page.height, (0xFF000000.toInt() or
                (level shl 16) or (level shl 8) or level))
        }
        page.fill(18, 8, 40, 12, red)
        val draft = process(page, listOf(page.region("in a shadow", 4, 4, 152, 28))).single()
        assertEquals("#C42030", draft.inkColorHex)
        assertEquals(InkColorOrigin.DETECTED, draft.colorOrigin)
    }

    @Test
    fun `overlapping polygons do not count the same red ink twice`() = runTest {
        val page = Page(80, 32)
        page.fill(8, 8, 18, 18, red)
        page.fill(36, 8, 51, 18, blue)
        val redRegion = page.region("red", 4, 4, 28, 24, "p")
        val draft = process(page, listOf(redRegion, redRegion.copy(text = "overlap"),
            page.region("blue", 32, 4, 64, 24, "p"))).single()
        assertEquals(100, page.pixels.count { it == red })
        assertEquals(150, page.pixels.count { it == blue })
        assertEquals("#2058AF", draft.inkColorHex)
    }

    @Test
    fun `ink outside text region union is excluded even inside paragraph envelope`() = runTest {
        val page = Page(130, 80)
        page.fill(0, 25, 130, 43, red)
        page.fill(10, 10, 26, 14, blue)
        page.fill(10, 50, 26, 54, blue)
        val draft = process(page, listOf(
            page.region("line one", 4, 4, 124, 24, "p"),
            page.region("line two", 4, 44, 124, 64, "p"),
        )).single()
        assertEquals("#2058AF", draft.inkColorHex)
    }

    @Test
    fun `polygon sampling uses its shape not only its rectangular bounds`() = runTest {
        val page = Page(100, 100)
        page.fill(55, 55, 88, 88, red)
        page.fill(15, 15, 25, 20, blue)
        val triangle = TextRegion("triangle", listOf(
            ImagePoint(0.1f, 0.1f), ImagePoint(0.9f, 0.1f), ImagePoint(0.1f, 0.9f),
        ))
        assertEquals("#2058AF", process(page, listOf(triangle)).single().inkColorHex)
    }

    @Test
    fun `measured black is distinct from black default for blank paper`() = runTest {
        val paper = Page(100, 40)
        val region = paper.region("text survives", 4, 4, 90, 28)
        val defaulted = process(paper, listOf(region)).single()
        assertEquals("text survives", defaulted.text)
        assertEquals("#000000", defaulted.inkColorHex)
        assertEquals("Black", defaulted.inkColorName)
        assertEquals(InkColorOrigin.DEFAULTED, defaulted.colorOrigin)

        paper.fill(8, 8, 30, 12, 0xFF000000.toInt())
        val measured = process(paper, listOf(region)).single()
        assertEquals("#000000", measured.inkColorHex)
        assertEquals("Black", measured.inkColorName)
        assertEquals(InkColorOrigin.DETECTED, measured.colorOrigin)
    }

    @Test
    fun `equal line spacing and first line indentation give exactly two logical paragraphs`() = runTest {
        val page = Page(220, 100)
        val drafts = process(page, listOf(
            page.region("First paragraph", 20, 10, 190, 22),
            page.region("continues here.", 10, 30, 130, 42),
            page.region("Second paragraph", 30, 50, 185, 62),
            page.region("also wraps.", 10, 70, 160, 82),
        ))
        assertEquals(listOf("First paragraph continues here.", "Second paragraph also wraps."),
            drafts.map { it.text })
    }

    @Test
    fun `relative line spacing separates paragraphs without indentation`() = runTest {
        val page = Page(220, 130)
        val drafts = process(page, listOf(
            page.region("first", 10, 10, 190, 22),
            page.region("wrapped", 10, 30, 190, 42),
            page.region("second", 10, 70, 190, 82),
            page.region("wrapped", 10, 90, 190, 102),
        ))
        assertEquals(listOf("first wrapped", "second wrapped"), drafts.map { it.text })
    }

    @Test
    fun `trustworthy cloud paragraph hints override indentation but distinct hints do not merge`() = runTest {
        val page = Page(220, 100)
        val regions = listOf(
            page.region("English paragraph", 10, 10, 180, 22, "cloud:0", 0.8f),
            page.region("an indented continuation", 30, 30, 190, 42, "cloud:0", 1f),
            page.region("Paragrafo italiano", 10, 50, 180, 62, "cloud:1", 0.9f),
            page.region("ancora qui.", 10, 70, 180, 82, "cloud:1"),
        )
        val drafts = process(page, regions, RecognitionProviderId("cloud"))
        assertEquals(listOf("English paragraph an indented continuation", "Paragrafo italiano ancora qui."),
            drafts.map { it.text })
        assertEquals(0.9f, drafts.first().confidence)
        assertEquals(drafts, process(page, regions, RecognitionProviderId.PADDLE))
    }

    @Test
    fun `obvious columns stay independent even with an erroneous common hint`() = runTest {
        val page = Page(240, 70)
        val drafts = process(page, listOf(
            page.region("left first", 10, 10, 95, 22, "same"),
            page.region("right first", 145, 10, 230, 22, "same"),
            page.region("left wrapped", 10, 30, 95, 42, "same"),
            page.region("right wrapped", 145, 30, 230, 42, "same"),
        ))
        assertEquals(listOf("left first left wrapped", "right first right wrapped"), drafts.map { it.text })
    }

    @Test
    fun `hanging list indentation is a wrap not a fresh paragraph`() = runTest {
        val page = Page(220, 80)
        val drafts = process(page, listOf(
            page.region("• An item with", 10, 10, 190, 22),
            page.region("a hanging continuation", 26, 30, 190, 42),
        ))
        assertEquals("• An item with a hanging continuation", drafts.single().text)
    }

    @Test
    fun `accents literal punctuation and ambiguous line end hyphens survive soft wrapping`() = runTest {
        val page = Page(220, 90)
        val drafts = process(page, listOf(
            page.region("  È già così: co-\noperare, però.  ", 10, 10, 190, 22, "p"),
            page.region("L'acqua — café; re-", 10, 30, 190, 42, "p"),
            page.region("enter.", 10, 50, 190, 62, "p"),
        ))
        assertEquals("È già così: co- operare, però. L'acqua — café; re- enter.", drafts.single().text)
    }

    @Test
    fun `no text and blank regions create no notes`() = runTest {
        val page = Page(10, 10)
        assertTrue(process(page, emptyList()).isEmpty())
        assertTrue(process(page, listOf(TextRegion(" \r\n\t", emptyList()))).isEmpty())
    }

    @Test
    fun `unusable or subpixel polygons retain text with an explicit default`() = runTest {
        val page = Page(100, 100)
        val geometries = listOf(
            emptyList(),
            listOf(ImagePoint(0.1f, 0.1f), ImagePoint(0.2f, 0.2f)),
            listOf(ImagePoint(0.1f, 0.1f), ImagePoint(0.8f, 0.8f),
                ImagePoint(0.8f, 0.1f), ImagePoint(0.1f, 0.8f)),
            listOf(ImagePoint(0.10001f, 0.10001f), ImagePoint(0.10002f, 0.10001f),
                ImagePoint(0.10002f, 0.10002f), ImagePoint(0.10001f, 0.10002f)),
        )
        for (geometry in geometries) {
            val draft = process(page, listOf(TextRegion("Keep me.", geometry))).single()
            assertEquals("Keep me.", draft.text)
            assertEquals("#000000", draft.inkColorHex)
            assertEquals(InkColorOrigin.DEFAULTED, draft.colorOrigin)
        }
    }

    @Test
    fun `transparent pixels and isolated noise are not a reliable color`() = runTest {
        val transparent = Page(100, 40, 0x002058AF)
        val draft = process(transparent, listOf(transparent.region("alpha", 4, 4, 90, 28))).single()
        assertEquals(InkColorOrigin.DEFAULTED, draft.colorOrigin)
        val noise = Page(100, 40)
        noise.fill(8, 8, 9, 9, blue)
        assertEquals(InkColorOrigin.DEFAULTED,
            process(noise, listOf(noise.region("noise", 4, 4, 90, 28))).single().colorOrigin)
    }

    @Test
    fun `bounded stratified sampling remains deterministic on a larger image`() = runTest {
        val page = Page(1_100, 600)
        repeat(20) { row ->
            page.fill(20, 20 + row * 24, 120, 24 + row * 24, blue)
            page.fill(220, 20 + row * 24, 260, 24 + row * 24, red)
        }
        val regions = listOf(page.region("large paragraph", 0, 0, 1_100, 600))
        val first = process(page, regions).single()
        assertEquals("#2058AF", first.inkColorHex)
        assertEquals(first, process(page, regions).single())
    }

    @Test
    fun `exhausted geometry work never returns a partial color estimate`() = runTest {
        val page = Page(100, 40)
        page.fill(8, 8, 30, 12, blue)
        val paragraph = reconstructParagraphs(
            listOf(page.region("text survives", 4, 4, 90, 28)), page.width, page.height,
        ).single()
        assertEquals(null, estimateInkColor(page.image(), paragraph, 1_000, GeometryBudget(10)))
        assertEquals("text survives", paragraph.text)
    }

    @Test
    fun `invalid input is not misreported as a defaulted note`() {
        val page = Page(10, 10)
        assertThrows(IllegalArgumentException::class.java) {
            runTest { process(page, listOf(page.region("invalid", 1, 1, 8, 8, confidence = Float.NaN))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runTest { process(page, List(4_097) { TextRegion("too many", emptyList()) }) }
        }
    }

    @Test
    fun `cancellation is thrown before returning a defaulted note`() = runTest {
        val page = Page(100, 40)
        var observedCancellation = false
        var returned = false
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext()[Job]!!.cancel()
            try {
                process(page, listOf(page.region("not saved", 4, 4, 90, 28)))
                returned = true
            } catch (_: CancellationException) {
                observedCancellation = true
            }
        }
        task.join()
        assertTrue(observedCancellation)
        assertFalse(returned)
    }

    @Test
    fun `cancellation during region traversal is not swallowed`() = runTest {
        val page = Page(100, 40)
        var observedCancellation = false
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            val job = currentCoroutineContext()[Job]!!
            val regions = object : AbstractList<TextRegion>() {
                override val size = 100
                override fun get(index: Int): TextRegion {
                    if (index == 12) job.cancel()
                    return page.region("line $index", 4, 4, 90, 28)
                }
            }
            try {
                process(page, regions)
            } catch (_: CancellationException) {
                observedCancellation = true
            }
        }
        task.join()
        assertTrue(observedCancellation)
    }

    @Test
    fun `manual color names share the detector palette and reject invalid hex`() {
        assertEquals("Black", inkColorName("#000000"))
        assertEquals("White", inkColorName("#FFFFFF"))
        assertEquals("Gray", inkColorName("#808080"))
        assertEquals("Red", inkColorName("#c42030"))
        assertEquals("Blue", inkColorName("#2058AF"))
        assertEquals("Green", inkColorName("#008000"))
        assertEquals("Teal", inkColorName("#008080"))
        assertThrows(IllegalArgumentException::class.java) { inkColorName("000000") }
        assertThrows(IllegalArgumentException::class.java) { inkColorName("#GGGGGG") }
    }

    private suspend fun process(
        page: Page,
        regions: List<TextRegion>,
        provider: RecognitionProviderId = RecognitionProviderId.PADDLE,
    ) = processor.process(page.image(), RecognitionDocument(regions, provider, "synthetic-fixture")).getOrThrow()

    private class Page(val width: Int, val height: Int, paper: Int = -1) {
        val pixels = IntArray(width * height) { paper }
        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color
        }
        fun image() = PreparedImage(byteArrayOf(1), "image/png", width, height, pixels)
        fun region(
            text: String,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            paragraph: String? = null,
            confidence: Float? = null,
        ) = TextRegion(text, listOf(
            ImagePoint(left.toFloat() / width, top.toFloat() / height),
            ImagePoint(right.toFloat() / width, top.toFloat() / height),
            ImagePoint(right.toFloat() / width, bottom.toFloat() / height),
            ImagePoint(left.toFloat() / width, bottom.toFloat() / height),
        ), paragraph, confidence)
    }
}
