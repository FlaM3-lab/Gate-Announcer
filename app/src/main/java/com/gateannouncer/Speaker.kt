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

/**
 * Reads an announcement piece by piece: recorded clips when the voice pack has them,
 * the phone voice for anything else (such as passenger names). Optionally uses Google Cloud voices.
 */
class Speaker(private val context: Context) {
    var onStatus: (String) -> Unit = {}
    private var ready = false
    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var gen = 0
    private var segs: List<Pair<String, String>> = emptyList()
    private var idx = 0
    private var cfg = Settings()
    private var pre: MediaPlayer? = null
    private var preIdx = -1

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
        handler.post { scheduleNext(g) }
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
                    onStatus("Cloud voice unavailable (${e.message}). Using the recorded or phone voice.")
                    playSegments(items, s)
                }
            }
        } else {
            onStatus(if (s.useClips && !Voices.ready) "Voice pack not found. Using the phone voice." else "")
            playSegments(items, s)
        }
    }

    fun stop() {
        gen++
        job?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (ready) tts.stop()
        player?.release()
        player = null
        pre?.release()
        pre = null
        preIdx = -1
    }

    fun shutdown() {
        stop()
        if (ready) tts.shutdown()
        scope.cancel()
    }

    private fun playSegments(items: List<Item>, s: Settings) {
        gen++
        cfg = s
        val one = items.flatMap { item -> item.parts.map { item.lang to it } }
        segs = List(s.repeat) { one }.flatten()
        idx = 0
        nextSeg(gen)
    }

    private fun scheduleNext(g: Int) {
        val cur = segs.getOrNull(idx - 1)?.first
        val next = segs.getOrNull(idx)?.first
        val wait = if (cur != null && next != null && cur != next) 450L else 0L
        handler.postDelayed({ nextSeg(g) }, wait)
    }

    private fun loadClip(i: Int): MediaPlayer? {
        if (!cfg.useClips) return null
        val seg = segs.getOrNull(i) ?: return null
        val f = Voices.clip(seg.first, seg.second) ?: return null
        var mp: MediaPlayer? = null
        return try {
            mp = MediaPlayer()
            mp.setDataSource(f.path)
            mp.prepare()
            mp
        } catch (e: Exception) {
            mp?.release()
            null
        }
    }

    private fun nextSeg(g: Int) {
        if (g != gen || idx >= segs.size) return
        val i = idx++
        val seg = segs[i]
        var mp: MediaPlayer? = null
        if (preIdx == i) {
            mp = pre
            pre = null
            preIdx = -1
        } else {
            mp = loadClip(i)
        }
        if (mp != null) {
            mp.setOnCompletionListener { m ->
                m.release()
                if (player === m) player = null
                scheduleNext(g)
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release()
                if (player === m) player = null
                scheduleNext(g)
                true
            }
            player = mp
            mp.start()
            // get the next clip ready while this one plays, so there is no wait between them
            val nxt = segs.getOrNull(idx)
            if (nxt != null && nxt.first == seg.first) {
                pre = loadClip(idx)
                preIdx = if (pre != null) idx else -1
            }
            return
        }
        speakTts(seg.first, seg.second, g)
    }

    private fun speakTts(lang: String, text: String, g: Int) {
        if (!ready) { onStatus("Phone voice isn’t ready yet. Try again in a moment."); return }
        val l = langOf(lang)
        val r = tts.setLanguage(Locale.forLanguageTag(l.tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            onStatus("The phone has no ${l.name} voice installed. Add one in Android’s text-to-speech settings.")
        }
        if (lang == "en") tts.voices?.firstOrNull { it.name == cfg.localVoice }?.let { tts.voice = it }
        tts.setSpeechRate(cfg.rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "g$g")
    }

    private fun synthesize(item: Item, i: Int, s: Settings): File {
        val voice = s.cloudVoices[item.lang] ?: langOf(item.lang).defaultVoice
        val code = voice.split("-").take(2).joinToString("-")
        val body = JSONObject()
            .put("input", JSONObject().put("text", item.parts.joinToString(" ")))
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
