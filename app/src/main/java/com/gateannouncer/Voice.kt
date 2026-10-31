package com.gateannouncer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The recorded voice pack (assets/voices.zip): many short audio clips plus a manifest of word tables. */
object Voices {
    private var dir: File? = null
    private var man: JSONObject? = null
    @Volatile var ready = false

    fun init(ctx: Context) {
        Thread {
            try {
                val d = File(ctx.filesDir, "voices")
                @Suppress("DEPRECATION")
                val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
                val marker = File(d, ".stamp")
                if (!(marker.exists() && marker.readText() == stamp)) {
                    d.deleteRecursively()
                    d.mkdirs()
                    ctx.assets.open("voices.zip").use { input ->
                        ZipInputStream(input).use { z ->
                            var e = z.nextEntry
                            while (e != null) {
                                if (!e.isDirectory) File(d, e.name.substringAfterLast('/')).outputStream().use { z.copyTo(it) }
                                e = z.nextEntry
                            }
                        }
                    }
                    marker.writeText(stamp)
                }
                man = JSONObject(File(d, "manifest.json").readText())
                dir = d
                ready = true
            } catch (e: Exception) {
                ready = false
            }
        }.start()
    }

    fun clipId(lang: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest("$lang|$text".toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }.take(12)
    }

    fun clip(lang: String, text: String): File? {
        val d = dir ?: return null
        val f = File(d, clipId(lang, text) + ".mp3")
        return if (f.exists()) f else null
    }

    private fun strs(table: String, lang: String): List<String> {
        val a = man?.optJSONObject(table)?.optJSONArray(lang) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    fun digits(lang: String) = strs("digits", lang)
    fun letters(lang: String) = strs("letters", lang)
    fun hours(lang: String) = strs("hours", lang)
    fun mins(lang: String) = strs("mins", lang)
    fun periods(lang: String) = strs("periods", lang)
}

private val SLOT = Regex("\\{(\\w+)\\}")
private const val STRIP = ",，、;；:：.。!！?？"

private fun addFixed(seg: String, out: MutableList<String>) {
    var s = seg.trim()
    while (s.isNotEmpty() && s[0] in STRIP) s = s.substring(1).trim()
    if (s.any { it.isLetterOrDigit() }) out.add(s)
}

/** Reads a code such as a flight number, gate or seat one character at a time. */
private fun code(v: String, lang: String, out: MutableList<String>) {
    val dg = Voices.digits(lang)
    val lt = Voices.letters(lang)
    for (ch in v) {
        if (ch in '0'..'9') {
            out.add(if (dg.size == 10) dg[ch - '0'] else ch.toString())
        } else if (ch.isLetter() && ch.uppercaseChar() in 'A'..'Z') {
            out.add(if (lt.size == 26) lt[ch.uppercaseChar() - 'A'] else ch.toString())
        }
    }
}

private fun timeParts(v: String, lang: String, out: MutableList<String>) {
    val m = Regex("^(\\d{1,2})\\s*[:：.]?\\s*(\\d{2})$").find(v.trim())
    if (m == null) { out.add(v); return }
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    if (h > 23 || mi > 59) { out.add(v); return }
    val pe = Voices.periods(lang)
    val hr = Voices.hours(lang)
    val mn = Voices.mins(lang)
    if (pe.size < 3 || hr.size < 13 || mn.size < 60) { out.add(fmtTime(v, lang)); return }
    val h12 = if (h % 12 == 0) 12 else h % 12
    val pi = if (h < 12) 0 else if (h < 18) 1 else 2
    when (lang) {
        "en" -> { out.add(hr[h12]); if (mi > 0) out.add(mn[mi]); out.add(pe[pi]) }
        "ja" -> { out.add(pe[pi]); out.add(hr[h % 12]); if (mi > 0) out.add(mn[mi]) }
        else -> { out.add(pe[pi]); out.add(hr[h12]); if (mi > 0) out.add(mn[mi]) }
    }
}

private fun paxParts(pax: List<Passenger>, lang: String, out: MutableList<String>) {
    val list = pax.filter { it.name.isNotBlank() }
    list.forEachIndexed { i, p ->
        val ti = CHOICES.getValue("title").getOrNull(p.title)?.text?.get(lang).orEmpty()
        val seat = p.seat.trim()
        when (lang) {
            "ja" -> {
                if (seat.isNotEmpty()) { out.add("お座席"); code(seat, lang, out); out.add("の") }
                out.add(p.name.trim())
                out.add("様")
            }
            "yue" -> {
                if (seat.isNotEmpty()) { out.add("座位"); code(seat, lang, out); out.add("的") }
                out.add(p.name.trim())
                if (ti.isNotEmpty()) out.add(ti)
            }
            else -> {
                if (ti.isNotEmpty()) out.add(ti)
                out.add(p.name.trim())
                if (seat.isNotEmpty()) { out.add("seat"); code(seat, lang, out) }
                if (i < list.size - 1) out.add("and")
            }
        }
    }
}

private fun slot(k: String, vals: Map<String, String>, pax: List<Passenger>, lang: String, out: MutableList<String>) {
    val v = vals[k]?.trim().orEmpty()
    val choices = CHOICES[k]
    when {
        k == "codeshare" -> if (vals["csoff"] != "1") {
            val phrase = CODESHARE[lang].orEmpty()
            val i = phrase.indexOf("{cx}")
            if (i < 0) {
                addFixed(phrase, out)
            } else {
                addFixed(phrase.substring(0, i), out)
                code(vals["cx"]?.trim().orEmpty(), lang, out)
                addFixed(phrase.substring(i + 4), out)
            }
        }
        k == "passengers" -> paxParts(pax, lang, out)
        k == "dest" -> out.add(DEST[lang] ?: "Hong Kong")
        v.isEmpty() -> {}
        choices != null -> choices.getOrNull(v.toIntOrNull() ?: -1)?.text?.get(lang)?.let { addFixed(it, out) }
        k == "etd" -> timeParts(v, lang, out)
        k == "flight" || k == "cx" || k == "gate" -> code(v, lang, out)
        else -> out.add(v)
    }
}

/** Splits one language's announcement into short pieces, each of which can be a recorded clip. */
fun speechParts(t: Template, vals: Map<String, String>, pax: List<Passenger>, lang: String): List<String> {
    val text = t.textFor(lang)
    val out = mutableListOf<String>()
    var last = 0
    for (m in SLOT.findAll(text)) {
        addFixed(text.substring(last, m.range.first), out)
        slot(m.groupValues[1], vals, pax, lang, out)
        last = m.range.last + 1
    }
    addFixed(text.substring(last), out)
    return out
}
