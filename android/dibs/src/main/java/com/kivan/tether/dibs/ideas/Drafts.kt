package com.kivan.tether.dibs.ideas

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.Link
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * A note or an addition made on the phone that dibs doesn't list yet (PLAN.md "Capture moves into
 * dibs"): what was typed, or a recording until Gemini has heard it. It goes to dibs as `idea-new`
 * (or `idea-add` when [note] names the note it adds to) once it is whole, and is dropped when the
 * payload lists it.
 */
data class Draft(
    /** The note's id (32 hex), or the addition's. */
    val id: String,
    /** The note it adds to; null for a new note. */
    val note: String?,
    val createdMs: Long,
    val typed: Boolean,
    /** What was typed, or what Gemini heard. */
    val text: String = "",
    val title: String = "",
    val summary: String = "",
    val durationMs: Long? = null,
    /** Its recording's file name in [Drafts.audioDir], until Gemini has heard it. */
    val audio: String? = null,
    /** Whether Gemini still has to title it (a recording, a long typed note, any addition). */
    val gemini: Boolean = false,
    /** Why it waits ("Network error: …"), or why it failed. */
    val why: String? = null,
    /** Failed for good: the row offers Retry and Delete. */
    val failed: Boolean = false,
    val attempts: Int = 0,
    /** Gemini's last status error, so a second identical one is terminal. */
    val lastError: String? = null,
    /** When it last went to dibs; null while it hasn't. */
    val sentMs: Long? = null,
    /** Its recording is still under way (the draft is written at the start, so a crash can't lose it). */
    val recording: Boolean = false,
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("note", note).put("created", createdMs).put("typed", typed)
        .put("text", text).put("title", title).put("summary", summary).put("duration_ms", durationMs)
        .put("audio", audio).put("gemini", gemini).put("why", why).put("failed", failed)
        .put("attempts", attempts).put("last_error", lastError).put("sent", sentMs).put("recording", recording)

    /** The action that sends it to dibs, and its value. */
    fun action(): Pair<String, JSONObject> {
        val created = Instant.ofEpochMilli(createdMs).toString()
        val v = JSONObject().put("id", id).put("created", created).put("title", title).put("summary", summary)
        durationMs?.let { v.put("duration_ms", it) }
        return if (note == null) "idea-new" to v.put("transcript", text) else "idea-add" to v.put("note", note).put("text", text)
    }

    companion object {
        fun parse(o: JSONObject): Draft? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            fun str(k: String) = if (o.isNull(k)) null else o.optString(k)
            fun long(k: String) = if (o.isNull(k) || !o.has(k)) null else o.optLong(k)
            return Draft(
                id = id, note = str("note"), createdMs = o.optLong("created"), typed = o.optBoolean("typed"),
                text = o.optString("text"), title = o.optString("title"), summary = o.optString("summary"),
                durationMs = long("duration_ms"), audio = str("audio"), gemini = o.optBoolean("gemini"),
                why = str("why"), failed = o.optBoolean("failed"), attempts = o.optInt("attempts"),
                lastError = str("last_error"), sentMs = long("sent"), recording = o.optBoolean("recording"),
            )
        }

        /** A new id, as Capture made them: 32 hex. */
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

/** The rules for a typed note, as Capture's (and dibs's `notes.rs`): a short one-liner is its own title. */
object TextNote {
    const val MAX_CHARS = 200
    const val SHORT_TITLE_MAX_CHARS = 60

    fun needsGemini(text: String): Boolean = text.length > MAX_CHARS || text.lineSequence().count() > 1

    fun shortTitle(text: String): String = text.trim().takeIf { it.length <= SHORT_TITLE_MAX_CHARS && it.isNotEmpty() } ?: titleFrom(text)

    fun titleFrom(text: String): String =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "-" }.take(8).joinToString(" ").ifEmpty { "Untitled" }
}

/**
 * The phone's drafts: one JSON file each in `files/dibs-ideas/drafts`, recordings in
 * `files/dibs-ideas/audio` until Gemini has heard them. [IdeaWorker] gets them heard; [send] hands a
 * whole one to Tether's queue, which resends until the laptop has it.
 */
object Drafts {
    private const val TAG = "dibs-ideas"

    /**
     * A draft sent this long ago that dibs still doesn't list goes again while the laptop is
     * reachable (dibs gave up on it, or it was lost).
     */
    const val RESEND_MS = 10 * 60_000L

    private val _list = MutableStateFlow<List<Draft>>(emptyList())
    val list: StateFlow<List<Draft>> = _list.asStateFlow()

    @Volatile private var root: File? = null

    /** The `ideas` of dibs's last view, as [seen] last wrote it to disk. */
    @Volatile private var lastRaw: String? = null

