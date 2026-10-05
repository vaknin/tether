package com.kivan.tether.dibs

// A task's REPORT.md as plain readable blocks: headings, paragraphs, bullets, code. No library;
// inline marks (**bold**, `code`) are left to the screen.

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    /** A list item; [depth] 0 is the outer list, [mark] its bullet or number ("•", "2."). */
    data class Item(val text: String, val depth: Int, val mark: String) : MdBlock
    data class Code(val text: String) : MdBlock
    /** A table's rows, cells trimmed (the separator row dropped). */
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val heading = Regex("""^(#{1,6})\s+(.*)$""")
private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val numbered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val tableSep = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")

fun markdownBlocks(md: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
        para.clear()
    }
    val lines = md.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += MdBlock.Code(code.toString())
            }
            trimmed.isEmpty() -> flush()
            heading.matches(trimmed) -> {
                flush()
                val m = heading.find(trimmed)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim().trimEnd('#').trim())
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                flush()
                out += MdBlock.Rule
            }
            trimmed.startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                flush()
                val rows = ArrayList<List<String>>()
                rows += cells(trimmed)
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += cells(lines[i].trim())
                    i++
                }
                out += MdBlock.Table(rows)
                continue
            }
            bullet.matches(line) -> {
                flush()
                val m = bullet.find(line)!!
                out += MdBlock.Item(m.groupValues[2].trim(), m.groupValues[1].length / 2, "•")
            }
            numbered.matches(line) -> {
                flush()
                val m = numbered.find(line)!!
                out += MdBlock.Item(m.groupValues[3].trim(), m.groupValues[1].length / 2, "${m.groupValues[2]}.")
            }
            // A line that continues a list item.
            line.startsWith("  ") && para.isEmpty() && out.lastOrNull() is MdBlock.Item -> {
                val last = out.removeAt(out.size - 1) as MdBlock.Item
                out += last.copy(text = last.text + " " + trimmed)
            }
            else -> {
                if (para.isNotEmpty()) para.append(if (line.endsWith("  ")) "\n" else " ")
                para.append(trimmed)
            }
        }
        i++
    }
    flush()
    return out
}

private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/** Inline spans of a line: plain text, **bold**, `code`. */
sealed interface MdSpan {
    val text: String
    data class Plain(override val text: String) : MdSpan
    data class Bold(override val text: String) : MdSpan
    data class Code(override val text: String) : MdSpan
}

private val inline = Regex("""`([^`]+)`|\*\*(.+?)\*\*|__(.+?)__""")

fun markdownSpans(text: String): List<MdSpan> {
    val out = ArrayList<MdSpan>()
    var at = 0
    for (m in inline.findAll(text)) {
        if (m.range.first > at) out += MdSpan.Plain(text.substring(at, m.range.first))
        out += when {
            m.groupValues[1].isNotEmpty() -> MdSpan.Code(m.groupValues[1])
            m.groupValues[2].isNotEmpty() -> MdSpan.Bold(m.groupValues[2])
            else -> MdSpan.Bold(m.groupValues[3])
        }
        at = m.range.last + 1
    }
    if (at < text.length) out += MdSpan.Plain(text.substring(at))
    return out
}
