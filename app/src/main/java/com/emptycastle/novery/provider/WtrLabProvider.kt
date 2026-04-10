package com.emptycastle.novery.provider

import android.webkit.CookieManager
import com.emptycastle.novery.R
import com.emptycastle.novery.domain.model.Chapter
import com.emptycastle.novery.domain.model.FilterOption
import com.emptycastle.novery.domain.model.MainPageResult
import com.emptycastle.novery.domain.model.Novel
import com.emptycastle.novery.domain.model.NovelDetails
import com.emptycastle.novery.util.RatingUtils
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provider for wtr-lab.com
 *
 * Features:
 * - AI and Web translation support
 * - AES-GCM encrypted content decryption
 * - Glossary term replacement (story, chapter, user)
 * - Cookie-based authentication
 * - Aggressive rate limiting (12+ seconds)
 *
 * Authentication:
 * - AI translation requires login for unlimited access
 * - ~10 free AI chapters for guests
 * - Users must log in via WebView to unlock full access
 */
class WtrLabProvider : MainProvider() {

    override val name = "WTR-LAB"
    override val mainUrl = "https://wtr-lab.com"
    override val hasMainPage = true
    override val hasReviews = false
    override val iconRes: Int = R.drawable.ic_provider_wtrlab
    override val rateLimitTime: Long = 12000L
    override val ratingScale: RatingScale = RatingScale.FIVE_STAR

    private val lang = "en"
    private var decryptionKey: String? = null
    private var cachedBuildId: String? = null

    // Glossary terms cache
    private data class GlossaryTerm(val from: String, val to: String)
    private val userTermsCache = mutableMapOf<String, List<GlossaryTerm>>()
    private val storyTermsCache = mutableMapOf<String, List<GlossaryTerm>>()

    companion object {
        private const val BATCH_SIZE = 250

        private object Endpoints {
            const val API_CHAPTERS = "/api/chapters"
            const val API_READER_GET = "/api/reader/get"
            const val API_USER_CONFIG = "/api/v2/user/config"
            const val API_READER_TERMS = "/api/v2/reader/terms"
            const val NOVEL_FINDER = "/en/novel-finder"
        }

        private object Status {
            const val ONGOING = 0
            const val COMPLETED = 1
            const val HIATUS = 2
            const val DROPPED = 3
        }

        private object ErrorCodes {
            const val CHAPTER_LOCKED = "CHAPTER_LOCKED"
            const val TURNSTILE_REQUIRED = 1401
            const val UNAUTHORIZED = 401
            const val FORBIDDEN = 403
        }
    }

    // ================================================================
    // ENHANCED FILTER OPTIONS
    // ================================================================

    override val orderBys = listOf(
        FilterOption("Update Date", "update"),
        FilterOption("Addition Date", "date"),
        FilterOption("Random", "random"),
        FilterOption("Weekly View", "weekly_rank"),
        FilterOption("Monthly View", "monthly_rank"),
        FilterOption("All-Time View", "view"),
        FilterOption("Name", "name"),
        FilterOption("Reader", "reader"),
        FilterOption("Chapter Count", "chapter"),
        FilterOption("Rating", "rating"),
        FilterOption("Review Count", "total_rate"),
        FilterOption("Vote Count", "vote")
    )

    val orderDirections = listOf(
        FilterOption("Descending", "desc"),
        FilterOption("Ascending", "asc")
    )

    val statusFilters = listOf(
        FilterOption("All", "all"),
        FilterOption("Ongoing", "ongoing"),
        FilterOption("Completed", "completed"),
        FilterOption("Hiatus", "hiatus"),
        FilterOption("Dropped", "dropped")
    )

    val releaseStatusFilters = listOf(
        FilterOption("All", "all"),
        FilterOption("Released", "released"),
        FilterOption("On Voting", "voting")
    )

    val additionAgeFilters = listOf(
        FilterOption("All", "all"),
        FilterOption("< 2 Days", "day"),
        FilterOption("< 1 Week", "week"),
        FilterOption("< 1 Month", "month")
    )

