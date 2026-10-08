package com.kivan.tether.dibs

// A chat line as plain text and fenced code blocks. The same rule as dibs's desktop app
// (desktop/src/lib/chatText.ts), so both apps agree.

data class TextPart(val code: Boolean, val text: String)

private fun fence(line: String) = line.trimStart().startsWith("```")

/**
 * Splits a line at its code fences. A row starting (after spaces) with three backticks opens a
 * block, and any word after them (the language) is dropped; the next such row closes it, and an
 * unclosed block runs to the end. Text keeps its exact words; only the one break next to a fence
 * goes. Empty parts are dropped, and single backticks are not parsed.
 */
fun textParts(text: String): List<TextPart> {
    val parts = ArrayList<TextPart>()
    var code = false
    var lines = ArrayList<String>()
    fun flush() {
        val body = lines.joinToString("\n")
        lines = ArrayList()
        if (body.isBlank()) return
        // Two text parts side by side (an empty block between them) keep their break.
        val last = parts.lastOrNull()
        if (!code && last != null && !last.code) parts[parts.lastIndex] = TextPart(false, last.text + "\n" + body)
        else parts += TextPart(code, body)
    }
    for (line in text.split(Regex("\r?\n"))) {
        if (!fence(line)) {
            lines += line
            continue
        }
        flush()
        code = !code
    }
    flush()
    return parts
}
