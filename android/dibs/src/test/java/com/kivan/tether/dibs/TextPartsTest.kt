package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPartsTest {
    private fun t(s: String) = TextPart(false, s)
    private fun c(s: String) = TextPart(true, s)

    @Test
    fun plainStaysWhole() {
        assertEquals(listOf(t("one\ntwo `x` three")), textParts("one\ntwo `x` three"))
        assertEquals(emptyList<TextPart>(), textParts(""))
    }

    @Test
    fun blockBetweenText() {
        assertEquals(
            listOf(t("Run this:"), c("cargo test\ncargo build"), t("then ship.")),
            textParts("Run this:\n```\ncargo test\ncargo build\n```\nthen ship."),
        )
    }

    @Test
    fun languageWordIsDropped() {
        assertEquals(listOf(c("fn main() {}")), textParts("```rust\nfn main() {}\n```"))
        assertEquals(listOf(c("ls")), textParts("  ```sh\nls\n  ```"))
    }

    @Test
    fun unclosedRunsToEnd() {
        assertEquals(listOf(t("see"), c("a\nb")), textParts("see\n```\na\nb"))
    }

    @Test
    fun emptyBlockDropsAndTextJoins() {
        assertEquals(listOf(t("a\nb")), textParts("a\n```\n```\nb"))
    }

    @Test
    fun crlfAndBlankLinesKept() {
        assertEquals(listOf(t("a\n\nb"), c("x")), textParts("a\r\n\r\nb\r\n```\r\nx\r\n```"))
    }
}