    override val tags = listOf(
        FilterOption("Male Protagonist", "417"),
        FilterOption("Female Protagonist", "275"),
        FilterOption("Transmigration", "717"),
        FilterOption("System", "696"),
        FilterOption("Cultivation", "169"),
        FilterOption("Reincarnation", "578"),
        FilterOption("Fantasy World", "265"),
        FilterOption("Overpowered Protagonist", "506"),
        FilterOption("Weak to Strong", "750"),
        FilterOption("Harem-seeking Protagonist", "329"),
        FilterOption("Romance", "592"),
        FilterOption("Action", "1"),
        FilterOption("Adventure", "2"),
        FilterOption("Comedy", "3"),
        FilterOption("Drama", "4"),
        FilterOption("Fantasy", "5"),
        FilterOption("Harem", "6"),
        FilterOption("Horror", "7"),
        FilterOption("Martial Arts", "426"),
        FilterOption("Mystery", "471"),
        FilterOption("Psychological", "562"),
        FilterOption("School Life", "684"),
        FilterOption("Sci-fi", "13"),
        FilterOption("Seinen", "14"),
        FilterOption("Shounen", "15"),
        FilterOption("Slice of Life", "16"),
        FilterOption("Supernatural", "17"),
        FilterOption("Tragedy", "18"),
        FilterOption("Wuxia", "19"),
        FilterOption("Xianxia", "20"),
        FilterOption("Xuanhuan", "21"),
        FilterOption("Game Elements", "297"),
        FilterOption("Kingdom Building", "379"),
        FilterOption("Time Travel", "710"),
        FilterOption("Apocalypse", "47"),
        FilterOption("Survival", "692"),
        FilterOption("Modern Day", "446"),
        FilterOption("Magic", "410"),
        FilterOption("Fanfiction", "263"),
        FilterOption("Naruto", "769"),
        FilterOption("Marvel", "766"),
        FilterOption("One Piece", "767"),
        FilterOption("Harry Potter", "768"),
        FilterOption("Pokemon", "771"),
        FilterOption("Douluo Dalu", "772")
    )

    // ================================================================
    // COOKIE & AUTHENTICATION MANAGEMENT
    // ================================================================

