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

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Film Terpopuler",
        "$mainUrl/latest/page/" to "Baru Diupload",
        "$mainUrl/rating/page/" to "Rating Tertinggi",
        "$mainUrl/release/page/" to "Rilis Terbaru",
        "$mainUrl/top-movie-today/page/" to "Film Unggulan"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data.removeSuffix("/page/")
        } else {
            request.data + page
        }
        val document = app.get(url).document
        val home = document.select("article[itemtype*='schema.org/Movie']").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("figure > a") ?: return null
        val title = this.selectFirst("h3.poster-title")?.text()?.trim()
            ?: anchor.attr("title").replace("Nonton movie ", "").replace("Nonton series ", "").replace(" streaming gratis", "").trim()
        if (title.isEmpty()) return null

        val href = fixUrl(anchor.attr("href"))
        val img = this.selectFirst("img[itemprop='image']")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })
        val year = this.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val ratingText = this.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()
        val epText = this.selectFirst("span.episode strong")?.text()?.trim()?.toIntOrNull()
        val isSeries = this.selectFirst("span.episode") != null

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
        val document = app.get("$mainUrl/search?s=$query").document
        return document.select("article[itemtype*='schema.org/Movie']").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("div.movie-info > h1")?.text()?.trim() ?: return null
        val title = rawTitle.replace(Regex("""\(\d{4}\)$"""), "").trim()
        val year = Regex("""\((\d{4})\)$""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val img = document.selectFirst("div.detail img[itemprop='image']")
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

        // Cek apakah halaman memiliki daftar episode (untuk Series)
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

        // Ekstraksi langsung jika menggunakan wrapper iframe videonode.de (p2p / cast / hydrax)
        try {
            val res = app.get(cleanUrl, referer = referer)
            val html = res.text
            val doc = res.document

            // Cek nested iframe di dalam videonode.de
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

            // Cari stream .m3u8 atau .mp4 langsung di dalam script halaman player
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
        val document = app.get(data).document
        val servers = mutableMapOf<String, String>()

        // 1. Ambil semua server dari daftar ul#player-list (P2P, CAST, HYDRAX)
        document.select("ul#player-list li a[data-url]").forEach { el ->
            val url = el.attr("data-url").trim()
            val label = el.text().trim().ifEmpty { el.attr("data-server").uppercase() }
            if (url.isNotEmpty()) {
                servers[url] = label
            }
        }

        // 2. Fallback dari iframe utama jika daftar kosong
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
