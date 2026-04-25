package com.emptycastle.novery.ui.screens.tagexplorer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emptycastle.novery.recommendation.TagNormalizer
import com.emptycastle.novery.ui.components.NovelDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================================================================
// Shimmer Effect Extension
// ============================================================================

fun Modifier.shimmerEffect(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnimation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
    )

    this.drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.linearGradient(
                colors = shimmerColors,
                start = Offset(translateAnimation - 500f, 0f),
                end = Offset(translateAnimation, 0f)
            ),
            blendMode = BlendMode.SrcAtop
        )
    }
}

// ============================================================================
// Tag Colors
// ============================================================================

private object TagColors {
    private val colorPalette = mapOf(
        TagNormalizer.TagCategory.ACTION to (Color(0xFFEF4444) to Color(0xFFF87171)),
        TagNormalizer.TagCategory.ADVENTURE to (Color(0xFFF97316) to Color(0xFFFB923C)),
        TagNormalizer.TagCategory.ROMANCE to (Color(0xFFEC4899) to Color(0xFFF472B6)),
        TagNormalizer.TagCategory.FANTASY to (Color(0xFF8B5CF6) to Color(0xFFA78BFA)),
        TagNormalizer.TagCategory.SCI_FI to (Color(0xFF06B6D4) to Color(0xFF22D3EE)),
        TagNormalizer.TagCategory.MYSTERY to (Color(0xFF6366F1) to Color(0xFF818CF8)),
        TagNormalizer.TagCategory.HORROR to (Color(0xFF7C3AED) to Color(0xFF9333EA)),
        TagNormalizer.TagCategory.COMEDY to (Color(0xFFF59E0B) to Color(0xFFFBBF24)),
        TagNormalizer.TagCategory.DRAMA to (Color(0xFF10B981) to Color(0xFF34D399)),
        TagNormalizer.TagCategory.MARTIAL_ARTS to (Color(0xFFDC2626) to Color(0xFFEF4444))
    )

    private val defaultColor = Color(0xFF6366F1) to Color(0xFF818CF8)

    fun getColors(tag: TagNormalizer.TagCategory?): Pair<Color, Color> {
        return tag?.let { colorPalette[it] } ?: defaultColor
    }

    fun getColor(tag: TagNormalizer.TagCategory?): Color = getColors(tag).first
}

