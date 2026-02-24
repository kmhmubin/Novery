package com.emptycastle.novery.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emptycastle.novery.data.repository.RepositoryProvider
import com.emptycastle.novery.service.DownloadServiceManager
import com.emptycastle.novery.service.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadedNovel(
    val novelUrl: String,
    val novelName: String,
    val coverUrl: String?,
    val sourceName: String,
    val downloadedChapters: Int,
    val totalChapters: Int = 0,
    val lastDownloadedAt: Long = 0L  // Timestamp of most recent download
)

data class ActiveDownload(
    val novelUrl: String,
    val novelName: String,
    val coverUrl: String?,
    val currentChapterName: String,
    val downloadedCount: Int,
    val totalCount: Int,
    val progress: Float,
    val isPaused: Boolean = false,
    val speed: String = "",
    val eta: String = ""
)

enum class DownloadSortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST
}

data class DownloadsUiState(
    val isLoading: Boolean = true,
    val downloadedNovels: List<DownloadedNovel> = emptyList(),
    val activeDownloads: List<ActiveDownload> = emptyList(),
    val totalStorageUsed: String = "0 MB",
    val sortOrder: DownloadSortOrder = DownloadSortOrder.NEWEST_FIRST
)

class DownloadsViewModel : ViewModel() {

    private val offlineRepository = RepositoryProvider.getOfflineRepository()
    private val libraryRepository = RepositoryProvider.getLibraryRepository()

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    // Track previous download state for detecting completions
    private var previousActiveNovelUrl: String? = null
    private var wasDownloadActive: Boolean = false
    private var previousQueueSize: Int = 0

    // Cache unsorted novels for re-sorting without reloading
    private var cachedNovels: List<DownloadedNovel> = emptyList()

    init {
        observeActiveDownloads()
    }

    private fun observeActiveDownloads() {
        viewModelScope.launch {
            DownloadServiceManager.downloadState.collect { downloadState ->
                updateActiveDownloads(downloadState)
            }
        }
    }

    private fun updateActiveDownloads(downloadState: DownloadState) {
        val activeList = mutableListOf<ActiveDownload>()

        val isCurrentlyActive = downloadState.isActive || downloadState.isPaused
        val currentNovelUrl = downloadState.novelUrl.takeIf { it.isNotBlank() }
        val currentQueueSize = downloadState.queuedDownloads.size

        // Current download
        if (isCurrentlyActive) {
            activeList.add(
                ActiveDownload(
                    novelUrl = downloadState.novelUrl,
                    novelName = downloadState.novelName,
                    coverUrl = downloadState.novelCoverUrl,
                    currentChapterName = downloadState.currentChapterName,
                    downloadedCount = downloadState.currentProgress,
                    totalCount = downloadState.totalChapters,
                    progress = downloadState.progressPercent,
                    isPaused = downloadState.isPaused,
                    speed = downloadState.formattedSpeed,
                    eta = downloadState.estimatedTimeRemaining
                )
            )
        }

        // Queued downloads
        downloadState.queuedDownloads.forEach { queued ->
            activeList.add(
                ActiveDownload(
                    novelUrl = queued.novelUrl,
                    novelName = queued.novelName,
                    coverUrl = queued.novelCoverUrl,
                    currentChapterName = "Queued",
                    downloadedCount = 0,
                    totalCount = queued.chapterCount,
                    progress = 0f,
                    isPaused = false
                )
            )
        }

        _uiState.update { it.copy(activeDownloads = activeList) }

        // Detect completion scenarios that require refreshing the downloaded novels list
        val shouldRefresh = when {
            // Case 1: Download was active and is now complete (not active, not paused)
            wasDownloadActive && !isCurrentlyActive && !downloadState.isPaused -> true

            // Case 2: Active novel URL changed (previous download completed, new one started from queue)
            wasDownloadActive &&
                    isCurrentlyActive &&
                    previousActiveNovelUrl != null &&
                    currentNovelUrl != null &&
                    currentNovelUrl != previousActiveNovelUrl -> true

            // Case 3: Queue shrunk (a queued item was removed because it started or completed)
            currentQueueSize < previousQueueSize && wasDownloadActive -> true

            else -> false
        }

        if (shouldRefresh) {
            loadDownloads()
        }

        // Update tracking state for next comparison
        previousActiveNovelUrl = currentNovelUrl
        wasDownloadActive = isCurrentlyActive
        previousQueueSize = currentQueueSize
    }

