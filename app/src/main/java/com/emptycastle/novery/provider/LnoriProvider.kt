package com.emptycastle.novery.provider

import com.emptycastle.novery.R
import com.emptycastle.novery.domain.model.Chapter
import com.emptycastle.novery.domain.model.FilterOption
import com.emptycastle.novery.domain.model.MainPageResult
import com.emptycastle.novery.domain.model.Novel
import com.emptycastle.novery.domain.model.NovelDetails
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Provider for lnori.com
 *
 * This is a fully static site with no APIs.
 * - All novels are pre-loaded on the /library page (~800+ novels)
 * - Content is organized as Series → Volumes → Chapters
 * - Each volume loads as a single page with all chapters inside
 * - Search is client-side only (we filter locally)
 */
class LnoriProvider : MainProvider() {

    override val name = "Lnori"
    override val mainUrl = "https://lnori.com"
    override val hasMainPage = true
    override val hasReviews = false
    override val iconRes: Int = R.drawable.ic_provider_lnori

    // Page size for client-side pagination (library has 800+ novels)
    private val pageSize = 50

    // ================================================================
    // FILTER OPTIONS
    // ================================================================

    override val tags = listOf(
        FilterOption("All", ""),
        FilterOption("Academy", "academy"),
        FilterOption("Action", "action"),
        FilterOption("Adult Protagonist", "adult-protagonist"),
        FilterOption("Adventure", "adventure"),
        FilterOption("Age Gap", "age-gap"),
        FilterOption("Airhead", "airhead"),
        FilterOption("Alchemy", "alchemy"),
        FilterOption("Animals", "animals"),
        FilterOption("Anime Tie-In", "anime-tie-in"),
        FilterOption("Aristocracy", "aristocracy"),
        FilterOption("Battle", "battle"),
        FilterOption("Books", "books"),
        FilterOption("Boys Love", "boys-love"),
        FilterOption("Business", "business"),
        FilterOption("Camping", "camping"),
        FilterOption("Childhood Friend", "childhood-friend"),
        FilterOption("Chuunibyou", "chuunibyou"),
        FilterOption("Combat", "combat"),
        FilterOption("Comedy", "comedy"),
        FilterOption("Contract Marriage", "contract-marriage"),
        FilterOption("Cooking", "cooking"),
        FilterOption("Crime", "crime"),
        FilterOption("Cross-Dressing", "cross-dressing"),
        FilterOption("Dark", "dark"),
        FilterOption("Dark Fantasy", "dark-fantasy"),
        FilterOption("Demon Lord", "demon-lord"),
        FilterOption("Demons", "demons"),
        FilterOption("Dragons", "dragons"),
        FilterOption("Drama", "drama"),
        FilterOption("Dungeon", "dungeon"),
        FilterOption("Dungeon Diving", "dungeon-diving"),
        FilterOption("Dystopian", "dystopian"),
        FilterOption("Ecchi", "ecchi"),
        FilterOption("Elf", "elf"),
        FilterOption("Enemies to Lovers", "enemies-to-lovers"),
        FilterOption("Fairies", "fairies"),
        FilterOption("Familiars", "familiars"),
        FilterOption("Family", "family"),
        FilterOption("Fanservice", "fanservice"),
        FilterOption("Fantasy", "fantasy"),
        FilterOption("Fantasy World", "fantasy-world"),
        FilterOption("Female Protagonist", "female-protagonist"),
        FilterOption("First Person", "first-person"),
        FilterOption("Fish Out of Water", "fish-out-of-water"),
        FilterOption("Food", "food"),
        FilterOption("Friendship", "friendship"),
        FilterOption("Futuristic", "futuristic"),
        FilterOption("Game Elements", "game-elements"),
        FilterOption("Gamer Protagonist", "gamer-protagonist"),
        FilterOption("Gender Bender", "gender-bender"),
        FilterOption("Genius", "genius"),
        FilterOption("Girls Love", "girls-love"),
        FilterOption("Guns", "guns"),
        FilterOption("Harem", "harem"),
        FilterOption("Heartwarming", "heartwarming"),
        FilterOption("High Fantasy", "high-fantasy"),
        FilterOption("High School", "high-school"),
        FilterOption("Historical", "historical"),
        FilterOption("Historical Fantasy", "historical-fantasy"),
        FilterOption("Horror", "horror"),
        FilterOption("Humor", "humor"),
        FilterOption("Invention", "invention"),
        FilterOption("Isekai", "isekai"),
        FilterOption("Josei", "josei"),
        FilterOption("Knights", "knights"),
        FilterOption("LGBTQ", "lgbtq"),
        FilterOption("Lighthearted", "lighthearted"),
        FilterOption("Literary", "literary"),
        FilterOption("Magic", "magic"),
        FilterOption("Magic Academy", "magic-academy"),
        FilterOption("Magical Weapons", "magical-weapons"),
        FilterOption("Maid", "maid"),
        FilterOption("Male Protagonist", "male-protagonist"),
        FilterOption("Manga Tie-In", "manga-tie-in"),
        FilterOption("Marriage", "marriage"),
        FilterOption("Martial Arts", "martial-arts"),
        FilterOption("Master and Servant", "master-and-servant"),
        FilterOption("Mature", "mature"),
        FilterOption("Mecha", "mecha"),
        FilterOption("Medieval", "medieval"),
        FilterOption("Military", "military"),
        FilterOption("Modern Day", "modern-day"),
        FilterOption("Moe", "moe"),
        FilterOption("Monster Girls", "monster-girls"),
        FilterOption("Monster Taming", "monster-taming"),
        FilterOption("Monsters", "monsters"),
        FilterOption("Multiple POV", "multiple-pov"),
        FilterOption("Mystery", "mystery"),
        FilterOption("Nobility", "nobility"),
        FilterOption("Not the Hero", "not-the-hero"),
        FilterOption("OP Power", "op-power"),
        FilterOption("OP Protagonist", "op-protagonist"),
        FilterOption("Ordinary Protagonist", "ordinary-protagonist"),
        FilterOption("Otaku", "otaku"),
        FilterOption("Otome", "otome"),
        FilterOption("Otome Game", "otome-game"),
        FilterOption("Overpowered", "overpowered"),
        FilterOption("Paranormal", "paranormal"),
        FilterOption("Past Life", "past-life"),
        FilterOption("Period Piece", "period-piece"),
        FilterOption("Personal Growth", "personal-growth"),
        FilterOption("Political Marriage", "political-marriage"),
        FilterOption("Politics", "politics"),
        FilterOption("Princess", "princess"),
        FilterOption("Reincarnation", "reincarnation"),
        FilterOption("Revenge", "revenge"),
        FilterOption("Reverse Harem", "reverse-harem"),
        FilterOption("Rewriting History", "rewriting-history"),
        FilterOption("Romance", "romance"),
        FilterOption("Romantic Fantasy", "romantic-fantasy"),
        FilterOption("RPG", "rpg"),
        FilterOption("Satire", "satire"),
        FilterOption("School", "school"),
        FilterOption("School Life", "school-life"),
        FilterOption("Sci-Fi", "sci-fi"),
        FilterOption("Seinen", "seinen"),
        FilterOption("Shoujo", "shoujo"),
        FilterOption("Shounen", "shounen"),
        FilterOption("Slice of Life", "slice-of-life"),
        FilterOption("Slow Life", "slow-life"),
        FilterOption("Snarky Protagonist", "snarky-protagonist"),
        FilterOption("Sorcery", "sorcery"),
        FilterOption("Strategy", "strategy"),
        FilterOption("Strong Female Lead", "strong-female-lead"),
        FilterOption("Supernatural", "supernatural"),
        FilterOption("Superpowers", "superpowers"),
        FilterOption("Survival", "survival"),
        FilterOption("Sword and Sorcery", "sword-and-sorcery"),
        FilterOption("Thriller", "thriller"),
        FilterOption("Time Travel", "time-travel"),
        FilterOption("Tsundere", "tsundere"),
        FilterOption("Underdog", "underdog"),
        FilterOption("Unique Ability", "unique-ability"),
        FilterOption("Vampire", "vampire"),
        FilterOption("Video Game", "video-game"),
        FilterOption("Video Game Related", "video-game-related"),
        FilterOption("Video Game Tie-In", "video-game-tie-in"),
        FilterOption("Villainess", "villainess"),
        FilterOption("Violence", "violence"),
        FilterOption("VRMMO", "vrmmo"),
        FilterOption("War", "war"),
        FilterOption("Weak Protagonist", "weak-protagonist"),
        FilterOption("Witch", "witch"),
        FilterOption("Zero to Hero", "zero-to-hero")
    )