    private fun getCookiesForDomain(): String? {
        return try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.getCookie(mainUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun isAuthenticated(): Boolean {
        val cookies = getCookiesForDomain() ?: return false
        return cookies.contains("token") ||
                cookies.contains("session") ||
                cookies.contains("user_id") ||
                cookies.contains("auth") ||
                cookies.contains("remember")
    }

    private fun buildHeadersWithCookies(
        additionalHeaders: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers.putAll(additionalHeaders)

        getCookiesForDomain()?.let { cookies ->
            headers["Cookie"] = cookies
        }

        return headers
    }

    // ================================================================
    // UTILITY METHODS
    // ================================================================

    private fun extractNextData(document: Document): JSONObject? {
        val script = document.selectFirstOrNull("script#__NEXT_DATA__")
        val jsonText = script?.data() ?: return null
        return try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractBuildId(nextData: JSONObject): String? {
        return nextData.optString("buildId", null)?.takeIf { it.isNotBlank() }
    }

    private fun parseStatus(status: Int?): String? {
        return when (status) {
            Status.ONGOING -> "Ongoing"
            Status.COMPLETED -> "Completed"
            Status.HIATUS -> "Hiatus"
            Status.DROPPED -> "Dropped"
            else -> null
        }
    }

    private suspend fun getBuildId(): String {
        cachedBuildId?.let { return it }

        val response = get("$mainUrl${Endpoints.NOVEL_FINDER}", buildHeadersWithCookies())
        val nextData = extractNextData(response.document)
        val buildId = nextData?.let { extractBuildId(it) }
            ?: throw Exception("Could not extract buildId from page")

        cachedBuildId = buildId
        return buildId
    }

    /**
     * Build URL with comprehensive filter support
     */
    private fun buildBrowseUrl(
        page: Int,
        orderBy: String? = null,
        tag: String? = null,
        status: String = "all",
        releaseStatus: String = "all",
        additionAge: String = "all"
    ): String {
        val params = buildList {
            add("orderBy=${orderBy ?: "update"}")
            add("order=desc")
            add("status=$status")
            add("release_status=$releaseStatus")
            add("addition_age=$additionAge")
            add("page=$page")

            if (!tag.isNullOrEmpty()) {
                add("gi=$tag")
                add("gc=or")
            }
        }

        return "$mainUrl${Endpoints.NOVEL_FINDER}.json?${params.joinToString("&")}"
    }

    // ================================================================
    // DECRYPTION
    // ================================================================

    /**
     * Extract decryption key from site's JavaScript files
     */
    private suspend fun getDecryptionKey(document: Document): String {
        decryptionKey?.let { return it }

        try {
            val searchPattern = "TextEncoder().encode(\""
            val scripts = document.select("head script[src]")

            for (script in scripts) {
                val src = script.attr("src")
                if (src.isBlank()) continue

                val scriptUrl = when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "$mainUrl$src"
                    else -> "$mainUrl/$src"
                }

                try {
                    val scriptContent = get(scriptUrl, buildHeadersWithCookies()).text

                    val keyIndex = scriptContent.indexOf(searchPattern)
                    if (keyIndex >= 0) {
                        val keyStart = keyIndex + searchPattern.length
                        if (keyStart + 32 <= scriptContent.length) {
                            val extractedKey = scriptContent.substring(keyStart, keyStart + 32)
                            decryptionKey = extractedKey
                            return extractedKey
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to known key (may be outdated)
        return "IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru"
    }

    /**
     * Decrypt AES-GCM encrypted content
     * Format: [arr:|str:]base64(iv):base64(shortCipher):base64(longCipher)
     */
    private fun decryptContent(encryptedText: String, key: String): List<String> {
        if (encryptedText.isBlank()) return emptyList()

        var isArray = false
        var rawText = encryptedText

        when {
            encryptedText.startsWith("arr:") -> {
                isArray = true
                rawText = encryptedText.removePrefix("arr:")
            }
            encryptedText.startsWith("str:") -> {
                rawText = encryptedText.removePrefix("str:")
            }
        }

        val parts = rawText.split(":")
        if (parts.size != 3) {
            // Not encrypted or invalid format
            return listOf(encryptedText)
        }

        return try {
            val ivBytes = Base64.getDecoder().decode(parts[0])
            val shortCipher = Base64.getDecoder().decode(parts[1])
            val longCipher = Base64.getDecoder().decode(parts[2])

            // Combine: longCipher + shortCipher
            val cipherBytes = ByteArray(longCipher.size + shortCipher.size)
            System.arraycopy(longCipher, 0, cipherBytes, 0, longCipher.size)
            System.arraycopy(shortCipher, 0, cipherBytes, longCipher.size, shortCipher.size)

            val keyBytes = key.substring(0, 32).toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val decryptedBytes = cipher.doFinal(cipherBytes)
            val decryptedText = decryptedBytes.toString(Charsets.UTF_8)

            if (isArray) {
                val jsonArray = JSONArray(decryptedText)
                (0 until jsonArray.length()).map { jsonArray.getString(it) }
            } else {
                listOf(decryptedText)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            listOf(encryptedText)
        }
    }

    // ================================================================
    // GLOSSARY & TERMS MANAGEMENT
    // ================================================================

    /**
     * Fetch user-configured glossary terms
     */
    private suspend fun getUserTerms(serieId: String): List<GlossaryTerm> {
        userTermsCache[serieId]?.let { return it }

        return try {
            val response = get("$mainUrl${Endpoints.API_USER_CONFIG}", buildHeadersWithCookies(
                mapOf("Accept" to "application/json")
            ))

            val json = JSONObject(response.text)
            val config = json.optJSONObject("config")
            val termsArray = config?.optJSONArray("terms")

            val terms = mutableListOf<GlossaryTerm>()

            if (termsArray != null) {
                for (i in 0 until termsArray.length()) {
                    val termArray = termsArray.optJSONArray(i) ?: continue

                    // Filter terms that apply to this serie (or all series)
                    val applicableSeries = termArray.optJSONArray(4)
                    val appliesToThisSerie = applicableSeries == null ||
                            (0 until applicableSeries.length()).any {
                                applicableSeries.optString(it) == serieId
                            }

                    if (!appliesToThisSerie) continue

                    val to = termArray.optString(1, null)?.takeIf { it.isNotBlank() } ?: continue
                    val fromString = termArray.optString(2, null)?.takeIf { it.isNotBlank() } ?: continue

                    // Split by | to get multiple "from" values
                    val fromList = fromString.split("|").filter { it.isNotBlank() }

                    fromList.forEach { from ->
                        terms.add(GlossaryTerm(from = from, to = to))
                    }
                }
            }

            userTermsCache[serieId] = terms
            terms
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Fetch story-level glossary terms
     */
    private suspend fun getStoryTerms(rawId: String): List<GlossaryTerm> {
        storyTermsCache[rawId]?.let { return it }

        return try {
            val response = get(
                "$mainUrl${Endpoints.API_READER_TERMS}/$rawId.json",
                buildHeadersWithCookies(mapOf("Accept" to "application/json"))
            )

            val json = JSONObject(response.text)
            val glossaries = json.optJSONArray("glossaries")

            val termsMap = mutableMapOf<String, String>()

            if (glossaries != null) {
                for (i in 0 until glossaries.length()) {
                    val glossary = glossaries.optJSONObject(i) ?: continue
                    val data = glossary.optJSONObject("data") ?: continue
                    val termsArray = data.optJSONArray("terms") ?: continue

                    for (j in 0 until termsArray.length()) {
                        val term = termsArray.optJSONArray(j) ?: continue
                        if (term.length() < 2) continue

                        val toArray = term.optJSONArray(0)
                        val from = term.optString(1, null)?.takeIf { it.isNotBlank() } ?: continue
                        val to = toArray?.optString(0, null)?.takeIf { it.isNotBlank() } ?: continue

                        termsMap[from] = to
                    }
                }
            }

            val terms = termsMap.map { GlossaryTerm(from = it.key, to = it.value) }
            storyTermsCache[rawId] = terms
            terms
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Apply glossary term replacements with priority:
     * 1. User terms (highest priority)
     * 2. Story terms
     * 3. Chapter terms
     */
    private fun applyGlossaryTerms(
        text: String,
        chapterTerms: Map<String, String>,
        storyTerms: List<GlossaryTerm>,
        userTerms: List<GlossaryTerm>
    ): String {
        var result = text

        // First apply chapter terms (with story and user overrides)
        for ((marker, chapterReplacement) in chapterTerms) {
            var finalReplacement = chapterReplacement

            // Check if story term overrides this
            storyTerms.find { it.from == chapterReplacement }?.let {
                finalReplacement = it.to
            }

            // Check if user term overrides this
            userTerms.find { it.from == chapterReplacement }?.let {
                finalReplacement = it.to
            }

            result = result.replace(marker, finalReplacement)
        }

        // Then apply user custom terms directly
        for (term in userTerms) {
            result = result.replace(term.from, term.to)
        }

        return result
    }

    /**
     * Apply patch replacements (zh -> en)
     */
    private fun applyPatches(text: String, patches: JSONArray?): String {
        if (patches == null) return text

        var result = text
        for (i in 0 until patches.length()) {
            val patch = patches.optJSONObject(i) ?: continue
            val zh = patch.optString("zh", null)?.takeIf { it.isNotBlank() } ?: continue
            val en = patch.optString("en", null)?.takeIf { it.isNotBlank() } ?: continue

            result = result.replace(zh, " $en")
        }

        return result
    }

    // ================================================================
    // NOVEL PARSING
    // ================================================================

    private fun parseNovelFromJson(seriesObj: JSONObject): Novel? {
        val rawId = seriesObj.optLong("raw_id", 0)
        if (rawId == 0L) return null

        val slug = seriesObj.optString("slug", "")
        val data = seriesObj.optJSONObject("data") ?: return null

        val title = data.optString("title", "").takeIf { it.isNotBlank() } ?: return null
        val image = data.optString("image", null)?.takeIf { it.isNotBlank() }

        val novelUrl = "/$lang/serie-$rawId/$slug"

        val chapterCount = seriesObj.optInt("chapter_count", 0)
        val latestChapter = if (chapterCount > 0) "$chapterCount Chapters" else null

        val rating = seriesObj.optDouble("rating", Double.NaN)
            .takeIf { !it.isNaN() && it > 0 }
            ?.let { RatingUtils.from5Stars(it.toFloat()) }

        return Novel(
            name = title,
            url = fixUrl(novelUrl) ?: return null,
            posterUrl = image,
            latestChapter = latestChapter,
            rating = rating,
            apiName = this.name
        )
    }

    // ================================================================
    // MAIN PAGE
    // ================================================================

    override suspend fun loadMainPage(
        page: Int,
        orderBy: String?,
        tag: String?
    ): MainPageResult {
        val buildId = getBuildId()

        val params = buildList {
            add("orderBy=${orderBy ?: "update"}")
            add("order=desc")
            add("status=all")
            add("release_status=all")
            add("addition_age=all")
            add("page=$page")

            if (!tag.isNullOrEmpty()) {
                add("gi=$tag")
                add("gc=or")
            }
        }

        val queryString = params.joinToString("&")
        val url = "$mainUrl/_next/data/$buildId/$lang/novel-finder.json?$queryString"

        val response = get(url, buildHeadersWithCookies(
            mapOf("Accept" to "application/json, text/plain, */*")
        ))

        val json = JSONObject(response.text)
        val pageProps = json.optJSONObject("pageProps")
            ?: return MainPageResult(url, emptyList())

        val seriesArray = pageProps.optJSONArray("series")
            ?: return MainPageResult(url, emptyList())

        val novels = mutableListOf<Novel>()
        val seenIds = mutableSetOf<Long>()

        for (i in 0 until seriesArray.length()) {
            val seriesObj = seriesArray.getJSONObject(i)
            val rawId = seriesObj.optLong("raw_id", 0)

            if (rawId != 0L && seenIds.add(rawId)) {
                parseNovelFromJson(seriesObj)?.let { novels.add(it) }
            }
        }

        return MainPageResult(url = url, novels = novels)
    }

    // ================================================================
    // SEARCH
    // ================================================================

    override suspend fun search(query: String): List<Novel> {
        val buildId = getBuildId()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/_next/data/$buildId/$lang/novel-finder.json?text=$encodedQuery"

        val response = get(url, buildHeadersWithCookies(
            mapOf("Accept" to "application/json, text/plain, */*")
        ))

        val json = JSONObject(response.text)
        val pageProps = json.optJSONObject("pageProps") ?: return emptyList()
        val seriesArray = pageProps.optJSONArray("series") ?: return emptyList()

        val novels = mutableListOf<Novel>()
        val seenIds = mutableSetOf<Long>()

        for (i in 0 until seriesArray.length()) {
            val seriesObj = seriesArray.getJSONObject(i)
            val rawId = seriesObj.optLong("raw_id", 0)

            if (rawId != 0L && seenIds.add(rawId)) {
                parseNovelFromJson(seriesObj)?.let { novels.add(it) }
            }
        }

        return novels
    }

    // ================================================================
    // LOAD NOVEL DETAILS
    // ================================================================

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"

        val response = get(fullUrl, buildHeadersWithCookies())
        val document = response.document

        val nextData = extractNextData(document) ?: return null
        val props = nextData.optJSONObject("props") ?: return null
        val pageProps = props.optJSONObject("pageProps") ?: return null
        val serie = pageProps.optJSONObject("serie") ?: return null
        val serieData = serie.optJSONObject("serie_data") ?: return null

        val rawId = serieData.optLong("raw_id", 0)
        if (rawId == 0L) return null

        val slug = serieData.optString("slug", "")
        val data = serieData.optJSONObject("data")

        val title = data?.optString("title")?.takeIf { it.isNotBlank() }
            ?: document.selectFirstOrNull("h1.text-uppercase")?.text()?.trim()
            ?: document.selectFirstOrNull("h1.long-title")?.text()?.trim()
            ?: return null

        val author = data?.optString("author")?.takeIf { it.isNotBlank() }
        val description = data?.optString("description")?.takeIf { it.isNotBlank() }
        val image = data?.optString("image")?.takeIf { it.isNotBlank() }

        val status = parseStatus(serieData.optInt("status", -1))
        val rawChapterCount = serieData.optLong("raw_chapter_count", 0)

        val rating = serieData.optDouble("rating", Double.NaN)
            .takeIf { !it.isNaN() && it > 0 }
            ?.let { RatingUtils.from5Stars(it.toFloat()) }

        val peopleVoted = serieData.optInt("total_rate", 0).takeIf { it > 0 }
        val views = serieData.optInt("view", 0).takeIf { it > 0 }

        // Extract tags
        val tags = mutableListOf<String>()
        val tagsArray = pageProps.optJSONArray("tags")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tagObj = tagsArray.optJSONObject(i)
                val tagTitle = tagObj?.optString("title")
                if (!tagTitle.isNullOrBlank()) {
                    tags.add(tagTitle)
                }
            }
        }

        // Load chapters
        val chapters = loadChapters(rawId, rawChapterCount, slug)

        // Parse related novels
        val recommendations = serie.optJSONArray("recommendation")
        val relatedNovels = if (recommendations != null) {
            (0 until recommendations.length()).mapNotNull { i ->
                val recObj = recommendations.getJSONObject(i)
                parseNovelFromJson(recObj)
            }
        } else {
            emptyList()
        }

        val otherSeries = serie.optJSONArray("other_series")
        val otherNovels = if (otherSeries != null) {
            (0 until otherSeries.length()).mapNotNull { i ->
                val seriesObj = otherSeries.getJSONObject(i)
                parseNovelFromJson(seriesObj)
            }
        } else {
            emptyList()
        }

        val allRelated = (relatedNovels + otherNovels).distinctBy { it.url }

        return NovelDetails(
            url = fullUrl,
            name = title,
            chapters = chapters,
            author = author,
            posterUrl = image,
            synopsis = description ?: "No description available.",
            tags = tags.ifEmpty { null },
            rating = rating,
            peopleVoted = peopleVoted,
            status = status,
            views = views,
            relatedNovels = allRelated.ifEmpty { null }
        )
    }

    /**
     * Load chapters from API in batches
     */
    private suspend fun loadChapters(
        rawId: Long,
        totalChapters: Long,
        slug: String
    ): List<Chapter> {
        if (totalChapters <= 0) return emptyList()

        val chapters = mutableListOf<Chapter>()
        var start = 1L

        while (start <= totalChapters) {
            val end = minOf(start + BATCH_SIZE - 1, totalChapters)

            try {
                val url = "$mainUrl${Endpoints.API_CHAPTERS}/$rawId?start=$start&end=$end"
                val response = get(url, buildHeadersWithCookies(
                    mapOf("Accept" to "application/json")
                ))

                val json = JSONObject(response.text)
                val chaptersArray = json.optJSONArray("chapters")

                if (chaptersArray == null || chaptersArray.length() == 0) break

                for (i in 0 until chaptersArray.length()) {
                    val chapterObj = chaptersArray.getJSONObject(i)
                    val order = chapterObj.optLong("order", 0)
                    val title = chapterObj.optString("title", "").trim()
                    val updatedAt = chapterObj.optString("updated_at", null)

                    val chapterName = if (title.isNotBlank()) {
                        "#$order: $title"
                    } else {
                        "Chapter $order"
                    }

                    val chapterUrl = "/$lang/serie-$rawId/$slug/chapter-$order"

                    chapters.add(
                        Chapter(
                            name = chapterName,
                            url = fixUrl(chapterUrl) ?: continue,
                            dateOfRelease = updatedAt?.take(10)
                        )
                    )
                }

                if (chaptersArray.length() < BATCH_SIZE) break
                start += BATCH_SIZE

            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }

        return chapters.sortedBy { chapter ->
            Regex("chapter-(\\d+)").find(chapter.url)
                ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
        }
    }

    // ================================================================
    // LOAD CHAPTER CONTENT
    // ================================================================

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"

        // Extract rawId and chapterNo from URL
        val regex = Regex("/serie-(\\d+)/[^/]+/chapter-(\\d+)")
        val match = regex.find(fullUrl)
            ?: throw Exception("Invalid chapter URL format: $fullUrl")

        val rawId = match.groupValues[1]
        val chapterNo = match.groupValues[2]

        // Fetch glossary terms
        val userTerms = getUserTerms(rawId)
        val storyTerms = getStoryTerms(rawId)

        // Check authentication
        val hasAuth = isAuthenticated()

        // Translation services priority
        val services = if (hasAuth) {
            listOf("ai", "web")
        } else {
            listOf("web", "ai")
        }

        var lastError: String? = null
        var pageDocument: Document? = null
        var usedService: String? = null

        for (service in services) {
            try {
                val jsonBody = JSONObject().apply {
                    put("translate", service)
                    put("language", lang)
                    put("raw_id", rawId.toLong())
                    put("chapter_no", chapterNo.toLong())
                    put("retry", false)
                    put("force_retry", false)
                }

                val response = postJson(
                    url = "$mainUrl${Endpoints.API_READER_GET}",
                    json = jsonBody.toString(),
                    headers = buildHeadersWithCookies(
                        mapOf(
                            "Accept" to "application/json",
                            "Referer" to fullUrl,
                            "Origin" to mainUrl
                        )
                    )
                )

                val json = JSONObject(response.text)

                // Check for Turnstile (Cloudflare challenge)
                if (json.optBoolean("requireTurnstile", false) ||
                    json.optInt("code", 0) == ErrorCodes.TURNSTILE_REQUIRED) {
                    throw Exception("Cloudflare verification required. Please open in WebView.")
                }

                // Check for chapter locked
                if (json.optString("code", null) == ErrorCodes.CHAPTER_LOCKED) {
                    lastError = "Chapter is locked or not AI translated yet."
                    continue
                }

                // Check for auth errors
                val code = json.optInt("code", 0)
                if (code == ErrorCodes.UNAUTHORIZED || code == ErrorCodes.FORBIDDEN) {
                    lastError = "Authentication required for AI translation."
                    continue
                }

                // Check success
                if (!json.optBoolean("success", true)) {
                    val message = json.optString("message", "Unknown error")
                    if (message.contains("limit", ignoreCase = true) ||
                        message.contains("auth", ignoreCase = true) ||
                        message.contains("login", ignoreCase = true) ||
                        message.contains("quota", ignoreCase = true)) {
                        lastError = message
                        continue
                    }
                    lastError = message
                    continue
                }

                val dataObj = json.optJSONObject("data")?.optJSONObject("data")
                if (dataObj == null) {
                    lastError = "No content data in response"
                    continue
                }

                val bodyContent = dataObj.opt("body")
                if (bodyContent == null) {
                    lastError = "No body content"
                    continue
                }

                // Success!
                usedService = service

                // Parse body content (may be encrypted)
                val paragraphs: List<String> = when (bodyContent) {
                    is String -> {
                        if (bodyContent.startsWith("arr:") || bodyContent.startsWith("str:")) {
                            if (pageDocument == null) {
                                pageDocument = get(fullUrl, buildHeadersWithCookies()).document
                            }
                            val key = getDecryptionKey(pageDocument!!)
                            decryptContent(bodyContent, key)
                        } else {
                            listOf(bodyContent)
                        }
                    }
                    is JSONArray -> {
                        (0 until bodyContent.length()).map { bodyContent.getString(it) }
                    }
                    else -> {
                        lastError = "Unexpected body content type"
                        continue
                    }
                }

                // Parse chapter glossary terms
                val chapterTerms = mutableMapOf<String, String>()
                val glossaryData = dataObj.optJSONObject("glossary_data")
                val termsArray = glossaryData?.optJSONArray("terms")

                if (termsArray != null) {
                    for (i in 0 until termsArray.length()) {
                        val term = termsArray.optJSONArray(i) ?: continue
                        val replacement = term.optString(0, null)?.takeIf { it.isNotBlank() }
                        if (replacement != null) {
                            chapterTerms["※${i}⛬"] = replacement
                            chapterTerms["※${i}〓"] = replacement
                        }
                    }
                }

                // Get patches
                val patchArray = dataObj.optJSONArray("patch")

                // Build HTML content
                return buildChapterHtml(
                    chapterNo = chapterNo,
                    chapterTitle = json.optJSONObject("chapter")?.optString("title"),
                    paragraphs = paragraphs,
                    images = dataObj.optJSONArray("images"),
                    chapterTerms = chapterTerms,
                    storyTerms = storyTerms,
                    userTerms = userTerms,
                    patches = patchArray,
                    usedService = usedService,
                    hasAuth = hasAuth
                )

            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                e.printStackTrace()
                continue
            }
        }

        // All services failed
        return buildErrorHtml(lastError, hasAuth)
    }

    /**
     * Build chapter HTML with all content processing
     */
    private fun buildChapterHtml(
        chapterNo: String,
        chapterTitle: String?,
        paragraphs: List<String>,
        images: JSONArray?,
        chapterTerms: Map<String, String>,
        storyTerms: List<GlossaryTerm>,
        userTerms: List<GlossaryTerm>,
        patches: JSONArray?,
        usedService: String?,
        hasAuth: Boolean
    ): String {
        val html = StringBuilder()

        // Chapter title
        if (!chapterTitle.isNullOrBlank()) {
            html.append("<h1>#$chapterNo: $chapterTitle</h1>\n")
        }

        // Translation service indicator
        if (usedService == "web" && !hasAuth) {
            html.append("""
                <div style="background: #fff3cd; padding: 12px; margin-bottom: 16px; 
                    border-radius: 8px; border-left: 4px solid #ffc107;">
                    <strong>⚠️ Web Translation (Machine Translation)</strong><br/>
                    <span style="font-size: 0.9em;">
                        Log in via WebView for better AI translation quality.
                    </span>
                </div>
            """.trimIndent())
        }

        // Process paragraphs
        var imageIndex = 0

        for (paragraph in paragraphs) {
            if (paragraph == "[image]") {
                // Insert image
                val imageUrl = images?.optString(imageIndex)
                if (!imageUrl.isNullOrBlank()) {
                    html.append("""
                        <p><img src="$imageUrl" style="max-width: 100%; height: auto;" /></p>
                    """.trimIndent())
                }
                imageIndex++
            } else {
                // Apply all term replacements
                var processedText = applyGlossaryTerms(
                    text = paragraph,
                    chapterTerms = chapterTerms,
                    storyTerms = storyTerms,
                    userTerms = userTerms
                )

                // Apply patches (zh -> en)
                processedText = applyPatches(processedText, patches)

                html.append("<p>$processedText</p>\n")
            }
        }

        return html.toString()
    }

    /**
     * Build error HTML with helpful information
     */
    private fun buildErrorHtml(errorMessage: String?, hasAuth: Boolean): String {
        return buildString {
            append("<div style=\"padding: 20px; text-align: center;\">")
            append("<h2>❌ Failed to load chapter</h2>")
            append("<p style=\"color: #666; margin: 16px 0;\">")
            append(errorMessage ?: "Unknown error occurred")
            append("</p>")

            if (!hasAuth) {
                append("<hr style=\"margin: 20px 0; border: none; border-top: 1px solid #ddd;\"/>")
                append("<h3>💡 Unlock unlimited AI translations</h3>")
                append("<p>WTR-LAB limits AI translations for guests (~10 chapters).</p>")
                append("<p><strong>To unlock all chapters:</strong></p>")
                append("<ol style=\"text-align: left; display: inline-block; margin: 16px 0;\">")
                append("<li>Tap the globe icon to open WTR-LAB in WebView</li>")
                append("<li>Create an account or log in</li>")
                append("<li>Return to reading</li>")
                append("</ol>")
                append("<p style=\"font-size: 0.9em; color: #888;\">")
                append("Authentication persists across app restarts.")
                append("</p>")
            } else {
                append("<hr style=\"margin: 20px 0; border: none; border-top: 1px solid #ddd;\"/>")
                append("<p><strong>Troubleshooting:</strong></p>")
                append("<ul style=\"text-align: left; display: inline-block; margin: 16px 0;\">")
                append("<li>This chapter may not be AI translated yet</li>")
                append("<li>The server might be under heavy load</li>")
                append("<li>Try again in a few moments</li>")
                append("<li>Check if the chapter loads on the website</li>")
                append("</ul>")
            }

            append("</div>")
        }
    }
}