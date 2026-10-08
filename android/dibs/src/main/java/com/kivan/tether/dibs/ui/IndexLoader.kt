package com.kivan.tether.dibs.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.RefIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The index of tasks and ideas (docs/DIBS-APP.md, "Links"): dibs sends it as a channel file
// `index-<rev>.json.gz` when the view's `index_rev` changes; the newest one read stays in
// [Dibs.index] for every screen, and until one is read no text has links.

/** Reads the newest index file as it arrives, and asks dibs for one when [rev] has none yet. */
@Composable
internal fun IndexLoader(rev: String?) {
    val wanted by rememberUpdatedState(rev)
    // The name of the newest index file on the phone; null once the lookup found none.
    var have by remember { mutableStateOf<String?>(null) }
    var looked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Dibs.host.channelFile("index-").collect { f ->
            have = f?.name
            looked = true
            val read = f?.let { withContext(Dispatchers.IO) { runCatching { RefIndex.parse(readFetched(it)) }.getOrNull() } }
            if (read != null) Dibs.index = read
        }
    }
    // Not before the first lookup (a file for this rev may be there); a new rev asks again.
    LaunchedEffect(rev, have, looked) { if (looked) Dibs.wantIndex(wanted, have) }
}
