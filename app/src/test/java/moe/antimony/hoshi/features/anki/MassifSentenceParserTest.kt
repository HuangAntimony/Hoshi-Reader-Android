package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class MassifSentenceParserTest {
    private val fixture = """
        <div id="results-list"><ul>
        <li class="text-japanese">
          <div>いろいろと<em>勉強</em>になりました。</div>
          <div class="result-meta"><a class="source_link" href="x">src</a></div>
        </li>
        <li class="text-japanese" data-id="9">
          <div><em>勉強</em>させて&#12356;ただき、ありがとうございました</div>
        </li>
        <li class="text-japanese">
          <div>とっても<em>勉強</em>になりました。</div>
        </li>
        </ul></div>
    """.trimIndent()

    @Test
    fun extractsSentencesStripsTagsAndUnescapesEntities() {
        assertEquals(
            listOf(
                ExampleSentence("いろいろと勉強になりました。", listOf(5..6)),
                ExampleSentence("勉強させていただき、ありがとうございました", listOf(0..1)),
            ),
            parseMassifSentences(fixture, limit = 2),
        )
    }

    @Test
    fun highlightsTrackConjugatedMatchesPastEntities() {
        val body = """<li class="text-japanese"><div>そう<em>思っ</em>ていた。</div></li>"""
        assertEquals(
            listOf(ExampleSentence("そう思っていた。", listOf(2..3))),
            parseMassifSentences(body, limit = 5),
        )
    }

    @Test
    fun capsResultsAtLimit() {
        assertEquals(1, parseMassifSentences(fixture, limit = 1).size)
    }

    @Test
    fun emptyBodyReturnsEmpty() {
        assertEquals(emptyList<ExampleSentence>(), parseMassifSentences("", limit = 5))
    }

    @Test
    fun toleratesExtraLiAttributesAndNamedEntities() {
        val body = """<li class="text-japanese" data-id="9"><div>A&amp;B&lt;C&gt;の<em>例</em>です。</div></li>"""
        assertEquals(
            listOf(ExampleSentence("A&B<C>の例です。", listOf(7..7))),
            parseMassifSentences(body, limit = 5),
        )
    }
}
