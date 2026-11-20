package com.idlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class Idlix : MainAPI() {
    override var mainUrl = "https://z3.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/trending/page/" to "Trending",
        "$mainUrl/movie/page/" to "Film Terbaru",
        "$mainUrl/tvshows/page/" to "TV Series",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/animation/page/" to "Animation",
        "$mainUrl/genre/drama/page/" to "Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.items > article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        val quality = this.selectFirst("span.quality")?.text()
        val isSeries = href.contains("/tvshows/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addQuality(quality ?: "")
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality ?: "")
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.result-item").mapNotNull {
            val title = it.selectFirst("div.title > a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("div.title > a")?.attr("href") ?: return@mapNotNull null)
            val posterUrl = fixUrlNull(it.selectFirst("div.image img")?.attr("src"))
            val isSeries = href.contains("/tvshows/")
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.sheader > div.data > h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.sheader > div.poster > img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        val description = document.select("div.wp-content > p").text().trim()
        val tags = document.select("div.sgeneros > a").map { it.text() }
        val year = document.selectFirst("span.date")?.text()?.takeLast(4)?.toIntOrNull()
        val isSeries = url.contains("/tvshows/")

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div#seasons > div.se-c").forEach { seasonEl ->
                val seasonNum = seasonEl.selectFirst("span.se-t")?.text()?.trim()?.toIntOrNull() ?: 1
                seasonEl.select("ul.episodios > li").forEach { epEl ->
                    val epLink = fixUrl(epEl.selectFirst("div.episodiotitle > a")?.attr("href") ?: return@forEach)
                    val epName = epEl.selectFirst("div.episodiotitle > a")?.text()?.trim()
                    val epNumText = epEl.selectFirst("div.numerando")?.text()?.split("-")?.getOrNull(1)?.trim()
                    val epNum = epNumText?.toIntOrNull()
                    episodes.add(
                        newEpisode(epLink) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    // Dekripsi CryptoJS AES bawaan DooPlay / Idlix
    private fun decryptCryptoJsAes(cipherJsonStr: String, passphrase: String): String {
        return try {
            val json = JSONObject(cipherJsonStr)
            val ct = Base64.decode(json.getString("ct"), Base64.DEFAULT)
            val iv = json.getString("iv").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val salt = json.getString("s").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            var derived = ByteArray(0)
            var block = ByteArray(0)
            val md5 = MessageDigest.getInstance("MD5")
            while (derived.size < 32) {
                md5.reset()
                if (block.isNotEmpty()) md5.update(block)
                md5.update(passBytes)
                md5.update(salt)
                block = md5.digest()
                derived += block
            }
            val keySpec = SecretKeySpec(derived.copyOfRange(0, 32), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        document.select("ul#playeroptionsul > li.dooplay_player_option").forEach { option ->
            val postId = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")
            if (postId.isEmpty() || nume == "trailer") return@forEach

            try {
                val resText = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text

                val json = JSONObject(resText)
                var embedUrl = json.optString("embed_url")
                val key = json.optString("key")

                // Jika embed_url terenkripsi JSON CryptoJS
                if (embedUrl.trim().startsWith("{") && key.isNotEmpty()) {
                    val decrypted = decryptCryptoJsAes(embedUrl, key).replace("\\/", "/").trim('"')
                    embedUrl = if (decrypted.contains("<iframe")) {
                        Jsoup.parse(decrypted).selectFirst("iframe")?.attr("src") ?: decrypted
                    } else {
                        decrypted
                    }
                }

                val cleanEmbed = fixUrl(embedUrl)
                if (cleanEmbed.startsWith("http")) {
                    // 1. Jalankan extractor standar CloudStream
                    loadExtractor(cleanEmbed, "$mainUrl/", subtitleCallback, callback)

                    // 2. Ekstraksi langsung dari player jeniusplay / idlix
                    if (cleanEmbed.contains("jeniusplay")) {
                        val hash = cleanEmbed.substringAfterLast("/").substringBefore("?")
                        val playerHost = Regex("""https?://[^/]+""").find(cleanEmbed)?.value ?: "https://jeniusplay.com"
                        val postRes = app.post(
                            "$playerHost/player/index.php?data=$hash&do=getVideo",
                            data = mapOf("hash" to hash, "r" to "$mainUrl/"),
                            referer = cleanEmbed,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        ).text

                        if (postRes.trim().startsWith("{")) {
                            val vidJson = JSONObject(postRes)
                            val videoSource = vidJson.optString("videoSource")
                            if (videoSource.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} Server $nume",
                                        url = videoSource,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = cleanEmbed
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                            }
                        }

                        // Ambil subtitle Indonesia jika tersedia di halaman jeniusplay
                        val playerHtml = app.get(cleanEmbed, referer = "$mainUrl/").text
                        Regex("""["'](https?://[^"']+\.(?:vtt|srt))["']""").findAll(playerHtml).forEach { subMatch ->
                            subtitleCallback.invoke(
                                SubtitleFile("Indonesian", subMatch.groupValues[1])
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return true
    }
}
