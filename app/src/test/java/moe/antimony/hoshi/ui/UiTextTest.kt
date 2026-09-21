package moe.antimony.hoshi.ui

import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test
    fun joinedFailuresResolveNestedReasonsInTheCurrentLocale() {
        val text = UiText.Resource(1, UiText.Joined(listOf(
            UiText.Resource(2, "壊れた𠮟.zip", UiText.Resource(3)),
            UiText.Resource(2, "Other.zip", UiText.Literal("native detail")),
        )))
        for (reason in listOf("Invalid archive", "压缩包无效")) {
            assertEquals("Failures: 壊れた𠮟.zip: $reason\nOther.zip: native detail", text.resolve(
                getString = { id, args -> when (id) {
                    1 -> "Failures: ${args[0]}"
                    2 -> "${args[0]}: ${args[1]}"
                    3 -> reason
                    else -> error("Unexpected resource")
                } },
                getQuantityString = { _, _, _ -> error("No plurals") },
            ))
        }
    }

    @Test
    fun literalResolvesWithoutResourceLookup() {
        assertEquals(
            "External error",
            UiText.Literal("External error").resolve(
                getString = { _, _ -> error("No resource lookup expected") },
                getQuantityString = { _, _, _ -> error("No plural lookup expected") },
            ),
        )
    }

    @Test
    fun resourceResolvesWithArguments() {
        assertEquals(
            "Importing book.epub...",
            UiText.Resource(R.string.bookshelf_importing_named_format, "book.epub").resolve(
                getString = { id, args ->
                    assertEquals(R.string.bookshelf_importing_named_format, id)
                    assertEquals(listOf("book.epub"), args.toList())
                    "Importing book.epub..."
                },
                getQuantityString = { _, _, _ -> error("No plural lookup expected") },
            ),
        )
    }

    @Test
    fun pluralResolvesWithQuantityAndArguments() {
        assertEquals(
            "Delete 2 books?",
            UiText.Plural(R.plurals.bookshelf_bulk_delete_title, 2, 2).resolve(
                getString = { _, _ -> error("No string lookup expected") },
                getQuantityString = { id, quantity, args ->
                    assertEquals(R.plurals.bookshelf_bulk_delete_title, id)
                    assertEquals(2, quantity)
                    assertEquals(listOf(2), args.toList())
                    "Delete 2 books?"
                },
            ),
        )
    }
}
