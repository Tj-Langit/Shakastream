package com.lk21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Lk21 : MainAPI() {
    override var mainUrl = "https://tv12.lk21official.cc"
    override var name = "LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/populer" to "Film Terpopuler",
        "$mainUrl/latest" to "Baru Diupload",
        "$mainUrl/rating" to "Rating Tertinggi",
        "$mainUrl/release" to "Rilis Terbaru",
        "$mainUrl/top-movie-today" to "Film Unggulan"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data}/page/$page"
        }
        val document = app.get(url, headers = requestHeaders).document
        val home = document.select("div.gallery-grid article, ul.sliders li.slider article, article[itemtype*='Movie'], article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("figure a[href], a[itemprop='url'], a[href]") ?: return null
        val rawHref = anchor.attr("href")
        if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.contains("/genre/") || rawHref.contains("/country/") || rawHref.contains("/year/")) {
            return null
        }

        val title = this.selectFirst("h3.poster-title, figcaption h3, [itemprop='name']")?.text()?.trim()
            ?: anchor.attr("title")
                .replace(Regex("""^Nonton (movie|series)\s+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+streaming gratis$""", RegexOption.IGNORE_CASE), "")
                .trim()
        if (title.isEmpty()) return null

        val href = fixUrl(rawHref)
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifEmpty {
                img.attr("src").ifEmpty {
                    img.attr("srcset").split(" ").firstOrNull()
                }
            }
        )
        val year = this.selectFirst("span.year, [itemprop='datePublished']")?.text()?.trim()?.toIntOrNull()
        val ratingText = this.selectFirst("span[itemprop='ratingValue'], span.rating")?.text()?.trim()
        val epText = this.selectFirst("span.episode strong")?.text()?.trim()?.toIntOrNull()
        val isSeries = this.selectFirst("span.episode") != null || anchor.attr("title").contains("series", true)

        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                addSub(epText)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = Score.from10(ratingText)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?s=$query", headers = requestHeaders).document
        return document.select("div.gallery-grid article, article[itemtype*='Movie'], article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = requestHeaders).document
        val rawTitle = document.selectFirst("div.movie-info > h1, h1")?.text()?.trim() ?: return null
        val title = rawTitle.replace(Regex("""\(\d{4}\)$"""), "").trim()
        val year = Regex("""\((\d{4})\)$""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val img = document.selectFirst("div.detail img[itemprop='image'], div.movie-info img, picture img")
        val poster = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })
        val plot = document.selectFirst("div.synopsis")?.text()?.trim()
        val tags = document.select("div.tag-list span.tag a[href*='/genre/']").map { it.text().trim() }
        val ratingText = document.selectFirst("span.rating-number")?.text()?.trim()
        val actors = document.select("div.detail a[href*='/artist/']").map { it.text().trim() }

        val recommendations = document.select("ul.video-list > li > a").mapNotNull { el ->
            val recTitle = el.selectFirst("span.video-title")?.text()?.trim() ?: return@mapNotNull null
            val recHref = fixUrl(el.attr("href"))
            val recImg = el.selectFirst("img")
            val recPoster = fixUrlNull(recImg?.attr("data-src")?.ifEmpty { recImg.attr("src") })
            val recYear = el.selectFirst("span.video-year")?.text()?.trim()?.toIntOrNull()
            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = recPoster
                this.year = recYear
            }
        }

        val episodeLinks = document.select("ul#episode-list li a, div.episode-list a").mapNotNull { ep ->
            val epHref = fixUrl(ep.attr("href"))
            val epName = ep.text().trim()
            val epNum = Regex("""(\d+)""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(epHref) {
                this.name = epName
                this.episode = epNum
            }
        }

        return if (episodeLinks.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeLinks) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingText)
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingText)
                addActors(actors)
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun extractVideoNode(
        playerUrl: String,
        serverLabel: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = fixUrl(playerUrl)
        val collected = mutableListOf<ExtractorLink>()
        loadExtractor(cleanUrl, referer, subtitleCallback) { link ->
            collected.add(link)
        }

        for (link in collected) {
            callback.invoke(
                newExtractorLink(
                    source = link.source,
                    name = "${link.name} [$serverLabel]",
                    url = link.url,
                    type = link.type
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.headers = link.headers
                }
            )
        }

        try {
            val res = app.get(cleanUrl, referer = referer, headers = requestHeaders)
            val html = res.text
            val doc = res.document

            val nestedLinks = mutableListOf<ExtractorLink>()
            doc.select("iframe[src]").forEach { iframe ->
                val nestedSrc = fixUrl(iframe.attr("src"))
                if (nestedSrc.startsWith("http") && nestedSrc != cleanUrl) {
                    loadExtractor(nestedSrc, cleanUrl, subtitleCallback) { link ->
                        nestedLinks.add(link)
                    }
                }
            }

            for (link in nestedLinks) {
                callback.invoke(
                    newExtractorLink(
                        source = link.source,
                        name = "${link.name} [$serverLabel]",
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.headers = link.headers
                    }
                )
            }

            val unescaped = html.replace("\\/", "/")
            Regex("""["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']""")
                .findAll(unescaped)
                .map { it.groupValues[1] }
                .distinct()
                .forEach { streamUrl ->
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} [$serverLabel]",
                            url = streamUrl,
                            type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = cleanUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
        } catch (_: Exception) {
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = requestHeaders).document
        val servers = mutableMapOf<String, String>()

        document.select("ul#player-list li a[data-url]").forEach { el ->
            val url = el.attr("data-url").trim()
            val label = el.text().trim().ifEmpty { el.attr("data-server").uppercase() }
            if (url.isNotEmpty()) {
                servers[url] = label
            }
        }

        if (servers.isEmpty()) {
            document.selectFirst("iframe#main-player")?.attr("src")?.let { src ->
                if (src.isNotEmpty()) {
                    servers[src] = "Default"
                }
            }
        }

        for ((serverUrl, label) in servers) {
            extractVideoNode(serverUrl, label, data, subtitleCallback, callback)
        }

        return true
    }
}
