package eu.kanade.tachiyomi.animeextension.es.monoschinos

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.OkruExtractor
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.SolidFilesExtractor
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.UploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.lang.Exception

class MonosChinos : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "MonosChinos"

    override val baseUrl = "https://monoschinos2.com"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.heromain div.row div.col-md-4"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?p=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val thumbDiv = element.select("a div.series div.seriesimg img")
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a").attr("href"))
            title = element.select("a div.series div.seriesdetails h3").text()
            thumbnail_url = if (thumbDiv.attr("src").contains("/public/img")) {
                thumbDiv.attr("data-src")
            } else {
                thumbDiv.attr("src")
            }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item a.page-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsoup = response.asJsoup()
        val animeId = response.request.url.pathSegments.last().replace("-sub-espanol", "").replace("-080p", "-1080p")
        return jsoup.select("div.col-item").map { it ->
            val epNum = it.attr("data-episode")
            SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                url = "/ver/$animeId-episodio-$epNum"
            }
        }.reversed()
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val headers = headers.newBuilder().add("referer", "https://uqload.com/").build()
        document.select("div.heroarea div.row div.col-md-12 ul.dropcaps li").forEach { it ->
            // val server = it.select("a").text()
            val urlBase64 = it.select("a").attr("data-player")
            val url = Base64.decode(urlBase64, Base64.DEFAULT).toString(Charsets.UTF_8).substringAfter("=")
            when {
                url.contains("fembed") -> videoList.addAll(FembedExtractor().videosFromUrl(url))
                url.contains("ok") -> if (!url.contains("streamcherry")) videoList.addAll(OkruExtractor(client).videosFromUrl(url))
                url.contains("solidfiles") -> videoList.addAll(SolidFilesExtractor(client).videosFromUrl(url))
                url.contains("uqload") -> {
                    val video = UploadExtractor(client).videoFromUrl(url, headers)
                    if (video != null) videoList.add(video)
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) }
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "Fembed:720p")
            val preferredIdx = videoSorted.indexOfFirst { x -> x.quality == userPreferredQuality }
            if (preferredIdx != -1) {
                videoSorted.drop(preferredIdx + 1)
                videoSorted[0] = videoSorted[preferredIdx]
            }
            videoSorted.toList()
        } catch (e: IOException) {
            this
        }
    }

    private fun getNumberFromString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }.ifEmpty { "0" }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.find { it is GenreFilter } as GenreFilter
        val yearFilter = try {
            (filters.find { it is YearFilter } as YearFilter).state.toInt()
        } catch (e: Exception) {
            "false"
        }
        val letterFilter = try {
            (filters.find { it is LetterFilter } as LetterFilter).state.first().uppercase()
        } catch (e: Exception) {
            "false"
        }

        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query&p=$page")
            else -> GET("$baseUrl/animes?categoria=false&genero=${genreFilter.toUriPart()}&fecha=$yearFilter&letra=$letterFilter&p=$page")
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("div.chapterpic img").attr("src")
            title = document.selectFirst("div.chapterdetails h1").text()
            description = document.select("p.textShort").first().ownText()
            genre = document.select("ol.breadcrumb li.breadcrumb-item a").joinToString { it.text() }
            status = parseStatus(document.select("div.butns button.btn1").text())
        }
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Estreno") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
        AnimeFilter.Separator(),
        YearFilter(),
        LetterFilter()
    )

    private class YearFilter : AnimeFilter.Text("Año", "2022")
    private class LetterFilter : AnimeFilter.Text("Letra", "")

    private class GenreFilter : UriPartFilter(
        "Generos",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Latino", "latino"),
            Pair("Castellano", "castellano"),
            Pair("Acción", "acción"),
            Pair("Aventura", "aventura"),
            Pair("Carreras", "carreras"),
            Pair("Comedia", "comedia"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Fantasía", "fantasía"),
            Pair("Gore", "gore"),
            Pair("Harem", "harem"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Lucha", "lucha"),
            Pair("Magia", "magia"),
            Pair("Josei", "josei"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "música"),
            Pair("Parodias", "parodias"),
            Pair("Psicológico", "psicológico"),
            Pair("Recuerdos de la vida", "recuerdos-de-la-vida"),
            Pair("Seinen", "seinen"),
            Pair("Shojo", "shojo"),
            Pair("Shonen", "shonen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Espacial", "espacial"),
            Pair("Histórico", "histórico"),
            Pair("Samurai", "samurai"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Demonios", "demonios"),
            Pair("Romance", "romance"),
            Pair("Policía", " policía"),
            Pair("Historia paralela", "historia-paralela"),
            Pair("Aenime", "aenime"),
            Pair("Donghua", "donghua"),
            Pair("Blu-ray", "blu-ray"),
            Pair("Monogatari", "monogatari")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", // Fembed
            "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", // Okru
            "SolidFiles", "Upload" // video servers without resolution
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Fembed:720p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
