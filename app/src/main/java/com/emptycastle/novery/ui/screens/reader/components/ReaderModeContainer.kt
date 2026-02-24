package com.emptycastle.novery.ui.screens.reader.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.emptycastle.novery.domain.model.ScrollMode
import com.emptycastle.novery.ui.screens.reader.model.ReaderUiState
import com.emptycastle.novery.ui.screens.reader.theme.ReaderColors
import com.emptycastle.novery.ui.screens.reader.components.ReaderContainer

/**
 * Container that switches between continuous scroll and paged reading modes.
 */
@Composable
fun ReaderModeContainer(
    uiState: ReaderUiState,
    colors: ReaderColors,
    listState: LazyListState,
    onPageChanged: (chapterIndex: Int, chapterUrl: String, chapterName: String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onRetryChapter: (Int) -> Unit,
    onToggleControls: () -> Unit
) {
    // Always render continuous scroll reader (paged modes removed)
    ReaderContainer(
        uiState = uiState,
        colors = colors,
        listState = listState,
        onPrevious = onPrevious,
        onNext = onNext,
        onBack = onBack,
        onRetryChapter = onRetryChapter
    )
}