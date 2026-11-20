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

    private suspend fun extractIframeVideo(
        iframeUrl: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = fixUrl(iframeUrl)
        // 1. Jalankan extractor bawaan CloudStream (mendukung mp4upload, pixeldrain, krakenfiles, vidhide, dll)
        loadExtractor(cleanUrl, referer, subtitleCallback, callback)

        // 2. Ekstraksi langsung dari HTML player lokal (DesuStream / Odesu / dll)
        try {
            val res = app.get(cleanUrl, referer = referer)
            val playerHtml = res.text
            val doc = res.document

            // Cek tag <video> atau <source src="...">
            doc.select("video source[src], video[src]").forEach { el ->
                val src = fixUrl(el.attr("src"))
                if (src.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} ($label)",
                            url = src,
                            type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = cleanUrl
                            this.quality = getQualityFromName(label)
                        }
                    )
                }
            }

            // Cari URL video di dalam blok <script> (termasuk karakter backslash \/ yang di-escape)
            val unescapedHtml = playerHtml.replace("\\/", "/")
            val videoRegex = Regex("""["'](https?://[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)["']""")
            videoRegex.findAll(unescapedHtml).map { it.groupValues[1] }.distinct().forEach { videoUrl ->
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} ($label)",
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = cleanUrl
                        this.quality = getQualityFromName(label)
                    }
                )
            }

            // Cek jika ada iframe bersarang di dalamnya
            doc.select("iframe[src]").forEach { nested ->
                val nestedSrc = fixUrl(nested.attr("src"))
                if (nestedSrc.startsWith("http") && nestedSrc != cleanUrl) {
                    loadExtractor(nestedSrc, cleanUrl, subtitleCallback, callback)
                }
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
        val res = app.get(data)
        val document = res.document
        val htmlText = res.text

        // 1. Cek iframe default pada halaman episode
        document.select("div.responsive-embed-stream iframe, #pembed iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                extractIframeVideo(src, "Default", data, subtitleCallback, callback)
            }
        }

        // 2. Kumpulkan seluruh kandidat hash 32-karakter dari script halaman episode
        val scriptText = document.select("script").joinToString("\n") { it.data() }
        val hashes = Regex("""["']([a-f0-9]{32})["']""")
            .findAll(scriptText.ifEmpty { htmlText })
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        var validNonce = ""
        var validPlayerAction = ""

        // Uji kandidat hash untuk mendapatkan token nonce yang valid
        for (candidate in hashes) {
            try {
                val testRes = app.post(
                    ajaxUrl,
                    data = mapOf("action" to candidate),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
                if (testRes.trim().startsWith("{")) {
                    val nonceVal = JSONObject(testRes).optString("data")
                    if (nonceVal.isNotEmpty() && nonceVal != "false" && nonceVal != "0") {
                        validNonce = nonceVal
                        validPlayerAction = hashes.firstOrNull { it != candidate } ?: ""
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Dekode tombol mirror stream (360p, 480p, 720p)
        if (validNonce.isNotEmpty() && validPlayerAction.isNotEmpty()) {
            document.select("div.mirrorstream ul").forEach { ul ->
                val qualityText = ul.className().replace(Regex("[^0-9p]"), "").ifEmpty { "HD" }
                ul.select("li a[data-content]").forEach { el ->
                    val serverName = el.text().trim()
                    try {
                        val encodedData = el.attr("data-content")
                        val decodedJson = String(Base64.decode(encodedData, Base64.DEFAULT))
                        val jsonObj = JSONObject(decodedJson)

                        val postData = mapOf(
                            "id" to jsonObj.optString("id"),
                            "i" to jsonObj.optString("i"),
                            "q" to jsonObj.optString("q"),
                            "nonce" to validNonce,
                            "action" to validPlayerAction
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
                                extractIframeVideo(iframeSrc, "$serverName $qualityText", data, subtitleCallback, callback)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // 4. Ikuti tautan pada kotak download (sering berisi Pixeldrain, Krakenfiles, Mp4upload, Acefile)
        document.select("div.download ul li a").forEach { link ->
            val href = link.attr("href")
            if (href.startsWith("http")) {
                try {
                    // Ikuti pengalihan (safelink/desudrive) jika ada
                    val resolved = app.get(href, referer = data)
                    val finalUrl = resolved.url
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                } catch (_: Exception) {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
