package com.gateannouncer

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Reads an announcement in one or more languages, one after another, with the phone voice or Google Cloud voices. */
class Speaker(private val context: Context) {
    var onStatus: (String) -> Unit = {}
    private var ready = false
    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var gen = 0
    private var queue: List<Item> = emptyList()
    private var idx = 0
    private var cfg = Settings()

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = advance(id)
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = advance(id)
            })
        }
    }

    private fun advance(id: String?) {
        val g = id?.removePrefix("g")?.toIntOrNull() ?: return
        handler.postDelayed({ nextLocal(g) }, 500)
    }

    fun voices(): List<String> =
        try { tts.voices?.filter { it.locale.language == "en" }?.map { it.name }?.sorted() ?: emptyList() }
        catch (e: Exception) { emptyList() }

    fun speak(items: List<Item>, s: Settings) {
        stop()
        if (items.isEmpty()) return
        if (s.useCloud && s.apiKey.isNotBlank()) {
            onStatus("Loading cloud voice…")
            val g = gen
            job = scope.launch {
                try {
                    val files = coroutineScope {
                        items.mapIndexed { i, x -> async(Dispatchers.IO) { synthesize(x, i, s) } }.awaitAll()
                    }
                    onStatus("")
                    playAll(files, s.repeat, g)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onStatus("Cloud voice unavailable (${e.message}). Using phone voice.")
                    local(items, s)
                }
            }
        } else {
            onStatus("")
            local(items, s)
        }
    }

    fun stop() {
        gen++
        job?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (ready) tts.stop()
        player?.release()
        player = null
    }

    fun shutdown() {
        stop()
        if (ready) tts.shutdown()
        scope.cancel()
    }

    private fun local(items: List<Item>, s: Settings) {
        if (!ready) { onStatus("Phone voice isn’t ready yet. Try again in a moment."); return }
        gen++
        cfg = s
        queue = List(s.repeat) { items }.flatten()
        idx = 0
        nextLocal(gen)
    }

    private fun nextLocal(g: Int) {
        if (g != gen || idx >= queue.size) return
        val item = queue[idx++]
        val lang = langOf(item.lang)
        val r = tts.setLanguage(Locale.forLanguageTag(lang.tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            onStatus("The phone has no ${lang.name} voice installed. Add one in Android’s text-to-speech settings.")
        }
        if (lang.code == "en") tts.voices?.firstOrNull { it.name == cfg.localVoice }?.let { tts.voice = it }
        tts.setSpeechRate(cfg.rate)
        tts.speak(item.speech, TextToSpeech.QUEUE_FLUSH, null, "g$g")
    }

    private fun synthesize(item: Item, i: Int, s: Settings): File {
        val voice = s.cloudVoices[item.lang] ?: langOf(item.lang).defaultVoice
        val code = voice.split("-").take(2).joinToString("-")
        val body = JSONObject()
            .put("input", JSONObject().put("text", item.speech))
            .put("voice", JSONObject().put("languageCode", code).put("name", voice))
            .put("audioConfig", JSONObject().put("audioEncoding", "MP3").put("speakingRate", s.rate.toDouble()))
        val url = URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=${s.apiKey.trim()}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) throw IOException("error ${conn.responseCode} for ${item.lang}")
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val bytes = Base64.decode(json.getString("audioContent"), Base64.DEFAULT)
        return File(context.cacheDir, "announcement_$i.mp3").also { it.writeBytes(bytes) }
    }

    private fun playAll(files: List<File>, times: Int, g: Int) {
        val list = List(times) { files }.flatten()
        var i = 0
        fun next() {
            if (g != gen || i >= list.size) { player = null; return }
            val mp = MediaPlayer().apply { setDataSource(list[i++].path); prepare() }
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                handler.postDelayed({ next() }, 500)
            }
            player = mp
            mp.start()
        }
        next()
    }
}