    /**
     * Reads the drafts once; every entry point calls it. A recording cut off (the app was killed,
     * the phone restarted) becomes a draft with what it got; only an empty one goes.
     */
    @Synchronized
    fun init(context: Context) {
        if (root != null) return
        val r = File(context.applicationContext.filesDir, "dibs-ideas")
        File(r, "drafts").mkdirs()
        root = r
        lastRaw = runCatching { File(r, LAST_IDEAS).readText() }.getOrNull()
        _list.value = File(r, "drafts").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { Draft.parse(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.createdMs }
        recover(context)
    }

    /** Takes in recordings cut off before they were handed over; not while one is under way (it'd be taken for one). */
    @Synchronized
    private fun recover(context: Context) {
        if (IdeaRecording.busy) return
        val dir = audioDir(context)
        for (d in _list.value.filter { it.recording }) {
            val f = d.audio?.let { File(dir, it) }
            if (f == null || f.length() <= 0L) {
                drop(context, d.id)
            } else {
                Log.i(TAG, "Recording ${f.name} was cut off; keeping what it got")
                put(d.copy(recording = false, durationMs = durationOf(f)))
                IdeaWorker.enqueue(context, d.id)
            }
        }
        // A recording no draft names (from before drafts were written at the start).
        val named = _list.value.mapNotNull { it.audio }.toSet()
        for (f in dir.listFiles().orEmpty().filter { it.name !in named }) {
            if (f.length() <= 0L) {
                f.delete()
                continue
            }
            val ms = durationOf(f)
            val d = Draft(Draft.newId(), null, f.lastModified() - (ms ?: 0), typed = false, durationMs = ms, audio = f.name, gemini = true)
            put(d)
            IdeaWorker.enqueue(context, d.id)
        }
    }

    /** A recording's length from its file, if Android can read it. */
    private fun durationOf(f: File): Long? = runCatching {
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(f.path)
            m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            m.release()
        }
    }.getOrNull()

    /** Puts every draft still waiting for Gemini back in WorkManager's hands (idempotent). */
    fun resume(context: Context) {
        init(context)
        recover(context)
        _list.value.filter { it.gemini && !it.failed && !it.recording }.forEach { IdeaWorker.enqueue(context, it.id) }
    }

    /**
     * dibs's last view of the Ideas tab: Tether's view while it has one, else the copy [seen] keeps
     * on disk (Tether loads its views only once its node runs, which a worker may not wait for).
     */
    fun lastIdeas(context: Context): Ideas? {
        init(context)
        val raw = hostIdeas()?.toString() ?: lastRaw ?: return null
        return runCatching { Ideas.parse(JSONObject(raw)) }.getOrNull()
    }

    private fun hostIdeas(): JSONObject? = runCatching { Dibs.host.view.value?.optJSONObject("dibs")?.optJSONObject("ideas") }.getOrNull()

    /** Forgets what [init] read, for a test's fresh files. */
    @Synchronized
    internal fun reset() {
        root = null
        lastRaw = null
        _list.value = emptyList()
    }

    fun audioDir(context: Context): File = File(context.applicationContext.filesDir, "dibs-ideas/audio").apply { mkdirs() }

    fun get(id: String): Draft? = _list.value.firstOrNull { it.id == id }

    @Synchronized
    fun put(d: Draft) {
        val r = root ?: return
        val f = File(r, "drafts/${d.id}.json")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(d.json().toString())
        tmp.renameTo(f)
        _list.value = (_list.value.filter { it.id != d.id } + d).sortedByDescending { it.createdMs }
    }

    @Synchronized
    fun drop(context: Context, id: String) {
        init(context)
        val d = get(id) ?: return
        IdeaWorker.cancel(context, id)
        d.audio?.let { File(audioDir(context), it).delete() }
        root?.let { File(it, "drafts/$id.json").delete() }
        _list.value = _list.value.filter { it.id != id }
    }

    /**
     * Typed on the phone ([note]: added to that note). A short new one-liner goes at once; the rest
     * are titled by Gemini first. Returns once it is safe on the phone (and, if it goes at once,
     * in Tether's queue).
     */
    suspend fun typed(context: Context, text: String, note: String? = null) {
        init(context)
        val t = text.trim()
        if (t.isEmpty()) return
        val gemini = note != null || TextNote.needsGemini(t)
        val d = Draft(Draft.newId(), note, System.currentTimeMillis(), typed = true, text = t, title = if (gemini) "" else TextNote.shortTitle(t), gemini = gemini)
        put(d)
        if (gemini) IdeaWorker.enqueue(context, d.id) else send(d)
    }

