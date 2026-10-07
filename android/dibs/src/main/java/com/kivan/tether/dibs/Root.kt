package com.kivan.tether.dibs

import org.json.JSONObject
import java.security.MessageDigest

// Root steps approved from the phone (dibs task #145; the wire formats are the task's SPEC.md,
// summed up in docs/DIBS-APP.md "Root steps"). A root request comes as a Waiting card of kind
// `root` with a `root` object; the phone shows it word for word, builds the message the laptop's
// root helper checks, and signs it with its own key after the user's fingerprint or face. The
// phone works out every text hash itself, from the bytes it shows: the laptop can't show one
// script and get another signed.

/**
 * A file the step brings: a text file shown whole ([text]), or a binary one the phone can't show
 * ([size] and [sha256], the laptop's hash).
 */
data class RootFile(val name: String, val text: String?, val size: Long?, val sha256: String?) {
    val binary: Boolean get() = text == null
}

/** One action on the allow list: what it's called, and its action hash. */
data class RootAllowed(val name: String, val action: String)

/**
 * A root request as dibs sends it (`questions[i].root`). [why] is the agent's own reason; [note]
 * and [pick] (`accept` | `reject`) are dibs's check, null while it hasn't looked.
 */
data class RootRequest(
    val request: Long?,
    val machine: String,
    val nonce: String,
    val expires: Long,
    val network: Boolean,
    /** `no` (hidden) or `ro` (visible, read-only). */
    val home: String,
    val timeout: Long,
    val script: String,
    val files: List<RootFile>,
    val why: String,
    val note: String?,
    val pick: String?,
    val allowList: List<RootAllowed>,
) {
    companion object {
        fun parse(o: JSONObject): RootRequest = RootRequest(
            request = if (o.has("request") && !o.isNull("request")) o.optLong("request", -1).takeIf { it >= 0 } else null,
            machine = o.optString("machine"),
            nonce = o.optString("nonce"),
            expires = o.optLong("expires"),
            network = o.optBoolean("network"),
            home = o.optString("home", "no"),
            timeout = o.optLong("timeout", 600),
            script = if (o.isNull("script")) "" else o.optString("script"),
            files = o.optJSONArray("files").let { a ->
                if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { f ->
                    // A file with `text` (even empty) is text; one without is binary.
                    val text = if (f.has("text") && !f.isNull("text")) f.optString("text") else null
                    RootFile(
                        f.optString("name"),
                        text,
                        if (text == null && f.has("size") && !f.isNull("size")) f.optLong("size") else null,
                        if (text == null && !f.isNull("sha256")) f.optString("sha256").takeIf { it.isNotEmpty() } else null,
                    )
                }
            },
            why = if (o.isNull("why")) "" else o.optString("why"),
            note = if (o.isNull("note")) null else o.optString("note").takeIf { it.isNotBlank() },
            pick = if (o.isNull("pick")) null else o.optString("pick").takeIf { it.isNotBlank() },
            allowList = o.optJSONArray("allow_list").let { a ->
                if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .map { RootAllowed(it.optString("name"), it.optString("action")) }
            },
        )
    }
}

object RootMessage {
    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    /**
     * Why the phone can't sign [r], in plain words, or null when it can. The helper refuses the same
     * things; refusing here too means nothing odd ever gets into a signed message.
     */
    fun problem(r: RootRequest): String? = when {
        r.request == null -> "It has no request number."
        !HEX32.matches(r.machine) -> "It doesn't name the laptop properly."
        !HEX32.matches(r.nonce) -> "Its one-time code is missing or wrong."
        r.expires <= 0 -> "It has no expiry time."
        r.home != "no" && r.home != "ro" -> "It asks for the home folder in a way the phone doesn't know."
        r.timeout !in 1..3600 -> "Its time limit is out of range."
        r.files.any { !NAME.matches(it.name) } -> "A file has a name the phone can't sign."
        r.files.map { it.name }.toSet().size != r.files.size -> "Two files have the same name."
        r.files.any { it.binary && (it.sha256 == null || !HEX64.matches(it.sha256)) } -> "A file the phone can't show has no proper hash."
        // The helper refuses these in a script, so a script that has them was never what it holds.
        hasHidden(r.script) -> "Its script has hidden characters (shown as ⟨U+…⟩), which the laptop never runs."
        else -> null
    }

    /**
     * The message the phone signs and the helper checks (SPEC.md §1), byte for byte: every hash of a
     * script or text file worked out here from the text shown; only a binary file's comes from the
     * laptop. [remember]: "Never ask again" is ticked. Call only when [problem] is null.
     */
    fun build(r: RootRequest, remember: Boolean): String = buildString {
        fun line(s: String) = append(s).append('\n')
        line("dibs-root v1")
        line("machine ${r.machine}")
        line("request ${r.request}")
        line("nonce ${r.nonce}")
        line("expires ${r.expires}")
        line("network ${yesNo(r.network)}")
        line("home ${r.home}")
        line("timeout ${r.timeout}")
        line("remember ${yesNo(remember)}")
        line("script ${sha256Hex(r.script.toByteArray(Charsets.UTF_8))}")
        for (f in sortedFiles(r.files)) line("file ${f.name} ${fileHash(f)}")
    }

    /** The files in byte order of their names (the names are ASCII, checked by [problem]). */
    fun sortedFiles(files: List<RootFile>): List<RootFile> = files.sortedWith { a, b -> compareBytes(a.name.toByteArray(), b.name.toByteArray()) }

    /** A text file's hash from its bytes; a binary file's as the laptop sent it. */
    fun fileHash(f: RootFile): String = f.text?.let { sha256Hex(it.toByteArray(Charsets.UTF_8)) } ?: f.sha256.orEmpty()

