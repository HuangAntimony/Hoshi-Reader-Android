package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSearchSourceTest {
    // A real Bing `a.iusc` m-attribute payload (captured live, 紫陽花 search).
    private val realM =
        """{"sid":"","cturl":"","cid":"o72MmeQM",""" +
            """"purl":"https://allabout-japan.com/zh-tw/article/5466/",""" +
            """"murl":"https://imgcp.aacdn.jp/img-a/1720/auto/x.jpg",""" +
            """"turl":"https://ts1.mm.bing.net/th?id=OIP.o72MmeQMDCc&pid=15.1",""" +
            """"md5":"a3bd8c99","t":"紫陽花 | All About Japan","mid":"5F8A644C"}"""

    @Test
    fun mapsOnePayload() {
        val c = parseIusc(listOf(realM)).single()
        assertEquals("https://ts1.mm.bing.net/th?id=OIP.o72MmeQMDCc&pid=15.1", c.thumbUrl)
        assertEquals("https://imgcp.aacdn.jp/img-a/1720/auto/x.jpg", c.fullUrl)
        assertEquals("紫陽花 | All About Japan", c.title)
        assertEquals("https://allabout-japan.com/zh-tw/article/5466/", c.sourcePage)
    }

    @Test
    fun skipsMalformedJson() {
        val out = parseIusc(listOf("{not json", realM))
        assertEquals(1, out.size)
        assertEquals("紫陽花 | All About Japan", out[0].title)
    }

    @Test
    fun skipsEntriesWithoutImageUrls() {
        val blank = """{"turl":"","murl":"","t":"ad","purl":"https://x"}"""
        val noThumb = """{"turl":"","murl":"https://x/full.jpg","t":"a","purl":"https://x"}"""
        val noFull = """{"turl":"https://t/1","murl":"","t":"a","purl":"https://x"}"""
        val out = parseIusc(listOf(blank, noThumb, noFull, realM))
        assertEquals(1, out.size)
        assertEquals("https://imgcp.aacdn.jp/img-a/1720/auto/x.jpg", out[0].fullUrl)
    }

    @Test
    fun skipsEntriesWithNonPrimitiveFieldsWithoutCrashing() {
        val weird = """{"turl":["a","b"],"murl":{"x":1},"t":"bad"}"""
        val out = parseIusc(listOf(weird, realM))
        assertEquals(1, out.size)
        assertEquals("紫陽花 | All About Japan", out[0].title)
    }

    @Test
    fun dedupesByFullUrlPreservingOrder() {
        val a = """{"turl":"https://t/1","murl":"https://x/a.jpg","t":"A"}"""
        val b = """{"turl":"https://t/2","murl":"https://x/b.jpg","t":"B"}"""
        val aDup = """{"turl":"https://t/3","murl":"https://x/a.jpg","t":"A again"}"""
        assertEquals(
            listOf("https://x/a.jpg", "https://x/b.jpg"),
            parseIusc(listOf(a, b, aDup)).map { it.fullUrl },
        )
    }
}