    /** A recording has started ([IdeaRecordingService]): its draft is written now, so it outlives a crash. */
    fun recording(context: Context, audio: File, createdMs: Long, note: String?) {
        init(context)
        put(Draft(Draft.newId(), note, createdMs, typed = false, audio = audio.name, gemini = true, recording = true))
    }

    /** A recording finished: it waits for Gemini. An empty one ([audio] is gone) leaves no draft. */
    fun recorded(context: Context, audio: File, createdMs: Long, durationMs: Long, note: String?) {
        init(context)
        val was = _list.value.firstOrNull { it.audio == audio.name }
        if (!audio.isFile || audio.length() <= 0L) {
            was?.let { drop(context, it.id) }
            return
        }
        val d = was?.copy(recording = false, durationMs = durationMs)
            ?: Draft(Draft.newId(), note, createdMs, typed = false, durationMs = durationMs, audio = audio.name, gemini = true)
        put(d)
        IdeaWorker.enqueue(context, d.id)
    }

    /**
     * Gemini's answer: the draft is whole, and goes to dibs. A blank [title] on an addition leaves
     * its note's title as it is; on a new note it is made from the note's first words.
     */
    suspend fun heard(context: Context, id: String, title: String, summary: String, transcript: String?) {
        val d = get(id) ?: return
        val whole = d.copy(
            title = if (d.note != null) title.trim() else title.ifBlank { TextNote.titleFrom(transcript ?: d.text) },
            summary = summary,
            text = if (d.typed) d.text else transcript.orEmpty(),
            audio = null, gemini = false, why = null, failed = false,
        )
        // The words are kept before the recording goes.
        put(whole)
        d.audio?.let { File(audioDir(context), it).delete() }
        send(whole)
    }

    /**
     * Hands a whole draft to Tether's queue (a fresh uid each time, so a resend isn't taken for the
     * same tap), and returns once it is stored there; Tether resends until the laptop has it.
     */
    suspend fun send(d: Draft) {
        if (d.gemini || d.failed) return
        // One send per draft at a time (a view's resend and the worker's first send may meet).
        if (!sending.add(d.id)) return
        val (action, value) = d.action()
        try {
            // Not cut short by the next view: once Tether has it, it is marked sent.
            withContext(NonCancellable) {
                Dibs.host.actStored(action, value)
                get(d.id)?.let { put(it.copy(sentMs = System.currentTimeMillis())) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sending draft ${d.id}: $e")
        } finally {
            sending.remove(d.id)
        }
    }

    private val sending: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Retry on a failed draft: Gemini gets it again from the start (or dibs, once it's whole). */
    suspend fun retry(context: Context, id: String) {
        val d = get(id) ?: return
        put(d.copy(failed = false, why = null, attempts = 0, lastError = null))
        if (d.gemini) IdeaWorker.restart(context, id) else send(get(id) ?: return)
    }

    /**
     * A new view: drafts it lists are dropped (a note by its id, an addition by its note's `adds`).
     * An addition whose note went to Trash, or is gone, can't land: it fails, keeping its words. One
     * never handed over goes now; one sent [RESEND_MS] ago and still not listed goes again while the
     * laptop is reachable (a view kept from before says nothing of what dibs has since). The view's
     * `ideas` is kept on disk for [lastIdeas].
     */
    suspend fun seen(context: Context, ideas: Ideas?, nowMs: Long = System.currentTimeMillis()) {
        init(context)
        hostIdeas()?.toString()?.takeIf { it != lastRaw }?.let { keep(it) }
        ideas ?: return
        val up = runCatching { Dibs.host.link.value == Link.CONNECTED }.getOrDefault(false)
        for (d in _list.value.filter { !it.gemini && !it.failed }) {
            val note = d.note?.let(ideas::note)
            val landed = if (d.note == null) d.id in ideas.known else note?.adds?.contains(d.id) == true
            val stuck = when {
                d.note == null || note != null -> null
                ideas.trash.any { it.id == d.note } -> "Its note is in Trash"
                // Not among the notes the view lists (they're the newest only), nor made here: gone.
                ideas.total <= ideas.notes.size && get(d.note) == null -> "Its note is gone"
                else -> null
            }
            when {
                landed -> drop(context, d.id)
                stuck != null -> put(d.copy(failed = true, why = stuck))
                d.sentMs == null -> send(d)
                up && nowMs - d.sentMs >= RESEND_MS -> send(d)
            }
        }
    }

    @Synchronized
    private fun keep(raw: String) {
        val r = root ?: return
        lastRaw = raw
        runCatching {
            val f = File(r, LAST_IDEAS)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(raw)
            tmp.renameTo(f)
        }.onFailure { Log.w(TAG, "Keeping the last view: $it") }
    }

    private const val LAST_IDEAS = "last-ideas.json"
}