    // No sort options available - site only shows by popularity
    override val orderBys = listOf(
        FilterOption("Popular", "")
    )

    // ================================================================
    // UTILITY METHODS
    // ================================================================

    /**
     * Parse a novel card element from library/genre pages.
     * All metadata is available in data-* attributes on the <article>.
     */
    private fun parseNovelCard(element: Element): Novel? {
        // Get data from attributes (most reliable)
        val seriesId = element.attrOrNull("data-id") ?: return null
        val title = element.attrOrNull("data-t")?.trim()
        if (title.isNullOrBlank()) return null

        val author = element.attrOrNull("data-a")?.trim()
        val volumeCount = element.attrOrNull("data-v")?.toIntOrNull()

        // Get URL from link
        val linkElement = element.selectFirstOrNull("a.stretched-link[href^=\"/series/\"]")
            ?: element.selectFirstOrNull("a[href^=\"/series/\"]")
        val href = linkElement?.attrOrNull("href") ?: return null

        // Get cover image
        val imgElement = element.selectFirstOrNull("figure.card-cover img")
        val posterUrl = imgElement?.attrOrNull("src")

        // Build latest chapter info from volume count
        val latestChapter = volumeCount?.let { "$it Volumes" }

        return Novel(
            name = title,
            url = fixUrl(href) ?: return null,
            posterUrl = posterUrl,
            latestChapter = latestChapter,
            apiName = this.name
        )
    }

