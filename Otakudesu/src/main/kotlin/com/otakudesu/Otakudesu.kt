package com.otakudesu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Otakudesu : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/" to "Anime On-Going",
        "$mainUrl/complete-anime/page/" to "Anime Complete"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.venz > ul > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.thumb > a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("div.thumbz > img")?.attr("src"))
        val epNum = this.selectFirst("div.epz")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=anime").document
        return document.select("ul.chivsrc > li").mapNotNull {
            val title = it.selectFirst("h2 > a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("h2 > a")?.attr("href") ?: return@mapNotNull null)
            val posterUrl = fixUrlNull(it.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.jdlrx > h1")?.text()?.replace("Subtitle Indonesia", "")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.fotoanime > img")?.attr("src"))
        val description = document.select("div.sinopc > p").text().trim()
        val tags = document.select("div.infozingle > p:contains(Genre) > a").map { it.text() }
        val statusText = document.selectFirst("div.infozingle > p:contains(Status)")?.text() ?: ""
        val status = if (statusText.contains("Ongoing", true)) ShowStatus.Ongoing else ShowStatus.Completed

        val episodes = document.select("div.episodelist").getOrNull(1)?.select("ul > li")?.mapNotNull {
            val name = it.selectFirst("a")?.text() ?: return@mapNotNull null
            val link = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val epNum = Regex("Episode\\s+(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) {
                this.name = name
                this.episode = epNum
            }
        }?.reversed() ?: emptyList()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Mengambil iframe pemutar utama
        document.select("div.responsive-embed-stream iframe, #pembed iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotEmpty()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // Mengambil link dari daftar server mirror / download
        document.select("div.mirrorstream ul li a, div.download ul li a").forEach { link ->
            val href = link.attr("href")
            if (href.startsWith("http")) {
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
