package com.gateannouncer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val langs: List<String> = listOf("en", "ja", "yue"),
    val useClips: Boolean = true,
    val useCloud: Boolean = false,
    val apiKey: String = "",
    val cloudVoices: Map<String, String> = LANGS.associate { it.code to it.defaultVoice },
    val localVoice: String = "",
    val rate: Float = 1.1f,
    val clipSpeed: Float = 1.25f,
    val repeat: Int = 1
)

class Store(context: Context) {
    private val p = context.getSharedPreferences("gate", Context.MODE_PRIVATE)

    fun loadVals(): Map<String, String> = try {
        val o = JSONObject(p.getString("vals", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    fun saveVals(v: Map<String, String>) {
        val o = JSONObject()
        v.filterKeys { it in SAVED_KEYS }.forEach { (k, x) -> o.put(k, x) }
        p.edit().putString("vals", o.toString()).apply()
    }

    fun loadCustom(): List<Template> = try {
        val a = JSONArray(p.getString("custom2", "[]")!!)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val tx = o.getJSONObject("texts")
            Template(o.getString("title"), tx.keys().asSequence().associateWith { k -> tx.getString(k) }, true)
        }
    } catch (e: Exception) { emptyList() }

    fun saveCustom(list: List<Template>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("title", it.title).put("texts", JSONObject(it.texts))) }
        p.edit().putString("custom2", a.toString()).apply()
    }

    fun loadHistory(): List<List<Item>> = try {
        val a = JSONArray(p.getString("hist4", "[]")!!)
        (0 until a.length()).map { i ->
            val g = a.getJSONArray(i)
            (0 until g.length()).map { j ->
                val o = g.getJSONObject(j)
                val pa = o.getJSONArray("p")
                Item(o.getString("l"), o.getString("t"), (0 until pa.length()).map { pa.getString(it) })
            }
        }
    } catch (e: Exception) { emptyList() }

    fun saveHistory(list: List<List<Item>>) {
        val a = JSONArray()
        list.forEach { g ->
            val ga = JSONArray()
            g.forEach { ga.put(JSONObject().put("l", it.lang).put("t", it.text).put("p", JSONArray(it.parts))) }
            a.put(ga)
        }
        p.edit().putString("hist4", a.toString()).apply()
    }

    fun loadSettings(): Settings {
        val d = Settings()
        val langs = (p.getString("langs", "en,ja,yue") ?: "en,ja,yue").split(",").filter { c -> LANGS.any { it.code == c } }
        return Settings(
            langs = langs.ifEmpty { d.langs },
            useClips = p.getBoolean("clips", true),
            useCloud = p.getBoolean("cloud", false),
            apiKey = p.getString("key", "") ?: "",
            cloudVoices = LANGS.associate { it.code to (p.getString("cv_${it.code}", it.defaultVoice) ?: it.defaultVoice) },
            localVoice = p.getString("lvoice", "") ?: "",
            rate = p.getFloat("rate", 1.1f),
            clipSpeed = p.getFloat("cspeed", 1.25f),
            repeat = p.getInt("repeat", 1)
        )
    }

    fun saveSettings(s: Settings) {
        val e = p.edit().putString("langs", s.langs.joinToString(",")).putBoolean("cloud", s.useCloud)
            .putBoolean("clips", s.useClips).putString("key", s.apiKey).putString("lvoice", s.localVoice)
            .putFloat("rate", s.rate).putFloat("cspeed", s.clipSpeed).putInt("repeat", s.repeat)
        s.cloudVoices.forEach { (c, v) -> e.putString("cv_$c", v) }
        e.apply()
    }
}
