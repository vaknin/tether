package com.kivan.tether.dibs.ideas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kivan.tether.dibs.Dibs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gets one draft heard by Gemini (Capture's ProcessWorker, on [Drafts]): a recording, a long typed
 * note, or an addition. An addition goes up with its note's text so far, and its answer retitles
 * that note. One unique work per draft; WorkManager waits for a network and backs off between
 * failures that may pass. Drafts take turns through [IdeaGemini.gate], so a burst of them never
 * trips the free tier's rate limit.
 */
class IdeaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val id get() = inputData.getString(KEY_DRAFT).orEmpty()

    override suspend fun doWork(): Result = try {
        Drafts.init(applicationContext)
        // A long hold (a rate limit, or the daily quota) is left to WorkManager's backoff: a worker
        // waiting inside an app that has left the screen gets frozen with it.
        IdeaGemini.gate.withPermit(maxWaitMs = MAX_WAIT_IN_WORKER_MS) { inTurn() } ?: run {
            Log.i(TAG, "Draft $id: Gemini requests are on hold; retrying later")
            Result.retry()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Draft $id crashed", e)
        fail("Unexpected error: ${e.message ?: e.javaClass.simpleName}", terminal = false)
    }

    private suspend fun inTurn(): Result {
        val d = Drafts.get(id)?.takeIf { it.gemini && !it.failed && !it.recording } ?: return Result.success()
        Log.i(TAG, "Draft $id: ${if (d.typed) "typed" else "recorded"} ${if (d.note == null) "note" else "addition"} (try ${runAttemptCount + 1})")
        val audio = d.audio?.let { File(Drafts.audioDir(applicationContext), it) }?.takeIf { it.isFile }
        if (!d.typed && audio == null) return fail("Audio file missing", terminal = true)
        // An addition is titled with its note's text so far; without it, Gemini would retitle the
        // note from the addition alone, so it only transcribes and the note keeps its title.
        val base = d.note?.let { noteText(it) }
        val alone = d.note != null && base == null
        if (alone && d.typed) {
            Log.i(TAG, "Draft $id: its note's text isn't here; adding it as typed")
            Drafts.heard(applicationContext, id, "", "", null)
            return Result.success()
        }
        val result = when {
            d.typed && base == null -> IdeaGemini.text(applicationContext, d.text)
            // A typed addition goes up as the whole note with the new text at its end.
            d.typed -> IdeaGemini.text(applicationContext, wholeText(listOf(base!!, d.text)))
            base == null -> IdeaGemini.voice(applicationContext, audio!!, IdeaRecording.mimeType(audio))
            else -> IdeaGemini.append(applicationContext, base, audio!!, IdeaRecording.mimeType(audio))
        }
        return when (result) {
            is GeminiResult.Parsed -> heard(d, if (alone) result.result.copy(title = "", summary = "") else result.result)
            is GeminiResult.NotCompleted -> fail(statusError(result.status), isTerminalStatus(result.status, d.lastError))
            is GeminiResult.Failed -> {
                // A rate limit holds every draft back, and doesn't use up this one's attempts.
                result.retryAfterMs?.let {
                    Log.i(TAG, "Holding Gemini requests for ${it / 1000} s")
                    IdeaGemini.gate.holdFor(it)
                }
                fail(result.message, result.terminal, countsAsAttempt = result.retryAfterMs == null)
            }
        }
    }

    private suspend fun heard(d: Draft, r: CaptureResult): Result {
        if (!d.typed && r.transcript.isBlank()) {
            // As Capture: a recording in which nothing was heard makes no note.
            Log.i(TAG, "Draft $id: nothing was heard")
            Drafts.drop(applicationContext, id)
            notice(applicationContext, "Nothing was heard in that recording")
            return Result.success()
        }
        Drafts.heard(applicationContext, id, r.title, r.summary, if (d.typed) null else r.transcript)
        return Result.success()
    }

    /**
     * The note's text so far: dibs's view of it (or its loaded transcript, for a long one), else a
     * note made here that dibs doesn't list yet; then this phone's additions to it not listed yet.
     * Null when none of it is here.
     */
    private suspend fun noteText(note: String): String? {
        val listed = Drafts.lastIdeas(applicationContext)?.note(note)
        val own = Drafts.get(note)
        val base = listed?.transcript
            ?: listed?.let { loaded(it) }
            ?: own?.text?.takeIf { it.isNotBlank() }
            ?: return null
        val pending = Drafts.list.value
            .filter { it.note == note && it.id != id && !it.gemini && !(listed?.adds?.contains(it.id) ?: false) }
            .sortedBy { it.createdMs }
            .map { it.text }
        return wholeText(listOf(base) + pending)
    }

    /** A long note's whole text, if it was loaded and is as new as the view ([IdeaNote.loadPrefix]). */
    private suspend fun loaded(n: IdeaNote): String? = runCatching {
        withTimeoutOrNull(2_000) { Dibs.host.channelFile(n.loadPrefix).first() }?.readText()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Terminal errors and the last allowed attempt end the draft; anything else waits for
     * WorkManager's next try. As Capture: a typed note Gemini can't title goes as typed, titled
     * from its words, and a typed addition goes without retitling its note.
     */
    private suspend fun fail(error: String, terminal: Boolean, countsAsAttempt: Boolean = true): Result {
        val d = Drafts.get(id)?.takeIf { it.gemini && !it.failed } ?: return Result.success()
        val attempts = d.attempts + if (countsAsAttempt) 1 else 0
        val final = terminal || attempts >= GeminiConfig.MAX_ATTEMPTS
        Log.w(TAG, "Draft $id failed${if (final) " for good" else ", will retry"}: $error")
        return when {
            final && d.typed -> {
                Drafts.heard(applicationContext, id, "", "", null)
                Result.success()
            }
            final -> {
                Drafts.put(d.copy(attempts = attempts, lastError = error, why = error, failed = true))
                Result.failure()
            }
            else -> {
                Drafts.put(d.copy(attempts = attempts, lastError = error, why = error))
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "dibs-ideas"
        private const val KEY_DRAFT = "draft"

        /** Longer holds are not waited out inside the worker (see [doWork]). */
        private const val MAX_WAIT_IN_WORKER_MS = 90_000L

        private fun workName(id: String) = "dibs-idea-$id"

        /** As Capture's `Additions.wholeText`: the parts trimmed, the empty ones left out, a blank line between. */
        fun wholeText(parts: List<String>): String = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

        /** Idempotent: a draft that already has pending or running work keeps it. */
        fun enqueue(context: Context, id: String) = work(context) { enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request(id)) }

        /** A Retry tap: the draft's old work (failed, or waiting out a backoff) is replaced. */
        fun restart(context: Context, id: String) = work(context) { enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request(id)) }

        private fun request(id: String) = OneTimeWorkRequestBuilder<IdeaWorker>()
            .setInputData(workDataOf(KEY_DRAFT to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        fun cancel(context: Context, id: String) = work(context) { cancelUniqueWork(workName(id)) }

        /** WorkManager, if it's there (not in a unit test): a draft it missed is put back by [Drafts.resume]. */
        private fun work(context: Context, block: WorkManager.() -> Unit) {
            runCatching { WorkManager.getInstance(context.applicationContext).block() }.onFailure { Log.w(TAG, "WorkManager: $it") }
        }

        private fun notice(context: Context, text: String) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
        }
    }
}