// ============================================================================
// Main Tag Explorer Screen
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagExplorerScreen(
    tagName: String,
    onBack: () -> Unit,
    onNovelClick: (novelUrl: String, providerName: String) -> Unit,
    viewModel: TagExplorerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tagNovelsCount by viewModel.tagNovelsCount.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var showFilterSheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showTagSelector by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    val (primaryColor, secondaryColor) = remember(uiState.tag) {
        TagColors.getColors(uiState.tag)
    }

    // Load novels when tag changes
    LaunchedEffect(tagName) {
        val tagCategory = try {
            TagNormalizer.TagCategory.valueOf(tagName)
        } catch (e: Exception) {
            null
        }

        tagCategory?.let {
            viewModel.loadNovelsForTag(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // Clickable tag name to open selector
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showTagSelector = true
                            },
                            color = Color.Transparent,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = primaryColor.copy(alpha = 0.15f),
                                    modifier = Modifier.size(8.dp)
                                ) {}

                                Text(
                                    text = uiState.tagDisplayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )

                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = "Change tag",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        AnimatedContent(
                            targetState = uiState.isLoading to uiState.filteredNovels.size,
                            label = "novel_count",
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            }
                        ) { (loading, count) ->
                            if (!loading) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = "$count novel${if (count != 1) "s" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (uiState.hasActiveFilters) {
                                        Text(
                                            text = "•",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Filtered",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = primaryColor,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Filter button with badge
                    BadgedBox(
                        badge = {
                            if (uiState.hasActiveFilters) {
                                Badge(
                                    containerColor = primaryColor,
                                    modifier = Modifier.offset(x = (-8).dp, y = 8.dp)
                                )
                            }
                        }
                    ) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showFilterSheet = true
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (uiState.hasActiveFilters) {
                                    primaryColor.copy(alpha = 0.15f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FilterList,
                                contentDescription = "Filter",
                                tint = if (uiState.hasActiveFilters) {
                                    primaryColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    // Sort button
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showSortSheet = true
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sort,
                            contentDescription = "Sort"
                        )
                    }

                    // Display mode toggle
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.setDisplayMode(
                                if (uiState.displayMode == com.emptycastle.novery.domain.model.DisplayMode.GRID) {
                                    com.emptycastle.novery.domain.model.DisplayMode.LIST
                                } else {
                                    com.emptycastle.novery.domain.model.DisplayMode.GRID
                                }
                            )
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Icon(
                            imageVector = if (uiState.displayMode == com.emptycastle.novery.domain.model.DisplayMode.GRID) {
                                Icons.Rounded.ViewList
                            } else {
                                Icons.Rounded.GridView
                            },
                            contentDescription = "Toggle view"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    uiState.tag?.let { viewModel.loadNovelsForTag(it) }
                    delay(1000)
                    isRefreshing = false
                }
            },
            state = rememberPullToRefreshState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AnimatedContent(
                targetState = Triple(
                    uiState.isLoading,
                    uiState.error != null && uiState.novels.isEmpty(),
                    uiState.filteredNovels.isEmpty() && !uiState.isLoading
                ),
                label = "content_state",
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                }
            ) { (isLoading, hasError, isEmpty) ->
                when {
                    isLoading -> {
                        LoadingState(
                            tagName = uiState.tagDisplayName,
                            color = primaryColor,
                            displayMode = uiState.displayMode,
                            density = uiState.density
                        )
                    }

                    hasError -> {
                        ErrorState(
                            tagName = uiState.tagDisplayName,
                            message = uiState.error ?: "No novels found",
                            color = primaryColor,
                            onRetry = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                uiState.tag?.let { viewModel.loadNovelsForTag(it) }
                            }
                        )
                    }

                    isEmpty -> {
                        NoResultsState(
                            hasFilters = uiState.hasActiveFilters,
                            color = primaryColor,
                            onClearFilters = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.clearFilters()
                            }
                        )
                    }

                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Stats header
                            TagStatsHeader(
                                totalNovels = uiState.novels.size,
                                filteredNovels = uiState.filteredNovels.size,
                                providersCount = uiState.availableProviders.size,
                                color = primaryColor,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )

                            NovelDisplay(
                                items = uiState.filteredNovels.map { novel ->
                                    com.emptycastle.novery.ui.components.NovelGridItem(
                                        novel = novel,
                                        newChapterCount = 0,
                                        readingStatus = null,
                                        lastReadChapter = null
                                    )
                                },
                                onNovelClick = { novel ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onNovelClick(novel.url, novel.apiName)
                                },
                                displayMode = uiState.displayMode,
                                density = uiState.density,
                                showApiName = true,
                                contentPadding = PaddingValues(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Tag Selector Sheet
    if (showTagSelector) {
        TagSelectorSheet(
            currentTag = uiState.tag,
            tagNovelsCount = tagNovelsCount,
            onDismiss = { showTagSelector = false },
            onTagSelected = { newTag ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.loadNovelsForTag(newTag)
                showTagSelector = false
            }
        )
    }

    // Filter Bottom Sheet
    if (showFilterSheet) {
        TagFilterBottomSheet(
            uiState = uiState,
            color = primaryColor,
            onDismiss = { showFilterSheet = false },
            onClearFilters = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.clearFilters()
            },
            onMinRatingChange = { viewModel.setMinRating(it) },
            onToggleProvider = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.toggleProvider(it)
            },
            onClearProviders = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.clearProviderFilter()
            }
        )
    }

    // Sort Bottom Sheet
    if (showSortSheet) {
        SortBottomSheet(
            currentSort = uiState.sortBy,
            color = primaryColor,
            onDismiss = { showSortSheet = false },
            onSortSelected = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.setSortOption(it)
                showSortSheet = false
            }
        )
    }
}