    fun loadDownloads() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Get all novels with download counts and last download time
                val downloadInfo = offlineRepository.getAllDownloadInfo()

                val downloadedNovels = downloadInfo.mapNotNull { info ->
                    if (info.chapterCount <= 0) return@mapNotNull null

                    val novelUrl = info.novelUrl

                    // Try to get novel details from offline cache first
                    val offlineDetails = offlineRepository.getNovelDetails(novelUrl)

                    // If not in offline cache, try library
                    val libraryItem = libraryRepository.getLibraryItem(novelUrl)

                    // Get the best available info
                    val novelName = offlineDetails?.name
                        ?: libraryItem?.novel?.name
                        ?: extractNameFromUrl(novelUrl)

                    val coverUrl = offlineDetails?.posterUrl
                        ?: libraryItem?.novel?.posterUrl

                    val sourceName = libraryItem?.novel?.apiName
                        ?: extractSourceFromUrl(novelUrl)

                    DownloadedNovel(
                        novelUrl = novelUrl,
                        novelName = novelName,
                        coverUrl = coverUrl,
                        sourceName = sourceName,
                        downloadedChapters = info.chapterCount,
                        lastDownloadedAt = info.lastDownloadedAt
                    )
                }

                // Cache and sort
                cachedNovels = downloadedNovels
                val sortedNovels = sortNovels(downloadedNovels, _uiState.value.sortOrder)

                // Calculate approximate storage (rough estimate: ~10KB per chapter average)
                val totalChapters = downloadInfo.sumOf { it.chapterCount }
                val estimatedMB = (totalChapters * 10) / 1024.0
                val storageString = if (estimatedMB < 1) {
                    "${(estimatedMB * 1024).toInt()} KB"
                } else {
                    String.format("%.1f MB", estimatedMB)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        downloadedNovels = sortedNovels,
                        totalStorageUsed = storageString
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleSortOrder() {
        val newOrder = when (_uiState.value.sortOrder) {
            DownloadSortOrder.NEWEST_FIRST -> DownloadSortOrder.OLDEST_FIRST
            DownloadSortOrder.OLDEST_FIRST -> DownloadSortOrder.NEWEST_FIRST
        }

        val sortedNovels = sortNovels(cachedNovels, newOrder)

        _uiState.update {
            it.copy(
                sortOrder = newOrder,
                downloadedNovels = sortedNovels
            )
        }
    }

    private fun sortNovels(
        novels: List<DownloadedNovel>,
        order: DownloadSortOrder
    ): List<DownloadedNovel> {
        return when (order) {
            DownloadSortOrder.NEWEST_FIRST -> novels.sortedByDescending { it.lastDownloadedAt }
            DownloadSortOrder.OLDEST_FIRST -> novels.sortedBy { it.lastDownloadedAt }
        }
    }

    fun deleteNovelDownloads(novelUrl: String) {
        viewModelScope.launch {
            try {
                offlineRepository.deleteNovelDownloads(novelUrl)
                loadDownloads()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun pauseDownload() {
        DownloadServiceManager.pauseDownload()
    }

    fun resumeDownload() {
        DownloadServiceManager.resumeDownload()
    }

    fun cancelDownload() {
        DownloadServiceManager.cancelDownload()
        // Refresh immediately when cancelled to update the list
        loadDownloads()
    }

    fun removeFromQueue(novelUrl: String) {
        DownloadServiceManager.removeFromQueue(novelUrl)
    }

    private fun extractNameFromUrl(url: String): String {
        return try {
            url.substringAfterLast("/")
                .replace("-", " ")
                .replace("_", " ")
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                }
        } catch (e: Exception) {
            "Unknown Novel"
        }
    }

    private fun extractSourceFromUrl(url: String): String {
        return try {
            val host = url.removePrefix("https://").removePrefix("http://")
                .substringBefore("/")
                .removePrefix("www.")
            host.substringBefore(".").replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            ""
        }
    }
}