    /**
     * Parse tags from the series detail page.
     * Tags are stored as JSON in the data-tags attribute.
     */
    private fun parseTagsFromDocument(document: Document): List<String> {
        // Try to parse from data-tags JSON attribute first
        val tagsNav = document.selectFirstOrNull("nav.tags-box[data-tags]")
        val dataTagsJson = tagsNav?.attrOrNull("data-tags")

        if (!dataTagsJson.isNullOrBlank()) {
            try {
                // Parse JSON array: [{"name":"action","ttype":"genre"}, ...]
                val jsonArray = JSONArray(dataTagsJson)
                val tags = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val tagName = obj.optString("name", null)
                    if (!tagName.isNullOrBlank()) {
                        tags.add(tagName)
                    }
                }
                if (tags.isNotEmpty()) return tags
            } catch (_: Throwable) {
                // Fall through to link parsing
            }
        }

        // Fallback: parse from tag links
        return document.select("nav.tags-box a.tag").mapNotNull {
            it.textOrNull()?.trim()
        }.filter { it.isNotBlank() }
    }

    /**
     * Parse volumes from the series detail page.
     * Each volume becomes a "chapter" in the app since volumes have their own URLs.
     */
    private fun parseVolumes(document: Document): List<Chapter> {
        return document.select("section.vol-grid article.card").mapNotNull { volumeCard ->
            // Get volume link
            val linkElement = volumeCard.selectFirstOrNull("a.stretched-link[href^=\"/book/\"]")
                ?: volumeCard.selectFirstOrNull("a[href^=\"/book/\"]")
            val href = linkElement?.attrOrNull("href") ?: return@mapNotNull null

            // Get volume title
            val volumeTitle = volumeCard.selectFirstOrNull("h3.card-title span")?.textOrNull()?.trim()
                ?: linkElement.attrOrNull("aria-label")
                ?: "Volume"

            Chapter(
                name = volumeTitle,
                url = fixUrl(href) ?: return@mapNotNull null
            )
        }
    }

    /**
     * Clean up the chapter HTML content
     */
    private fun cleanChapterHtml(html: String): String {
        var cleaned = html

        // Remove excessive separators
        cleaned = cleaned.replace(Regex("(<hr\\s*/?>\n*){2,}"), "<hr/>\n")

        // Remove empty paragraphs
        cleaned = cleaned.replace(Regex("<p>\\s*</p>"), "")

        // Clean up whitespace
        cleaned = cleaned.replace(Regex("\n{3,}"), "\n\n")

        return cleaned.trim()
    }

    // ================================================================
    // MAIN PAGE
    // ================================================================

    override suspend fun loadMainPage(
        page: Int,
        orderBy: String?,
        tag: String?,
        extraFilters: Map<String, String>
    ): MainPageResult {
        val url = if (tag.isNullOrBlank()) {
            "$mainUrl/library"
        } else {
            "$mainUrl/genre/$tag"
        }

        val response = get(url)
        val document = response.document

        // Parse all novel cards from the page
        val allNovels = document.select("article.card").mapNotNull { element ->
            parseNovelCard(element)
        }

        // Handle pagination client-side (all data is on one page)
        val startIndex = (page - 1) * pageSize
        val endIndex = minOf(startIndex + pageSize, allNovels.size)

        val novels = if (startIndex < allNovels.size) {
            allNovels.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        return MainPageResult(url = url, novels = novels)
    }

    // ================================================================
    // SEARCH
    // ================================================================

    override suspend fun search(query: String): List<Novel> {
        // Since search is client-side only, we load the library page
        // and filter the results locally
        val response = get("$mainUrl/library")
        val document = response.document

        val queryLower = query.lowercase().trim()

        return document.select("article.card").mapNotNull { element ->
            val title = element.attrOrNull("data-t")?.trim() ?: return@mapNotNull null
            val author = element.attrOrNull("data-a")?.trim() ?: ""
            val tags = element.attrOrNull("data-tags")?.lowercase() ?: ""

            // Search in title, author, and tags
            if (title.lowercase().contains(queryLower) ||
                author.lowercase().contains(queryLower) ||
                tags.contains(queryLower)
            ) {
                parseNovelCard(element)
            } else {
                null
            }
        }
    }

    // ================================================================
    // LOAD NOVEL DETAILS
    // ================================================================

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"

        val response = get(fullUrl)
        val document = response.document

        // Parse title
        val name = document.selectFirstOrNull("h1.s-title")?.textOrNull()?.trim()
            ?: return null

        // Parse author
        val author = document.selectFirstOrNull("p.author")?.textOrNull()?.trim()

        // Parse cover
        val posterUrl = document.selectFirstOrNull("figure.cover-wrap img")?.attrOrNull("src")

        // Parse description
        val synopsis = document.selectFirstOrNull("p.description.desc-wrapper")?.textOrNull()?.trim()
            ?: "No description available."

        // Parse tags from data-tags JSON
        val tags = parseTagsFromDocument(document)

        // Parse volumes as chapters
        val chapters = parseVolumes(document)

        return NovelDetails(
            url = fullUrl,
            name = name,
            chapters = chapters,
            author = author,
            posterUrl = posterUrl,
            synopsis = synopsis,
            tags = tags.ifEmpty { null }
            // Note: rating, status, views, relatedNovels not available on this site
        )
    }

    // ================================================================
    // LOAD CHAPTER CONTENT (VOLUME)
    // ================================================================

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"

        val response = get(fullUrl)
        val document = response.document

        // Get all chapter sections on this volume page
        val chapterSections = document.select("section.chapter[id^=\"page\"]")

        if (chapterSections.isEmpty()) {
            return null
        }

        val contentBuilder = StringBuilder()

        for (section in chapterSections) {
            val sectionId = section.attrOrNull("id") ?: ""

            // Check for chapter title (standalone h2 in the section)
            val standaloneTitle = section.selectFirstOrNull("> h2.chapter-title")
            if (standaloneTitle != null) {
                contentBuilder.append("<h2>${standaloneTitle.text()}</h2>\n")
            }

            // Get the inner content container
            val innerContent = section.selectFirstOrNull("section.body-rw.Chapter-rw")
                ?: section.selectFirstOrNull("div.galley-rw section")
                ?: section.selectFirstOrNull("div.galley-rw")

            if (innerContent != null) {
                // Get chapter headers if present
                val chapterNumber = innerContent.selectFirstOrNull("h2.chapter-number span")?.textOrNull()?.trim()
                val chapterTitle = innerContent.selectFirstOrNull("h2.chapter-title span")?.textOrNull()?.trim()

                // Add chapter header
                if (!chapterNumber.isNullOrBlank() || !chapterTitle.isNullOrBlank()) {
                    val headerText = listOfNotNull(chapterNumber, chapterTitle)
                        .filter { it.isNotBlank() }
                        .joinToString(": ")
                    if (headerText.isNotBlank()) {
                        contentBuilder.append("<h2>$headerText</h2>\n")
                    }
                }

                // Process content - clone to avoid modifying original
                val contentClone = innerContent.clone()

                // Remove the header elements we already processed
                contentClone.select("h2.chapter-number, h2.chapter-title").remove()

                // Process images - convert <picture> to simple <img>
                contentClone.select("picture").forEach { picture ->
                    val img = picture.selectFirstOrNull("img")
                    val src = img?.attrOrNull("src")
                    val alt = img?.attrOrNull("alt") ?: "Image"
                    if (!src.isNullOrBlank()) {
                        picture.html("<img src=\"$src\" alt=\"$alt\" />")
                    }
                }

                contentBuilder.append(contentClone.html())
                contentBuilder.append("\n")

            } else {
                // Section might just be an image (cover, insert)
                val pictures = section.select("picture")
                for (picture in pictures) {
                    val img = picture.selectFirstOrNull("img")
                    val src = img?.attrOrNull("src")
                    val alt = img?.attrOrNull("alt") ?: "Image"
                    if (!src.isNullOrBlank()) {
                        contentBuilder.append("<p><img src=\"$src\" alt=\"$alt\" /></p>\n")
                    }
                }
            }

            // Add separator between sections
            contentBuilder.append("<hr/>\n")
        }

        return cleanChapterHtml(contentBuilder.toString())
    }
}