    /** The request's code: the first 16 hex digits of the message's sha256, in 4 groups. */
    fun shortHash(message: String): String = groups(sha256Hex(message.toByteArray(Charsets.UTF_8)))

    /** The key's code (SPEC.md §1): sha256 of its public key's SubjectPublicKeyInfo, 16 hex digits in 4 groups. */
    fun fingerprint(spki: ByteArray): String = groups(sha256Hex(spki))

    private fun groups(hex: String) = hex.take(16).chunked(4).joinToString(" ")

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return a.size - b.size
    }

    /**
     * A character the helper refuses in a script (SPEC.md §7, widened after its security review), and
     * treats as binary in a file: controls other than newline and tab; every space other than the
     * plain one (no-break, en, em, thin, ideographic …); line and paragraph separators; and the
     * invisible format characters (bidi controls, zero-width ones, soft hyphen, BOM: Unicode Cf), private
     * use (Co) and unassigned (Cn) code points. Any of them could make a line read differently from what
     * runs: a no-break space before `#` looks like a comment and isn't one.
     */
    fun hiddenChar(c: Int): Boolean {
        if (c == '\n'.code || c == '\t'.code || c == ' '.code) return false
        if (c in 0x200b..0x200f || c in 0x202a..0x202e || c in 0x2060..0x2064 || c in 0x2066..0x2069 || c == 0xfeff) return true
        return when (Character.getType(c).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE,
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            -> true
            else -> Character.isWhitespace(c) || Character.isSpaceChar(c)
        }
    }

    fun hasHidden(text: String): Boolean = text.codePoints().anyMatch(::hiddenChar)

    /**
     * [text] as the page shows it: word for word, with each hidden character written out as
     * `⟨U+202E⟩`, so nothing is invisible. (The hash is always of the text itself.)
     */
    fun visible(text: String): String {
        if (!hasHidden(text)) return text
        val sb = StringBuilder()
        text.codePoints().forEach { c -> if (hiddenChar(c)) sb.append("⟨U+%04X⟩".format(c)) else sb.appendCodePoint(c) }
        return sb.toString()
    }

    /** Within this many seconds of its expiry, a request says it expires soon. */
    const val SOON_SECS = 15 * 60L

    /**
     * The phone's own warnings, worked out from the bytes it shows (SPEC.md §3), in plain lines.
     * [now] is the phone's clock in seconds.
     */
    fun warnings(r: RootRequest, now: Long): List<String> {
        val texts = listOf(r.script) + r.files.mapNotNull { it.text }
        fun any(re: Regex) = texts.any { re.containsMatchIn(it) }
        return buildList {
            if (r.network) add("Uses the network.")
            if (r.home == "ro") add("Can read your home folder.")
            if (any(DOWNLOADS)) add("Downloads things from the internet.")
            if (any(PIPE_SHELL)) add("Pipes something into a shell, which runs whatever it gets.")
            if (any(RULES)) add("Changes sudo, login, polkit or SSH rules.")
            if (any(HELPER)) add("Changes the root helper itself.")
            if (any(DELETES)) add("Deletes or overwrites files.")
            if (any(USERS)) add("Changes users or passwords.")
            if (texts.any(::setuid)) add("Lets a program run with root's rights (setuid).")
            if (any(DISABLES)) add("Turns a service off for good (disable or mask).")
            r.files.filter { it.binary }.forEach { add("Brings ${it.name}, a file the phone can't show.") }
            if (texts.any(::hasHidden)) add("Has hidden characters, shown below as ⟨U+…⟩.")
            val left = r.expires - now
            if (left <= 0) add("Expired.") else if (left <= SOON_SECS) add("Expires in ${maxOf(1, left / 60)} min.")
        }
    }

    private val DOWNLOADS = Regex("""\bcurl\b|\bwget\b|\bgit\s+clone\b|\bpacman\s+-S|\bapt(-get)?\b""")
    private val PIPE_SHELL = Regex("""\|\s*(sudo\s+)?(\S*/)?(ba|z|da|k|fi)?sh\b""")
    private val RULES = Regex("""sudoers|/etc/pam\.d|polkit|ssh""")
    private val HELPER = Regex("""dibs-root""")
    private val DELETES = Regex("""\brm\s|\bshred\b|\btruncate\b|\bdd\s""")
    private val USERS = Regex("""\buseradd\b|\busermod\b|\bpasswd\b|\bchpasswd\b""")
    private val DISABLES = Regex("""\bsystemctl\b[^\n]*\b(disable|mask)\b""")
    private val SETUID_MODE = Regex("""[ugoa]*[+=][rwxXt]*s|\b[2467][0-7]{3}\b""")

    private fun setuid(text: String): Boolean = text.lineSequence().any { l ->
        val at = Regex("""\bchmod\b""").find(l) ?: return@any false
        SETUID_MODE.containsMatchIn(l.substring(at.range.last + 1))
    }

    /** How long it may run, in words: "10 min", "45 s", "1 h". */
    fun timeoutWords(secs: Long): String = when {
        secs < 60 -> "$secs s"
        secs % 3600 == 0L -> "${secs / 3600} h"
        secs < 3600 || secs % 60 != 0L -> "${(secs + 59) / 60} min"
        else -> "${secs / 3600} h ${secs % 3600 / 60} min"
    }

    /** dibs's check in one line: its pick and why, or that it hasn't looked yet. */
    fun checkWords(r: RootRequest): String = when {
        r.note == null -> "dibs hasn't checked this one"
        r.pick == "accept" -> "dibs would approve it: ${r.note}"
        r.pick == "reject" -> "dibs would deny it: ${r.note}"
        else -> "dibs: ${r.note}"
    }
}
