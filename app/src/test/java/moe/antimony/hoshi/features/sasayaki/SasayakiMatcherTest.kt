package moe.antimony.hoshi.features.sasayaki

import com.sun.management.ThreadMXBean
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubBookParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.management.ManagementFactory

class SasayakiMatcherTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun repeatedResynchronizationAllocationScalesNearLinearly() {
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(allocationBean?.isThreadAllocatedMemorySupported == true)
        allocationBean ?: return
        allocationBean.isThreadAllocatedMemoryEnabled = true

        val book = EpubBook(
            title = "Allocation Test",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>${"正文".repeat(128)}</body></html>",
                ),
            ),
        )

        fun cues(count: Int): List<SasayakiCue> =
            List(count) { index ->
                SasayakiCue(
                    id = index.toString(),
                    startTime = index.toDouble(),
                    endTime = index + 1.0,
                    text = "不存在的字幕文本${index}号",
                )
            }

        fun allocatedBytes(cueCount: Int): Long {
            val testCues = cues(cueCount)
            @Suppress("DEPRECATION")
            val threadId = Thread.currentThread().id
            val before = allocationBean.getThreadAllocatedBytes(threadId)
            val result = SasayakiMatcher.match(book = book, cues = testCues)
            val allocated = allocationBean.getThreadAllocatedBytes(threadId) - before
            assertEquals(0, result.matches.size)
            assertEquals(cueCount, result.unmatched)
            return allocated
        }

        allocatedBytes(200)
        val smallAllocation = allocatedBytes(2_000)
        val largeAllocation = allocatedBytes(4_000)

        assertTrue(
            "Expected near-linear allocation growth, small=$smallAllocation large=$largeAllocation",
            largeAllocation < smallAllocation * 2.25,
        )
    }

    @Test
    fun matchesCuesAgainstFilteredChapterTextAndSavesChapterOffsets() {
        val book = EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter(
                    id = "chapter-1",
                    href = "chapter-1.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = """
                        <html><body>
                          <p><ruby>渚<rt>なぎさ</rt></ruby>　それはある日の、あたし達にとっては日常の光景だった。</p>
                        </body></html>
                    """.trimIndent(),
                ),
                EpubChapter(
                    id = "chapter-2",
                    href = "chapter-2.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body><p>次の章の本文です。</p></body></html>",
                ),
            ),
        )
        val cues = listOf(
            SasayakiCue(id = "0", startTime = 1.0, endTime = 2.0, text = "＊スキップ"),
            SasayakiCue(id = "1", startTime = 24.148, endTime = 28.468, text = "渚　それはある日の、あたし達にとっては日常の光景だった。"),
            SasayakiCue(id = "2", startTime = 29.0, endTime = 31.0, text = "次の章の本文です。"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(2, match.matches.size)
        assertEquals(1, match.unmatched)
        assertEquals("1", match.matches[0].id)
        assertEquals(0, match.matches[0].chapterIndex)
        assertEquals(0, match.matches[0].start)
        assertEquals("渚それはある日のあたし達にとっては日常の光景だった".length, match.matches[0].length)
        assertEquals("2", match.matches[1].id)
        assertEquals(1, match.matches[1].chapterIndex)
        assertEquals(0, match.matches[1].start)
    }

    @Test
    fun choosesLaterCoherentCueSequenceOverEarlierRepeatedOpeningCue() {
        val lowerVolumeLines = listOf(
            "八月一日火曜日",
            "時計館新館に宿泊した二人が起床した",
            "午前十一時半ごろのことだった",
            "鹿谷が尋ねた",
            "そのあとに続く固有の文章です",
            "さらに次の文章が続いている",
        )
        val book = EpubBook(
            title = "Combined Book",
            chapters = listOf(
                EpubChapter(
                    id = "upper",
                    href = "upper.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>鹿谷が尋ねた。${"上".repeat(300)}</body></html>",
                ),
                EpubChapter(
                    id = "lower",
                    href = "lower.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>${lowerVolumeLines.joinToString("。")}</body></html>",
                ),
            ),
        )
        val cues = lowerVolumeLines.mapIndexed { index, text ->
            SasayakiCue(index.toString(), index.toDouble(), index + 1.0, text)
        }

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(lowerVolumeLines.indices.map(Int::toString), match.matches.map { it.id })
        assertEquals(List(lowerVolumeLines.size) { 1 }, match.matches.map { it.chapterIndex })
        assertEquals(0, match.unmatched)
    }

    @Test
    fun backfillsUniqueCuesBeforeTheFirstTrustedAnchor() {
        val book = EpubBook(
            title = "Book With Recoverable Opening",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = """
                        <html><body>
                          小説家は妹キチイお兄ちゃん。起っきっき。
                          そんな声が聞こえて目を開けると、俺の目の前に妹が立っていた。
                          それは文句のつけようのない朝だった。
                        </body></html>
                    """.trimIndent(),
                ),
            ),
        )
        val cues = listOf(
            SasayakiCue("0", 0.0, 1.0, "＊音声だけの作品紹介"),
            SasayakiCue("1", 1.0, 2.0, "＊小説家は妹キチ●イ「お兄ちゃん"),
            SasayakiCue("2", 2.0, 3.0, "起っきっき～」"),
            SasayakiCue("3", 3.0, 4.0, "そんな声が聞こえて目を開けると、"),
            SasayakiCue("4", 4.0, 5.0, "俺の目の前に妹が立っていた。"),
            SasayakiCue("5", 5.0, 6.0, "それは文句のつけようのない朝だった。"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("1", "2", "3", "4", "5"), match.matches.map { it.id })
        assertEquals(1, match.unmatched)
    }

    @Test
    fun doesNotBackfillAmbiguousCueBeforeTheFirstTrustedAnchor() {
        val trustedLines = listOf(
            "第一条稳定锚点正文",
            "第二条稳定锚点正文",
            "第三条稳定锚点正文",
        )
        val book = EpubBook(
            title = "Book With Ambiguous Opening",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>重复开场。重复开场。${trustedLines.joinToString("。")}。</body></html>",
                ),
            ),
        )
        val cues = (listOf("重复开场") + trustedLines).mapIndexed { index, text ->
            SasayakiCue(index.toString(), index.toDouble(), index + 1.0, text)
        }

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("1", "2", "3"), match.matches.map { it.id })
        assertEquals(1, match.unmatched)
    }

    @Test
    fun resynchronizesOnlyWhenMultipleLaterCuesFormACoherentSequence() {
        val openingLines = listOf(
            "最初の固有文章です",
            "二番目の固有文章です",
            "三番目の固有文章です",
            "四番目の固有文章です",
            "五番目の固有文章です",
        )
        val resumedLines = listOf(
            "再同期一番目の文章です",
            "再同期二番目の文章です",
            "再同期三番目の文章です",
            "再同期四番目の文章です",
        )
        val book = EpubBook(
            title = "Book With Inserted Text",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = buildString {
                        append("<html><body>")
                        append(openingLines.joinToString("。"))
                        append("挿入".repeat(150))
                        append(resumedLines.joinToString("。"))
                        append("</body></html>")
                    },
                ),
            ),
        )
        val cues = (openingLines + resumedLines).mapIndexed { index, text ->
            SasayakiCue(index.toString(), index.toDouble(), index + 1.0, text)
        }

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(cues.map { it.id }, match.matches.map { it.id })
        assertEquals(0, match.unmatched)
    }

    @Test
    fun recoversLowConfidenceCueOnlyAfterTrustedResynchronization() {
        val opening = listOf(
            "开头的第一条可信正文",
            "开头的第二条可信正文",
            "开头的第三条可信正文",
            "开头的第四条可信正文",
        )
        val recoverableLowConfidence = "低可信但确实存在于正文中的旁白"
        val trustedAnchors = listOf(
            "恢复位置后的第一条可信正文",
            "恢复位置后的第二条可信正文",
            "恢复位置后的第三条可信正文",
        )
        val book = EpubBook(
            title = "Book With Low Confidence Gap",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = buildString {
                        append("<html><body>")
                        append(opening.joinToString("。"))
                        append("插入".repeat(90))
                        append(recoverableLowConfidence)
                        append(trustedAnchors.joinToString("。"))
                        append("</body></html>")
                    },
                ),
            ),
        )
        val cueTexts = opening + listOf(
            "＊正文中不存在的低可信旁白甲",
            "＊$recoverableLowConfidence",
            "＊正文中不存在的低可信旁白乙",
        ) + trustedAnchors
        val cues = cueTexts.mapIndexed { index, text ->
            SasayakiCue(index.toString(), index.toDouble(), index + 1.0, text)
        }

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("0", "1", "2", "3", "5", "7", "8", "9"), match.matches.map { it.id })
        assertEquals(2, match.unmatched)
        assertTrue(
            match.matches.zipWithNext().all { (current, next) ->
                current.chapterIndex < next.chapterIndex ||
                    current.chapterIndex == next.chapterIndex && current.start + current.length <= next.start
            },
        )
    }

    @Test
    fun lowConfidenceCuesCannotEstablishAResynchronizationPoint() {
        val opening = "开头的可信正文已经匹配"
        val lowConfidenceLines = listOf(
            "第一条低可信旁白文本",
            "第二条低可信旁白文本",
            "第三条低可信旁白文本",
        )
        val book = EpubBook(
            title = "Book With Only Low Confidence Recovery",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>$opening${"插入".repeat(90)}${lowConfidenceLines.joinToString("。")}</body></html>",
                ),
            ),
        )
        val cues = (listOf(opening) + lowConfidenceLines.map { "＊$it" }).mapIndexed { index, text ->
            SasayakiCue(index.toString(), index.toDouble(), index + 1.0, text)
        }

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("0"), match.matches.map { it.id })
        assertEquals(3, match.unmatched)
    }

    @Test
    fun doesNotRecoverAmbiguousLowConfidenceCueBetweenTrustedPositions() {
        val opening = listOf(
            "开头的第一条可信正文",
            "开头的第二条可信正文",
            "开头的第三条可信正文",
            "开头的第四条可信正文",
        )
        val ambiguous = "重复出现的低可信旁白文本"
        val trustedAnchors = listOf(
            "恢复位置后的第一条可信正文",
            "恢复位置后的第二条可信正文",
            "恢复位置后的第三条可信正文",
        )
        val book = EpubBook(
            title = "Book With Ambiguous Low Confidence Cue",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = buildString {
                        append("<html><body>")
                        append(opening.joinToString("。"))
                        append("插入".repeat(90))
                        append(ambiguous)
                        append("间隔正文")
                        append(ambiguous)
                        append(trustedAnchors.joinToString("。"))
                        append("</body></html>")
                    },
                ),
            ),
        )
        val cueTexts = opening + listOf(
            "＊正文中不存在的低可信旁白甲",
            "＊$ambiguous",
            "＊正文中不存在的低可信旁白乙",
        ) + trustedAnchors
        val cues = cueTexts.mapIndexed { index, text ->
            SasayakiCue(index.toString(), index.toDouble(), index + 1.0, text)
        }

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("0", "1", "2", "3", "7", "8", "9"), match.matches.map { it.id })
        assertEquals(3, match.unmatched)
    }

    @Test
    fun countsEachCueOnceWhenResynchronizationCrossesAShortLowConfidenceCue() {
        val opening = listOf(
            "开头的第一条可信正文",
            "开头的第二条可信正文",
            "开头的第三条可信正文",
            "开头的第四条可信正文",
        )
        val trustedAnchors = listOf(
            "恢复位置后的第一条可信正文",
            "恢复位置后的第二条可信正文",
            "恢复位置后的第三条可信正文",
        )
        val cueTexts = opening + listOf(
            "正文中不存在的失败文本甲",
            "正文中不存在的失败文本乙",
            "＊瞬间",
            "正文中不存在的失败文本丙",
        ) + trustedAnchors
        val book = EpubBook(
            title = "Book With Skipped Cue During Recovery",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>${opening.joinToString("。")}${"插入".repeat(90)}${trustedAnchors.joinToString("。")}</body></html>",
                ),
            ),
        )
        val cues = cueTexts.mapIndexed { index, text ->
            SasayakiCue(index.toString(), index.toDouble(), index + 1.0, text)
        }

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("0", "1", "2", "3", "8", "9", "10"), match.matches.map { it.id })
        assertEquals(4, match.unmatched)
        assertEquals(cues.size, match.matches.size + match.unmatched)
        assertEquals(match.matches.size, match.matches.map { it.id }.distinct().size)
    }

    @Test
    fun doesNotResynchronizeToAnIsolatedFarCue() {
        val openingLines = listOf(
            "开头第一句完整文章",
            "开头第二句完整文章",
            "开头第三句完整文章",
        )
        val isolatedLine = "只有这一句在远处出现"
        val book = EpubBook(
            title = "Book With Isolated Match",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>${openingLines.joinToString("。")}。${"隔".repeat(300)}$isolatedLine</body></html>",
                ),
            ),
        )
        val cues = (openingLines + listOf("短一", "短二", isolatedLine)).mapIndexed { index, text ->
            SasayakiCue(index.toString(), index.toDouble(), index + 1.0, text)
        }

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("0", "1", "2"), match.matches.map { it.id })
        assertEquals(3, match.unmatched)
    }

    @Test
    fun localSearchIncludesCueLength() {
        val book = EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>これは検索窓より長い本文です。</body></html>",
                ),
            ),
        )
        val cue = SasayakiCue(
            id = "0",
            startTime = 0.0,
            endTime = 1.0,
            text = "これは検索窓より長い本文です。",
        )

        val match = SasayakiMatcher.match(book = book, cues = listOf(cue))

        assertEquals(listOf("0"), match.matches.map { it.id })
        assertEquals(0, match.unmatched)
    }

    @Test
    fun doesNotMatchAcrossChapterBoundariesLikeIos() {
        val book = EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter("a", "a.xhtml", "application/xhtml+xml", "<html><body>前半</body></html>"),
                EpubChapter("b", "b.xhtml", "application/xhtml+xml", "<html><body>後半</body></html>"),
            ),
        )

        val match = SasayakiMatcher.match(
            book = book,
            cues = listOf(SasayakiCue("0", 0.0, 1.0, "前半後半")),
        )

        assertEquals(0, match.matches.size)
        assertEquals(1, match.unmatched)
    }

    @Test
    fun longStarPrefixedCuesStillAdvanceCursorLikeIos() {
        val book = EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>最初の文章です星一番です星二番です星三番です東条さんの言葉だった</body></html>",
                ),
            ),
        )
        val cues = listOf(
            SasayakiCue("0", 0.0, 1.0, "最初の文章です"),
            SasayakiCue("1", 1.0, 2.0, "＊星一番です"),
            SasayakiCue("2", 2.0, 3.0, "＊星二番です"),
            SasayakiCue("3", 3.0, 4.0, "＊星三番です"),
            SasayakiCue("4", 4.0, 5.0, "東条さんの言葉だった"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("0", "1", "2", "3", "4"), match.matches.map { it.id })
        assertEquals(0, match.unmatched)
    }

    @Test
    fun skipsShortStarPrefixedCuesLikeIos() {
        val book = EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html><body>最初の文章です星次の本文です</body></html>",
                ),
            ),
        )
        val cues = listOf(
            SasayakiCue("0", 0.0, 1.0, "最初の文章です"),
            SasayakiCue("1", 1.0, 2.0, "＊星"),
            SasayakiCue("2", 2.0, 3.0, "次の本文です"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("0", "2"), match.matches.map { it.id })
        assertEquals(1, match.unmatched)
    }

    @Test
    fun compatibilityIdeographsDoNotShiftLaterCueOffsetsLikeIosReaderJavascript() {
        val book = EpubBook(
            title = "Book",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = """
                        <html><body>
                          <p>正面にいた重元が三叉槍を手に立ち上がった。その姿はまるで<ruby>猪<rt>ちよ</rt>八<rt>はつ</rt>戒<rt>かい</rt></ruby>だ。「僕も部屋に戻るよ」</p>
                        </body></html>
                    """.trimIndent(),
                ),
            ),
        )
        val cues = listOf(
            SasayakiCue("3802", 14577.372, 14580.4, "三叉槍を手に立ち上がった。"),
            SasayakiCue("3803", 14580.4, 14583.852, "その姿はまるで猪八戒だ。"),
            SasayakiCue("3804", 14584.592, 14588.176, "「僕も部屋に戻るよ」"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("3802", "3803", "3804"), match.matches.map { it.id })
        assertEquals(0, match.unmatched)
        assertEquals(8, match.matches[0].start)
        assertEquals(20, match.matches[1].start)
        assertEquals(30, match.matches[2].start)
        assertEquals(10, match.matches[1].length)
    }

    @Test
    fun skipsGuideTocSpineItemsLikeIosDuringMatch() {
        val root = tempFolder.newFolder("guide-toc-book")
        writeGuideTocExtractedEpub(root)
        val book = EpubBookParser().parse(root)
        val cues = listOf(
            SasayakiCue("0", 0.0, 1.0, "目次第一部"),
            SasayakiCue("1", 1.0, 2.0, "本文一番長い文章"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("1"), match.matches.map { it.id })
        assertEquals(1, match.unmatched)
    }

    @Test
    fun skipsNonLinearAndNavSpineItemsLikeIosDuringMatch() {
        val root = tempFolder.newFolder("non-reader-spine-book")
        writeNonReaderSpineExtractedEpub(root)
        val book = EpubBookParser().parse(root)
        val cues = listOf(
            SasayakiCue("0", 0.0, 1.0, "ナビ見出し本文"),
            SasayakiCue("1", 1.0, 2.0, "付録非線形本文"),
            SasayakiCue("2", 2.0, 3.0, "読書本文一番長い文章"),
        )

        val match = SasayakiMatcher.match(book = book, cues = cues)

        assertEquals(listOf("2"), match.matches.map { it.id })
        assertEquals(2, match.unmatched)
    }

    private fun writeGuideTocExtractedEpub(root: File) {
        root.resolve("META-INF").mkdirs()
        root.resolve("META-INF/container.xml").writeText(
            """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent(),
        )

        root.resolve("OPS/text").mkdirs()
        root.resolve("OPS/text/toc.html").writeText("<html><body>目次第一部</body></html>")
        root.resolve("OPS/text/chapter.xhtml").writeText("<html><body>本文一番長い文章</body></html>")
        root.resolve("OPS/package.opf").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Guide TOC Book</dc:title>
              </metadata>
              <manifest>
                <item id="toc" href="text/toc.html" media-type="application/xhtml+xml"/>
                <item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="toc"/>
                <itemref idref="chapter"/>
              </spine>
              <guide>
                <reference type="toc" title="Table of Contents" href="text/toc.html"/>
              </guide>
            </package>
            """.trimIndent(),
        )
    }

    private fun writeNonReaderSpineExtractedEpub(root: File) {
        root.resolve("META-INF").mkdirs()
        root.resolve("META-INF/container.xml").writeText(
            """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent(),
        )

        root.resolve("OPS/text").mkdirs()
        root.resolve("OPS/text/nav.xhtml").writeText("<html><body>ナビ見出し本文</body></html>")
        root.resolve("OPS/text/appendix.xhtml").writeText("<html><body>付録非線形本文</body></html>")
        root.resolve("OPS/text/chapter.xhtml").writeText("<html><body>読書本文一番長い文章</body></html>")
        root.resolve("OPS/package.opf").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Non Reader Spine Book</dc:title>
              </metadata>
              <manifest>
                <item id="nav" href="text/nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="appendix" href="text/appendix.xhtml" media-type="application/xhtml+xml"/>
                <item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="nav"/>
                <itemref idref="appendix" linear="no"/>
                <itemref idref="chapter"/>
              </spine>
            </package>
            """.trimIndent(),
        )
    }
}
