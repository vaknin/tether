package com.kivan.tether.dibs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space

// Every full story dibs keeps (opened from Recap's "Stories" row): newest first, a title and where and when it was
// written; a tap opens the task's story screen, whose Full story section reads it.

@Composable
internal fun StoriesPage(view: DibsView) {
    Column(Modifier.fillMaxSize()) {
        PageBar("Stories")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            items(view.recapStories, key = { "s${it.task}" }) { s ->
                Column(
                    Modifier.card().clip(MaterialTheme.shapes.medium)
                        .clickable(role = Role.Button, onClickLabel = "Open") { Dibs.open(Page.Brief(s.task, false)) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(s.title.ifBlank { "Task ${s.task}" }, style = AppType.heading, color = Palette.Text)
                    val whenText = if (s.ts > 0) whenWords(s.ts) else null
                    Text(listOfNotNull(s.project, whenText).joinToString(" · "), style = AppType.small, color = Palette.Muted)
                }
            }
            if (view.recapStories.isEmpty()) item(key = "_none") { Quiet("No full story has been written yet.") }
        }
    }
}
