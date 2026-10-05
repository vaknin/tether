package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun blocks() {
        val md = """
            # Budget pick

            Three listings,
            best first.

            - **M70q** for 900
              with 16 GB
            - Check the fan
            1. First
            ```
            free -h
            ```
            | Model | Price |
            |---|---|
            | M70q | 900 |
            ---
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Budget pick"),
                MdBlock.Para("Three listings, best first."),
                MdBlock.Item("**M70q** for 900 with 16 GB", 0, "•"),
                MdBlock.Item("Check the fan", 0, "•"),
                MdBlock.Item("First", 0, "1."),
                MdBlock.Code("free -h"),
                MdBlock.Table(listOf(listOf("Model", "Price"), listOf("M70q", "900"))),
                MdBlock.Rule,
            ),
            markdownBlocks(md),
        )
    }

    @Test
    fun spans() {
        assertEquals(
            listOf(MdSpan.Plain("Run "), MdSpan.Code("cargo test"), MdSpan.Plain(" and "), MdSpan.Bold("ship"), MdSpan.Plain(".")),
            markdownSpans("Run `cargo test` and **ship**."),
        )
    }
}
