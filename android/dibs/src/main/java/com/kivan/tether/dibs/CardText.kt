package com.kivan.tether.dibs

// Web addresses and sign-in codes in what dibs shows (task #303): a question card carrying a
// GitHub device login ("enter code 070B-16D2 at github.com/login/device") must let the user open
// the address and copy the code. Pure: no Android, and offsets are UTF-16 like Kotlin strings.

/** [start] until [end] of the text is a web address; [url] is what opening it opens. */
data class WebLink(val start: Int, val end: Int, val url: String)

/** [start] until [end] of the text is a code to copy. */
data class SignInCode(val start: Int, val end: Int, val code: String)

// The end of an address never takes the sentence's own punctuation.
private const val TAIL = """[^\s<>".,;:!?)\]']"""

private val fullUrl = Regex("""\b(?:https?://|www\.)[^\s<>"]+$TAIL""")

// A bare address ("github.com/login/device"): only well-known endings, so a file name
// ("server.md", "setup.sh", "Cargo.toml") is never taken for one; not inside an e-mail address,
// a path or a longer address.
private val bareHost = Regex(
    """(?<![\w@./-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+""" +
        """(?:com|org|net|io|dev|app|ai|co|me|gg|tv|xyz|page|info|edu|gov|uk|il|de)(?![\w-])""" +
        """(?:/[^\s<>"]*$TAIL)?""",
    RegexOption.IGNORE_CASE,
)

/** Every web address in [text], in order: with a scheme, `www.`, or a bare one with a well-known ending. */
fun webLinks(text: String): List<WebLink> {
    val full = fullUrl.findAll(text).map { m ->
        val u = m.value
        WebLink(m.range.first, m.range.last + 1, if (u.startsWith("www.", ignoreCase = true)) "https://$u" else u)
    }.toList()
    val bare = bareHost.findAll(text)
        .filter { m -> full.none { it.start < m.range.last + 1 && m.range.first < it.end } }
        .map { WebLink(it.range.first, it.range.last + 1, "https://${it.value}") }
    return (full + bare).sortedBy { it.start }
}

// "code 070B-16D2", "code: 482913", "code is AB12CD": capitals and digits, with a digit in it.
private val namedCode = Regex("""\b(?i:code)(?:\s+(?i:is))?:?\s+([A-Z0-9][A-Z0-9-]{2,}[A-Z0-9])(?![\w-])""")

// A device login's code alone, as GitHub writes it: four and four ("070B-16D2").
private val pairCode = Regex("""(?<![\w-])[A-Z0-9]{4}-[A-Z0-9]{4}(?![\w-])""")

/** The codes in [text] to copy, in order, each once; never one inside a web address. */
fun signInCodes(text: String): List<SignInCode> {
    val links = webLinks(text)
    val named = namedCode.findAll(text).mapNotNull { m ->
        val g = m.groups[1] ?: return@mapNotNull null
        if (g.value.none { it.isDigit() }) null else SignInCode(g.range.first, g.range.last + 1, g.value)
    }
    val pairs = pairCode.findAll(text).map { SignInCode(it.range.first, it.range.last + 1, it.value) }
    return (named + pairs)
        .filter { c -> links.none { it.start < c.end && c.start < it.end } }
        .distinctBy { it.start }
        .sortedBy { it.start }
        .toList()
}
