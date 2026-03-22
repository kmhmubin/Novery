package com.emptycastle.novery.provider

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
 * This site uses Next.js with SSR. Data is available in:
 * - __NEXT_DATA__ script tag (JSON with pageProps)
 * - Various API endpoints for chapters and content
 *
 * Chapter content may be encrypted with AES-GCM and requires decryption.
 * The site has aggressive rate limiting (~12 seconds between requests).
 */
class WtrLabProvider : MainProvider() {

    override val name = "WTR-LAB"
    override val mainUrl = "https://wtr-lab.com"
    override val hasMainPage = true
    override val hasReviews = false
    override val iconRes: Int = R.drawable.ic_provider_wtrlab
    override val rateLimitTime: Long = 12000L // Site is heavily rate-limited

    // Language prefix for URLs
    private val lang = "en"

    // Default decryption key (may need to be extracted from JS if it changes)
    private var decryptionKey: String? = "IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru"

    // Cache for buildId (needed for _next/data API calls)
    private var cachedBuildId: String? = null

    // ================================================================
    // FILTER OPTIONS
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

    override val tags = listOf(
        FilterOption("All", ""),
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
    // UTILITY METHODS
    // ================================================================

    /**
     * Extract __NEXT_DATA__ JSON from document
     */
    private fun extractNextData(document: Document): JSONObject? {
        val script = document.selectFirstOrNull("script#__NEXT_DATA__")
        val jsonText = script?.data() ?: return null
        return try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract buildId from __NEXT_DATA__
     */
    private fun extractBuildId(nextData: JSONObject): String? {
        return nextData.optString("buildId", null)?.takeIf { it.isNotBlank() }
    }

    /**
     * Parse status from integer value
     */
    private fun parseStatus(status: Int?): String? {
        return when (status) {
            0 -> "Ongoing"
            1 -> "Completed"
            2 -> "Hiatus"
            3 -> "Dropped"
            else -> null
        }
    }

    /**
     * Extract decryption key from site's JavaScript files
     * Falls back to hardcoded key if extraction fails
     */
    private suspend fun getDecryptionKey(document: Document): String {
        // Try cached/hardcoded key first
        decryptionKey?.let { return it }

        try {
            val searchPattern = "TextEncoder().encode(\""
            val scripts = document.select("head script[src]")

            for (script in scripts) {
                val src = script.attr("src")
                if (src.isBlank()) continue

                val scriptUrl = if (src.startsWith("http")) src else "$mainUrl$src"
                val scriptContent = get(scriptUrl).text

                val keyIndex = scriptContent.indexOf(searchPattern)
                if (keyIndex >= 0) {
                    val keyStart = keyIndex + searchPattern.length
                    val extractedKey = scriptContent.substring(keyStart, keyStart + 32)
                    decryptionKey = extractedKey
                    return extractedKey
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to hardcoded key
        return "IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru"
    }

    /**
     * Decrypt AES-GCM encrypted content
     *
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

    /**
     * Apply glossary term replacements to text
     * Terms are marked as ※N⛬ or ※N〓 where N is the index
     */
    private fun applyGlossary(text: String, glossaryTerms: Map<Int, String>): String {
        var result = text
        for ((index, replacement) in glossaryTerms) {
            result = result.replace("※${index}⛬", replacement)
            result = result.replace("※${index}〓", replacement)
        }
        return result
    }

    /**
     * Parse novel from JSON object (search/list results)
     */
    private fun parseNovelFromJson(seriesObj: JSONObject): Novel? {
        val rawId = seriesObj.optLong("raw_id", 0)
        if (rawId == 0L) return null

        val slug = seriesObj.optString("slug", "")
        val data = seriesObj.optJSONObject("data") ?: return null

        val title = data.optString("title", "").takeIf { it.isNotBlank() } ?: return null
        val image = data.optString("image", null)?.takeIf { it.isNotBlank() }

        val novelUrl = "/$lang/serie-$rawId/$slug"

        // Extract chapter count for latest chapter info
        val chapterCount = seriesObj.optInt("chapter_count", 0)
        val latestChapter = if (chapterCount > 0) "$chapterCount Chapters" else null

        // Extract rating
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

    /**
     * Get or refresh the buildId needed for _next/data API calls
     */
    private suspend fun getBuildId(): String {
        cachedBuildId?.let { return it }

        val response = get("$mainUrl/$lang/novel-finder")
        val nextData = extractNextData(response.document)
        val buildId = nextData?.let { extractBuildId(it) }
            ?: throw Exception("Could not extract buildId from page")

        cachedBuildId = buildId
        return buildId
    }

    // ================================================================
    // MAIN PAGE
    // ================================================================

    override suspend fun loadMainPage(
        page: Int,
        orderBy: String?,
        tag: String?
    ): MainPageResult {
        val order = orderBy.takeUnless { it.isNullOrEmpty() } ?: "update"

        val buildId = getBuildId()

        // Build query parameters
        val params = mutableListOf<String>()
        params.add("orderBy=$order")
        params.add("order=desc")
        params.add("status=all")
        params.add("release_status=all")
        params.add("addition_age=all")
        params.add("page=$page")

        if (!tag.isNullOrEmpty()) {
            params.add("gi=$tag")
            params.add("gc=or") // Genre operator: OR
        }

        val queryString = params.joinToString("&")
        val url = "$mainUrl/_next/data/$buildId/$lang/novel-finder.json?$queryString"

        val response = get(url, mapOf(
            "Accept" to "application/json, text/plain, */*"
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

            // Deduplicate
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

        val response = get(url, mapOf(
            "Accept" to "application/json, text/plain, */*"
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

        val response = get(fullUrl)
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

        // Extract basic info
        val title = data?.optString("title")?.takeIf { it.isNotBlank() }
            ?: document.selectFirstOrNull("h1.text-uppercase")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("h1.long-title")?.textOrNull()?.trim()
            ?: return null

        val author = data?.optString("author")?.takeIf { it.isNotBlank() }
        val description = data?.optString("description")?.takeIf { it.isNotBlank() }
        val image = data?.optString("image")?.takeIf { it.isNotBlank() }

        // Extract status, rating, views
        val status = parseStatus(serieData.optInt("status", -1))
        val rawChapterCount = serieData.optLong("raw_chapter_count", 0)

        val rating = serieData.optDouble("rating", Double.NaN)
            .takeIf { !it.isNaN() && it > 0 }
            ?.let { RatingUtils.from5Stars(it.toFloat()) }

        val peopleVoted = serieData.optInt("total_rate", 0).takeIf { it > 0 }
        val views = serieData.optInt("view", 0).takeIf { it > 0 }

        // Extract genres/tags
        val tags = mutableListOf<String>()

        // From pageProps.tags
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

        // From serieData.genres (these are IDs, but we can still show them)
        val genresArray = serieData.optJSONArray("genres")
        // Skip since these are just IDs

        // Load chapters
        val chapters = loadChapters(rawId, rawChapterCount, slug)

        // Extract related novels from recommendations
        val recommendations = serie.optJSONArray("recommendation")
        val relatedNovels = if (recommendations != null) {
            (0 until recommendations.length()).mapNotNull { i ->
                val recObj = recommendations.getJSONObject(i)
                parseNovelFromJson(recObj)
            }
        } else {
            emptyList()
        }

        // Also check other_series
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
        val batchSize = 250
        var start = 1L

        while (start <= totalChapters) {
            val end = minOf(start + batchSize - 1, totalChapters)

            try {
                val url = "$mainUrl/api/chapters/$rawId?start=$start&end=$end"
                val response = get(url, mapOf(
                    "Accept" to "application/json"
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
                        "#$order $title"
                    } else {
                        "Chapter $order"
                    }

                    val chapterUrl = "/$lang/serie-$rawId/$slug/chapter-$order"

                    chapters.add(
                        Chapter(
                            name = chapterName,
                            url = fixUrl(chapterUrl) ?: continue,
                            dateOfRelease = updatedAt?.take(10) // YYYY-MM-DD
                        )
                    )
                }

                if (chaptersArray.length() < batchSize) break
                start += batchSize

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
        // Pattern: /{lang}/serie-{rawId}/{slug}/chapter-{chapterNo}
        val regex = Regex("/serie-(\\d+)/[^/]+/chapter-(\\d+)")
        val match = regex.find(fullUrl)
            ?: throw Exception("Invalid chapter URL format: $fullUrl")

        val rawId = match.groupValues[1]
        val chapterNo = match.groupValues[2]

        // Try different translation services
        val services = listOf("ai", "web")
        var lastError: String? = null
        var pageDocument: Document? = null

        for (service in services) {
            try {
                val response = post(
                    url = "$mainUrl/api/reader/get",
                    data = mapOf(
                        "translate" to service,
                        "language" to lang,
                        "raw_id" to rawId,
                        "chapter_no" to chapterNo,
                        "retry" to "false",
                        "force_retry" to "false"
                    ),
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "Referer" to fullUrl
                    )
                )

                val json = JSONObject(response.text)

                // Check for Turnstile (Cloudflare challenge)
                if (json.optBoolean("requireTurnstile", false) ||
                    json.optInt("code", 0) == 1401) {
                    throw Exception("Cloudflare verification required. Please open in WebView.")
                }

                // Check for errors
                if (!json.optBoolean("success", true)) {
                    lastError = json.optString("message", "Unknown error")
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

                // Parse body content (may be encrypted)
                val paragraphs: List<String> = when (bodyContent) {
                    is String -> {
                        if (bodyContent.startsWith("arr:") || bodyContent.startsWith("str:")) {
                            // Need to get decryption key
                            if (pageDocument == null) {
                                pageDocument = get(fullUrl).document
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

                // Parse glossary terms for replacement
                val glossaryData = dataObj.optJSONObject("glossary_data")
                val termsArray = glossaryData?.optJSONArray("terms")
                val glossaryTerms = mutableMapOf<Int, String>()

                if (termsArray != null) {
                    for (i in 0 until termsArray.length()) {
                        val term = termsArray.optJSONArray(i) ?: continue
                        val replacement = term.optString(0, null)
                        if (!replacement.isNullOrBlank()) {
                            glossaryTerms[i] = replacement
                        }
                    }
                }

                // Build HTML content
                val contentBuilder = StringBuilder()

                // Add chapter title
                val chapterTitle = json.optJSONObject("chapter")?.optString("title")
                if (!chapterTitle.isNullOrBlank()) {
                    contentBuilder.append("<h1>#$chapterNo $chapterTitle</h1>\n")
                }

                // Process paragraphs
                val imagesArray = dataObj.optJSONArray("images")
                var imageIndex = 0

                // Parse patch data for additional replacements
                val patchArray = dataObj.optJSONArray("patch")

                for (paragraph in paragraphs) {
                    if (paragraph == "[image]") {
                        // Insert image
                        val imageUrl = imagesArray?.optString(imageIndex)
                        if (!imageUrl.isNullOrBlank()) {
                            contentBuilder.append("<p><img src=\"$imageUrl\" /></p>\n")
                        }
                        imageIndex++
                    } else {
                        // Apply glossary replacements
                        var processedText = applyGlossary(paragraph, glossaryTerms)

                        // Apply patches if available
                        if (patchArray != null) {
                            for (i in 0 until patchArray.length()) {
                                val patch = patchArray.optJSONObject(i) ?: continue
                                val zh = patch.optString("zh", "")
                                val en = patch.optString("en", "")
                                if (zh.isNotBlank() && en.isNotBlank()) {
                                    processedText = processedText.replace(zh, " $en")
                                }
                            }
                        }

                        contentBuilder.append("<p>$processedText</p>\n")
                    }
                }

                return contentBuilder.toString()

            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                e.printStackTrace()
                continue
            }
        }

        throw Exception(lastError ?: "Failed to load chapter content")
    }
}