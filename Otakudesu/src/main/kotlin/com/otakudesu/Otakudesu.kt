package com.otakudesu

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
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
        val res = app.get(data)
        val document = res.document
        val htmlText = res.text

        // 1. Cek iframe bawaan halaman jika langsung tersedia
        document.select("div.responsive-embed-stream iframe, #pembed iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.startsWith("http")) {
                loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
            }
        }

        // 2. Ekstrak kedua hash action AJAX secara dinamis dari script halaman
        val actionHashes = Regex("""action\s*:\s*["']([a-f0-9]{32})["']""")
            .findAll(htmlText)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        val nonceAction = actionHashes.getOrNull(0) ?: "aa1208d27f29ca340c92c66d1926f13f"
        val playerAction = actionHashes.getOrNull(1) ?: "2a3505c93b0035d3f455df82bf976b84"
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        val nonceRes = app.post(
            ajaxUrl,
            data = mapOf("action" to nonceAction),
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val nonce = try {
            JSONObject(nonceRes).optString("data")
        } catch (_: Exception) {
            ""
        }

        // 3. Dekode tombol mirror stream (data-content Base64)
        if (nonce.isNotEmpty()) {
            document.select("div.mirrorstream ul li a[data-content]").forEach { el ->
                try {
                    val encodedData = el.attr("data-content")
                    val decodedJson = String(Base64.decode(encodedData, Base64.DEFAULT))
                    val jsonObj = JSONObject(decodedJson)

                    val postData = mapOf(
                        "id" to jsonObj.optString("id"),
                        "i" to jsonObj.optString("i"),
                        "q" to jsonObj.optString("q"),
                        "nonce" to nonce,
                        "action" to playerAction
                    )

                    val playerRes = app.post(
                        ajaxUrl,
                        data = postData,
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).text

                    val embedHtmlBase64 = JSONObject(playerRes).optString("data")
                    if (embedHtmlBase64.isNotEmpty()) {
                        val iframeHtml = String(Base64.decode(embedHtmlBase64, Base64.DEFAULT))
                        val iframeSrc = Jsoup.parse(iframeHtml).selectFirst("iframe")?.attr("src")
                        if (!iframeSrc.isNullOrEmpty()) {
                            val cleanSrc = fixUrl(iframeSrc)
                            loadExtractor(cleanSrc, "$mainUrl/", subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 4. Ikuti link pengalihan dari kotak download jika mengarah ke host video langsung
        document.select("div.download ul li a").forEach { link ->
            val href = link.attr("href")
            if (href.startsWith("http")) {
                loadExtractor(href, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }
}