// ============================================================================
// Tag Selector Sheet
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagSelectorSheet(
    currentTag: TagNormalizer.TagCategory?,
    tagNovelsCount: Map<TagNormalizer.TagCategory, Int>,
    onDismiss: () -> Unit,
    onTagSelected: (TagNormalizer.TagCategory) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current

    // Group tags by category type
    val tagsByType = remember(searchQuery, tagNovelsCount) {
        val allTags = TagNormalizer.TagCategory.entries
            .filter { tag ->
                val count = tagNovelsCount[tag] ?: 0
                count > 0 && (searchQuery.isBlank() ||
                        TagNormalizer.getDisplayName(tag).contains(searchQuery, ignoreCase = true))
            }
            .sortedByDescending { tagNovelsCount[it] ?: 0 }

        mapOf(
            "Top Tags" to allTags.take(10),
            "Genres" to allTags.filter { it in listOf(
                TagNormalizer.TagCategory.ACTION,
                TagNormalizer.TagCategory.ADVENTURE,
                TagNormalizer.TagCategory.ROMANCE,
                TagNormalizer.TagCategory.FANTASY,
                TagNormalizer.TagCategory.SCI_FI,
                TagNormalizer.TagCategory.MYSTERY,
                TagNormalizer.TagCategory.HORROR,
                TagNormalizer.TagCategory.COMEDY,
                TagNormalizer.TagCategory.DRAMA
            )},
            "All Tags" to allTags
        ).filter { it.value.isNotEmpty() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LocalOffer,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "Browse Tags",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${tagNovelsCount.size} tags available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search tags...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                singleLine = true
            )

            // Tag list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                tagsByType.forEach { (categoryName, tags) ->
                    item {
                        Text(
                            text = categoryName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    item {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            tags.forEach { tag ->
                                TagChip(
                                    tag = tag,
                                    count = tagNovelsCount[tag] ?: 0,
                                    isSelected = tag == currentTag,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onTagSelected(tag)
                                    }
                                )
                            }
                        }
                    }
                }

                if (tagsByType.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "No tags found",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagChip(
    tag: TagNormalizer.TagCategory,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = TagColors.getColor(tag)
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "tag_chip_scale"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            color.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) {
                color
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = color.copy(alpha = 0.2f),
                modifier = Modifier.size(6.dp)
            ) {}

            Text(
                text = TagNormalizer.getDisplayName(tag),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) color else MaterialTheme.colorScheme.onSurface
            )

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) {
                    color
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ) {
                Text(
                    text = count.toString(),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = color
                )
            }
        }
    }
}

// ============================================================================
// Tag Stats Header
// ============================================================================

@Composable
private fun TagStatsHeader(
    totalNovels: Int,
    filteredNovels: Int,
    providersCount: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatBadge(
            value = "$filteredNovels",
            label = if (totalNovels != filteredNovels) "of $totalNovels" else "novels",
            color = color,
            modifier = Modifier.weight(1f)
        )

        StatBadge(
            value = "$providersCount",
            label = "sources",
            color = MaterialTheme.colorScheme.secondaryContainer,
            textColor = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatBadge(
    value: String,
    label: String,
    color: Color,
    textColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// Loading State
// ============================================================================

@Composable
private fun LoadingState(
    tagName: String,
    color: Color,
    displayMode: com.emptycastle.novery.domain.model.DisplayMode,
    density: com.emptycastle.novery.domain.model.UiDensity
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Animated loading header
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 32.dp)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "loading")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "rotation"
                )

                Surface(
                    shape = CircleShape,
                    color = color.copy(alpha = 0.15f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = rotation },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = color
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Discovering $tagName novels...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Searching across all sources",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Shimmer skeleton grid
        com.emptycastle.novery.ui.components.NovelGridSkeleton(
            count = 12,
            displayMode = displayMode,
            density = density,
            contentPadding = PaddingValues(0.dp)
        )
    }
}

// ============================================================================
// Error State
// ============================================================================

