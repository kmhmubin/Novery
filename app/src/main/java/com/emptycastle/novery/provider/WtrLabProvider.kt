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
 * This site uses Next.js with SSR. Data is available in:
 * - __NEXT_DATA__ script tag (JSON with pageProps)
 * - Various API endpoints for chapters and content
 *
 * Chapter content may be encrypted with AES-GCM and requires decryption.
 * The site has aggressive rate limiting (~12 seconds between requests).
 *
 * IMPORTANT: AI translation requires authentication. Users must log in
 * via WebView to access AI translations beyond the free limit (~10 chapters).
 */
class WtrLabProvider : MainProvider() {

    override val name = "WTR-LAB"
    override val mainUrl = "https://wtr-lab.com"
    override val hasMainPage = true
    override val hasReviews = false
    override val iconRes: Int = R.drawable.ic_provider_wtrlab
    override val rateLimitTime: Long = 12000L

    private val lang = "en"
    private var decryptionKey: String? = null
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
    // COOKIE MANAGEMENT
    // ================================================================

    /**
     * Get cookies for wtr-lab.com from the system CookieManager
     * These are set when user logs in via WebView
     */
    private fun getCookiesForDomain(): String? {
        return try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.getCookie(mainUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Check if user is likely authenticated based on cookies
     */
    private fun isAuthenticated(): Boolean {
        val cookies = getCookiesForDomain() ?: return false
        // WTR-LAB uses various auth cookies
        return cookies.contains("token") ||
                cookies.contains("session") ||
                cookies.contains("user_id") ||
                cookies.contains("auth") ||
                cookies.contains("remember")
    }

    /**
     * Build headers with cookies included
     */
    private fun buildHeadersWithCookies(
        additionalHeaders: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers.putAll(additionalHeaders)

        // Add cookies if available
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
            0 -> "Ongoing"
            1 -> "Completed"
            2 -> "Hiatus"
            3 -> "Dropped"
            else -> null
        }
    }

    /**
     * Extract decryption key from site's JavaScript files
     * The key changes periodically, so we need to extract it dynamically
     */
    private suspend fun getDecryptionKey(document: Document): String {
        // Return cached key if available
        decryptionKey?.let { return it }

        try {
            val searchPattern = "TextEncoder().encode(\""
            val scripts = document.select("head script[src]")

            for (script in scripts) {
                val src = script.attr("src")
                if (src.isBlank()) continue

                // Build full URL
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
                    // Continue to next script
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
            // Not encrypted or invalid format - return as-is
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
            // Return original text if decryption fails
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

    /**
     * Get or refresh the buildId needed for _next/data API calls
     */
    private suspend fun getBuildId(): String {
        cachedBuildId?.let { return it }

        val response = get("$mainUrl/$lang/novel-finder", buildHeadersWithCookies())
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

        val params = mutableListOf<String>()
        params.add("orderBy=$order")
        params.add("order=desc")
        params.add("status=all")
        params.add("release_status=all")
        params.add("addition_age=all")
        params.add("page=$page")

        if (!tag.isNullOrEmpty()) {
            params.add("gi=$tag")
            params.add("gc=or")
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
            ?: document.selectFirstOrNull("h1.text-uppercase")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("h1.long-title")?.textOrNull()?.trim()
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

        val chapters = loadChapters(rawId, rawChapterCount, slug)

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
        val batchSize = 250
        var start = 1L

        while (start <= totalChapters) {
            val end = minOf(start + batchSize - 1, totalChapters)

            try {
                val url = "$mainUrl/api/chapters/$rawId?start=$start&end=$end"
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
                        "#$order $title"
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
        val regex = Regex("/serie-(\\d+)/[^/]+/chapter-(\\d+)")
        val match = regex.find(fullUrl)
            ?: throw Exception("Invalid chapter URL format: $fullUrl")

        val rawId = match.groupValues[1]
        val chapterNo = match.groupValues[2]

        // Check authentication status
        val hasAuth = isAuthenticated()

        // Translation services to try:
        // - "ai" requires authentication for unlimited access
        // - "web" is the fallback (machine translation)
        val services = if (hasAuth) {
            listOf("ai", "web")
        } else {
            // Without auth, AI may fail after ~10 chapters
            listOf("web", "ai")
        }

        var lastError: String? = null
        var pageDocument: Document? = null
        var usedService: String? = null

        for (service in services) {
            try {
                // Build JSON body
                val jsonBody = JSONObject().apply {
                    put("translate", service)
                    put("language", lang)
                    put("raw_id", rawId.toLong())
                    put("chapter_no", chapterNo.toLong())
                    put("retry", false)
                    put("force_retry", false)
                }

                // Make request with cookies and JSON content type
                val response = postJson(
                    url = "$mainUrl/api/reader/get",
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
                    json.optInt("code", 0) == 1401) {
                    throw Exception("Cloudflare verification required. Please open in WebView.")
                }

                // Check for auth/limit errors
                val code = json.optInt("code", 0)
                if (code == 401 || code == 403) {
                    lastError = "Authentication required for AI translation."
                    continue
                }

                // Check for success
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

                // Success! Mark which service worked
                usedService = service

                // Parse body content (may be encrypted)
                val paragraphs: List<String> = when (bodyContent) {
                    is String -> {
                        if (bodyContent.startsWith("arr:") || bodyContent.startsWith("str:")) {
                            // Encrypted content - need decryption key
                            if (pageDocument == null) {
                                pageDocument = get(fullUrl, buildHeadersWithCookies()).document
                            }
                            val key = getDecryptionKey(pageDocument!!)
                            decryptContent(bodyContent, key)
                        } else {
                            // Plain text
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
                val chapterObj = json.optJSONObject("chapter")
                val chapterTitle = chapterObj?.optString("title")
                if (!chapterTitle.isNullOrBlank()) {
                    contentBuilder.append("<h1>#$chapterNo $chapterTitle</h1>\n")
                }

                // Add translation service indicator if using web translation without auth
                if (usedService == "web" && !hasAuth) {
                    contentBuilder.append(
                        "<div style=\"background: #fff3cd; padding: 12px; margin-bottom: 16px; " +
                                "border-radius: 8px; border-left: 4px solid #ffc107;\">" +
                                "<strong>⚠️ Web Translation</strong><br/>" +
                                "<span style=\"font-size: 0.9em;\">Log in via WebView for better AI translation.</span>" +
                                "</div>\n"
                    )
                }

                // Process paragraphs
                val imagesArray = dataObj.optJSONArray("images")
                var imageIndex = 0
                val patchArray = dataObj.optJSONArray("patch")

                for (paragraph in paragraphs) {
                    if (paragraph == "[image]") {
                        // Insert image
                        val imageUrl = imagesArray?.optString(imageIndex)
                        if (!imageUrl.isNullOrBlank()) {
                            contentBuilder.append("<p><img src=\"$imageUrl\" style=\"max-width: 100%;\" /></p>\n")
                        }
                        imageIndex++
                    } else {
                        // Apply glossary replacements
                        var processedText = applyGlossary(paragraph, glossaryTerms)

                        // Apply patches if available (zh -> en replacements)
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

        // All services failed - provide helpful error message as HTML
        val errorMessage = buildString {
            append("<div style=\"padding: 20px; text-align: center;\">")
            append("<h2>❌ Failed to load chapter</h2>")
            append("<p style=\"color: #666;\">$lastError</p>")

            if (!hasAuth) {
                append("<hr style=\"margin: 20px 0;\"/>")
                append("<h3>💡 Tip: Log in for unlimited access</h3>")
                append("<p>WTR-LAB limits AI translations for guests (~10 chapters).</p>")
                append("<p><strong>To unlock all chapters:</strong></p>")
                append("<ol style=\"text-align: left; display: inline-block;\">")
                append("<li>Open WTR-LAB in WebView (tap globe icon)</li>")
                append("<li>Log in or create an account</li>")
                append("<li>Return to reading</li>")
                append("</ol>")
            }

            append("</div>")
        }

        return errorMessage
    }
}