@Composable
private fun ErrorState(
    tagName: String,
    message: String,
    color: Color,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "No novels found",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "We couldn't find any novels tagged with \"$tagName\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (message.isNotBlank() && message != "No novels found for this tag") {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Try Again",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ============================================================================
// No Results State
// ============================================================================

@Composable
private fun NoResultsState(
    hasFilters: Boolean,
    color: Color,
    onClearFilters: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = color.copy(alpha = 0.15f),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = if (hasFilters) {
                                Icons.Rounded.FilterAltOff
                            } else {
                                Icons.Rounded.SearchOff
                            },
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = color
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (hasFilters) "No matching novels" else "No results",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = if (hasFilters) {
                            "Try adjusting your filters to see more results"
                        } else {
                            "No novels match your current criteria"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                if (hasFilters) {
                    Button(
                        onClick = onClearFilters,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FilterAltOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Clear Filters",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Filter Bottom Sheet (existing code - no changes)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagFilterBottomSheet(
    uiState: TagExplorerUiState,
    color: Color,
    onDismiss: () -> Unit,
    onClearFilters: () -> Unit,
    onMinRatingChange: (Float) -> Unit,
    onToggleProvider: (String) -> Unit,
    onClearProviders: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = color.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = color
                            )
                        }
                    }

                    Text(
                        text = "Filters",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (uiState.hasActiveFilters) {
                    TextButton(onClick = onClearFilters) {
                        Text(
                            "Reset",
                            fontWeight = FontWeight.SemiBold,
                            color = color
                        )
                    }
                }
            }

            // Minimum Rating
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFFFBBF24)
                            )
                            Text(
                                text = "Minimum Rating",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (uiState.minRating > 0) {
                                color.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        ) {
                            Text(
                                text = if (uiState.minRating > 0) {
                                    "%.1f★".format(uiState.minRating)
                                } else {
                                    "Any"
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.minRating > 0) color else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Slider(
                        value = uiState.minRating,
                        onValueChange = onMinRatingChange,
                        valueRange = 0f..5f,
                        steps = 9,
                        colors = SliderDefaults.colors(
                            thumbColor = color,
                            activeTrackColor = color,
                            inactiveTrackColor = color.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            // Providers
            if (uiState.availableProviders.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Sources",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )

                            if (uiState.selectedProviders.isNotEmpty()) {
                                TextButton(
                                    onClick = onClearProviders,
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text(
                                        "Clear",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = color
                                    )
                                }
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.availableProviders.forEach { provider ->
                                val isSelected = provider in uiState.selectedProviders
                                val showSelected = isSelected || uiState.selectedProviders.isEmpty()

                                FilterChip(
                                    selected = showSelected,
                                    onClick = { onToggleProvider(provider) },
                                    label = {
                                        Text(
                                            provider,
                                            fontWeight = if (showSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = color.copy(alpha = 0.15f),
                                        selectedLabelColor = color
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = showSelected,
                                        borderColor = if (showSelected) color.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ============================================================================
// Sort Bottom Sheet (existing code - no changes)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBottomSheet(
    currentSort: SortOption,
    color: Color,
    onDismiss: () -> Unit,
    onSortSelected: (SortOption) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var isPressed by remember { mutableStateOf<SortOption?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = color.copy(alpha = 0.15f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sort,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = color
                        )
                    }
                }

                Text(
                    text = "Sort By",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            SortOption.entries.forEach { option ->
                val isSelected = option == currentSort
                val scale by animateFloatAsState(
                    targetValue = if (isPressed == option) 0.96f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    ),
                    label = "sort_scale"
                )

                Surface(
                    onClick = { onSortSelected(option) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isPressed = option
                                    tryAwaitRelease()
                                    isPressed = null
                                }
                            )
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) {
                        color.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    border = if (isSelected) {
                        BorderStroke(2.dp, color.copy(alpha = 0.3f))
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) color else MaterialTheme.colorScheme.onSurface
                        )

                        AnimatedVisibility(
                            visible = isSelected,
                            enter = fadeIn() + slideInHorizontally { it / 2 },
                            exit = fadeOut() + slideOutHorizontally { it / 2 }
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = color,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}