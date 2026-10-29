package com.gateannouncer

/** To add a language: add a Lang here, then add its text to each template below (custom templates fall back to English). */
data class Lang(val code: String, val name: String, val tag: String, val defaultVoice: String)

val LANGS = listOf(
    Lang("en", "English", "en-US", "en-US-Neural2-F"),
    Lang("ja", "Japanese 日本語", "ja-JP", "ja-JP-Neural2-B"),
    Lang("yue", "Cantonese 廣東話", "zh-HK", "yue-HK-Standard-A")
)

fun langOf(code: String) = LANGS.firstOrNull { it.code == code } ?: LANGS[0]

/** One spoken part of an announcement: [text] is shown, [speech] is what is read aloud. */
data class Item(val lang: String, val text: String, val speech: String = text)

/** A tap-to-choose field. The saved value is the option's index; [text] holds the wording per language. */
data class Choice(val label: String, val text: Map<String, String>)

val CHOICES: Map<String, List<Choice>> = mapOf(
    "greet" to listOf(
        Choice("Morning", mapOf("en" to "Good morning", "ja" to "おはようございます", "yue" to "早晨")),
        Choice("Afternoon", mapOf("en" to "Good afternoon", "ja" to "こんにちは", "yue" to "午安")),
        Choice("Evening", mapOf("en" to "Good evening", "ja" to "こんばんは", "yue" to "晚安"))
    ),
    "reason" to listOf(
        Choice("Bad weather", mapOf("en" to "bad weather", "ja" to "天候不良", "yue" to "惡劣天氣")),
        Choice("Aircraft switch", mapOf("en" to "an aircraft switch", "ja" to "機材変更", "yue" to "航機調動")),
        Choice("Operational reason", mapOf("en" to "operational reasons", "ja" to "運航上の都合", "yue" to "航務運作")),
        Choice("Technical issue", mapOf("en" to "a technical issue", "ja" to "機材トラブル", "yue" to "機件故障")),
        Choice("Air traffic control", mapOf("en" to "air traffic control", "ja" to "航空管制からの指示", "yue" to "航空管制")),
        Choice("Late aircraft arrival", mapOf("en" to "the late arrival of the aircraft", "ja" to "使用機材の到着遅延", "yue" to "航機延遲抵港"))
    ),
    "title" to listOf(
        Choice("Mr.", mapOf("en" to "Mr.", "ja" to "", "yue" to "先生")),
        Choice("Ms.", mapOf("en" to "Ms.", "ja" to "", "yue" to "女士"))
    ),
    "kind" to listOf(
        Choice("Departure", mapOf("en" to "departure", "ja" to "出発", "yue" to "起飛")),
        Choice("Boarding", mapOf("en" to "boarding", "ja" to "搭乗", "yue" to "登機"))
    )
)

private val KEY = Regex("\\{(\\w+)\\}")

data class Template(
    val title: String,
    val texts: Map<String, String>,
    val custom: Boolean = false,
    val group: String = "Custom"
) {
    val keys: List<String>
        get() = texts.values.flatMap { v -> KEY.findAll(v).map { it.groupValues[1] }.toList() }.distinct()
    fun hasLang(code: String) = !texts[code].isNullOrBlank()
    fun textFor(code: String) = if (hasLang(code)) texts[code]!! else texts["en"].orEmpty()
}

val SAVED_KEYS = setOf("flight", "cx", "dest", "gate")

private val LABELS = mapOf(
    "flight" to "HK Express flight number (UO)", "cx" to "Cathay codeshare number (CX)",
    "dest" to "Destination", "gate" to "Gate", "name" to "Passenger name",
    "etd" to "New time", "reason" to "Reason", "title" to "Title",
    "kind" to "New time is for", "greet" to "Time of day"
)

fun label(k: String) = LABELS[k] ?: k.replace('_', ' ')

private fun spaceDigits(v: String) =
    if (Regex("^[A-Za-z]{0,3}\\s*\\d+$").matches(v)) v.replace(Regex("(\\d)"), "\$1 ").trim() else v

/** Turns 24-hour input like 18:45 or 1845 into wording for each language. */
private fun fmtTime(v: String, lang: String): String {
    val m = Regex("^(\\d{1,2})\\s*[:：.]?\\s*(\\d{2})$").find(v.trim()) ?: return v
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    if (h > 23 || mi > 59) return v
    val h12 = if (h % 12 == 0) 12 else h % 12
    return when (lang) {
        "en" -> "$h12:${"%02d".format(mi)} ${if (h < 12) "AM" else "PM"}"
        "ja" -> (if (h < 12) "午前" else "午後") + "${h % 12}時" + (if (mi == 0) "" else "${mi}分")
        "yue" -> (if (h < 12) "上午" else if (h < 18) "下午" else "晚上") + "${h12}時" + (if (mi == 0) "" else "${mi}分")
        else -> v
    }
}

fun fill(t: Template, vals: Map<String, String>, lang: String, forSpeech: Boolean): String =
    KEY.replace(t.textFor(lang)) { m ->
        val k = m.groupValues[1]
        val v = vals[k]?.trim().orEmpty()
        val choices = CHOICES[k]
        val blank = "[${label(k).lowercase()}]"
        when {
            v.isEmpty() -> blank
            choices != null -> choices.getOrNull(v.toIntOrNull() ?: -1)?.text?.get(lang) ?: blank
            k == "etd" -> fmtTime(v, lang)
            forSpeech && (k == "flight" || k == "cx") -> spaceDigits(v)
            else -> v
        }
    }

fun build(t: Template, vals: Map<String, String>, langs: List<String>): List<Item> =
    langs.map { Item(it, fill(t, vals, it, false), fill(t, vals, it, true)) }

private fun t(group: String, title: String, en: String, ja: String, yue: String) =
    Template(title, mapOf("en" to en, "ja" to ja, "yue" to yue), false, group)

/** Row-boarding announcements: first wave, optional second wave, then everyone remaining. */
private fun rowBoarding(group: String, a1: Int, b1: Int, second: Pair<Int, Int>?): List<Template> {
    val out = mutableListOf<Template>()
    out += t(group, "Rows $a1–$b1",
        "{greet} ladies and gentlemen. May I have your attention please, welcome to HK Express flight UO {flight}, codeshare with Cathay Pacific flight CX {cx}, departing to {dest}. We would like to invite Guests who are seated in rows $a1 to $b1 to board the aircraft. Kindly ensure that your boarding pass and travel documents are ready for secondary document check before boarding. All other Guests please remain seated, thank you!",
        "皆様、{greet}。香港エクスプレスからご案内いたします。香港エクスプレス {dest}行き UO{flight}便、キャセイパシフィック航空とのコードシェア CX{cx}便のご利用、誠にありがとうございます。これより、お座席列 ${a1}から${b1}列目にお座りのお客様のご搭乗を開始いたします。搭乗口で搭乗券とパスポートを再度確認いたしますので、お手元にご用意の上、搭乗口までお進みください。その他の列のお客様は、次のご案内まで今しばらくお待ちください。",
        "各位旅客{greet}！歡迎乘搭 HK Express UO{flight}，代號共享航班：國泰航空 CX{cx}前往{dest}。現在我們邀請第 $a1 到 $b1 排的旅客登機。登機時請出示登機證及旅行證件以便地勤人員再次檢查。其他旅客請稍候片刻，多謝！")
    if (second != null) {
        val (a2, b2) = second
        out += t(group, "Rows $a2–$b2",
            "Guests who are seated in rows $a2 to $b2 may board now. Thank you.",
            "お座席列 ${a2}から${b2}列目にお座りのお客様はただいまよりご搭乗ください。",
            "現在，我們請第 $a2 到 $b2 排的旅客登機。多謝。")
    }
    out += t(group, "All remaining",
        "Ladies and gentlemen, would all remaining Guests travelling on HK Express flight UO {flight}, codeshare with Cathay Pacific flight CX {cx}, departing to {dest}, please board now. Thank you.",
        "お待たせいたしました。香港エクスプレス {dest}行き UO{flight}便、キャセイパシフィック航空とのコードシェア CX{cx}便のご利用のすべてのお客様、ただいまよりご搭乗ください。",
        "乘搭 HK Express UO{flight}，代號共享航班：國泰航空 CX{cx}前往{dest}的所有旅客，請現在登機。多謝。")
    return out
}

val baseTemplates: List<Template> = listOf(
    t("Check-in", "Web check-in reminder",
        "{greet}. May I have your attention please. HK Express flight UO {flight} departing to {dest}. We would like to invite all Guests who have used web or mobile check-in and have not been to our check-in counter to proceed to boarding gate {gate} for document verification. Thank you for your cooperation.",
        "皆様、{greet}。香港エクスプレスからご案内いたします。香港エクスプレス UO{flight}便、{dest}行きにご搭乗予定で、オンラインチェックインをされたお客様のうち、チェックインカウンターにお立ち寄りにならなかったお客様は、ご搭乗前にパスポートなどの確認がございますので、{gate}番搭乗口までお越しください。",
        "各位旅客{greet}，乘搭 HK Express UO{flight}前往{dest}的旅客請留意，如您使用網上辦理登機手續及並未前往登機櫃檯，請前往登機閘口{gate}與地勤人員聯絡，以便檢查旅遊證件。多謝。"),
    t("Check-in", "Page passenger by name",
        "{greet}. May I have your attention please. HK Express flight UO {flight} departing to {dest}. We would like to invite Guests {title} {name} to proceed to boarding gate {gate} for document verification. Thank you for your cooperation.",
        "皆様、{greet}。香港エクスプレスからご案内いたします。{dest}行き UO{flight}便にご搭乗予定の{name}様、ご搭乗前にパスポートなどの確認がございますので、搭乗口{gate}までお越しください。ご協力ありがとうございます。",
        "各位旅客{greet}，以下乘搭 HK Express UO{flight}前往{dest}的旅客：{name}{title}，請前往登機閘口{gate}與地勤人員聯絡，以方便檢查旅遊證件。多謝。"),

    t("Pre-boarding", "Electronic devices",
        "{greet} ladies and gentlemen, may I have your attention please. For all Guests boarding HK Express flights, please note that any electronic devices in your check-in baggage should be switched off and securely stored to prevent any damage. If you have not done so already, please proceed to your boarding gate to seek assistance. Thank you.",
        "皆様、{greet}。香港エクスプレスからご案内いたします。香港エクスプレスにご搭乗のすべてのお客様、お預けになられたお荷物の中の電子機器は適切に収納され、電源は完全にお切りになりましたでしょうか？万が一、該当の処置をせずに荷物を預入れたお心当たりのあるお客様は、搭乗口までお越しください。地上係員が、お手伝いさせていただきます。",
        "各位旅客{greet}，乘搭 HK Express 航班的旅客請留意，所有於託運行李內的電子裝置應已完全關閉並妥善放置，以防發生任何意外。如您的電子裝置並未符合上述要求，請馬上前往登機閘口與地勤人員聯絡。多謝。"),
    t("Pre-boarding", "Lithium power bank",
        "Dear Passengers, according to the latest requirements by the Hong Kong Civil Aviation Department, passengers are restricted from using lithium battery power banks to charge other portable electronic devices and/or recharge power banks during flight. In addition, power banks must not be stored in the overhead compartments. Thank you for your attention.",
        "香港エクスプレス並びに香港航空局より、ご案内申し上げます。飛行中、リチウムイオンのモバイルバッテリーから他の電子機器への充電およびリチウムイオンのモバイルバッテリー本体への充電は禁じられております。また、リチウムイオンのモバイルバッテリーは頭上の棚に収納いただかないようお願い申し上げます。安全な運航へのご理解とご協力をお願いいたします。",
        "大家好，根據香港民航處最新要求，為確保航空安全，乘客嚴禁在航班上使用鋰電池外置充電器為其他便攜式電子裝置充電或為外置充電器充電。同時，外置充電器亦不可以放置於座位上方的共用置物櫃。多謝各位的合作。"),
    t("Pre-boarding", "Boarding sequence",
        "{greet} ladies and gentlemen. May I have your attention please, welcome to HK Express flight UO {flight}, codeshare with Cathay Pacific flight CX {cx}, departing to {dest}. In a few moments, we will be inviting Guests travelling with infants, young children, Guests who need special assistance, and HK Express Priority Guests to prepare for boarding. Other Guests will be invited to board according to seat rows. Please present your boarding pass and travel document when boarding. For your comfort and safety, each guest is allowed to carry only one small personal item, which must fit under the seat in front. If your fare category permits, you may bring one piece of cabin baggage. Additional screening of carry-on baggage will be performed by ground staff at the boarding gate. Any baggage that exceeds the limits of your fare category in terms of number, size, or weight must be checked, and relevant fees will apply. We will have a boarding announcement soon, thank you for your patience.",
        "皆様、{greet}。香港エクスプレスからご案内いたします。本日は香港エクスプレス UO{flight}便、キャセイパシフィック航空とのコードシェア CX{cx}便、{dest}行きのご利用、誠にありがとうございます。これより当便にご搭乗いただく順番についてご案内いたします。まずはじめに赤ちゃんやお子様をお連れのお客様、特別なお手伝いが必要なお客様、香港エクスプレス プライオリティをご利用のお客様を先に機内にご案内いたします。その他のお客様はお座席の列の番号に従ってご案内いたします。ご搭乗の際には、搭乗券とパスポートのご提示をお願いいたします。お客様の安全と快適な旅行のために、機内にお持ち込みいただけるのは、前の座席の下に収まる身の回り品お一つのみとなります。ご利用の運賃タイプによっては、さらに機内手荷物をお一つお持ち込みいただけます。機内持ち込み手荷物は、搭乗口において地上職員が再度確認いたします。手荷物の個数、サイズ、重さのいずれかがご利用の運賃タイプの制限を超える場合は、お預けいただく必要があり、追加料金がかかります。それでは、ご搭乗のご案内まで、今しばらくお待ちください。本日は香港エクスプレスにご搭乗いただきまして、誠にありがとうございます。",
        "各位旅客{greet}！歡迎乘搭 HK Express UO{flight}，代號共享航班：國泰航空 CX{cx}前往{dest}。航班將於 10 分鐘後開始登機，我們將首先邀請帶有嬰幼兒隨行，需要特別協助的旅客，香港快運優先服務的旅客登機。其他旅客將根據座位行數登機。登機時請出示登機證及旅行證件。為確保旅途舒適與安全，每位旅客只可攜帶一件必需能夠放置於前方座位下的隨身物品。如閣下的票價類別許可，你亦可攜帶一件登機行李。登機口地勤人員將會再次檢查所有手提行李，超出票價類別限制，包括件數，尺寸及重量的行李，必須寄艙並會收取相應費用。現在請稍候片刻，我們將會再有登機廣播。"),

    t("Boarding", "Express boarding",
        "{greet} ladies and gentlemen. May I have your attention please, welcome to HK Express flight UO {flight}, codeshare with Cathay Pacific flight CX {cx}, departing to {dest}. We would like to invite Guests travelling with infants, young children, Guests who need special assistance, and HK Express Priority Guests to board the aircraft. Please prepare your boarding pass and travel document for boarding. All other Guests please remain seated until your row is called, thank you!",
        "皆様、{greet}。香港エクスプレスからご案内いたします。香港エクスプレス {dest}行き UO{flight}便、キャセイパシフィック航空とのコードシェア CX{cx}便のご利用、誠にありがとうございます。香港エクスプレスから優先搭乗のご案内をいたします。小さなお子様をお連れのお客様、特別なお手伝いが必要なお客様、香港エクスプレス プライオリティをご利用のお客様のご搭乗を開始いたします。搭乗口で搭乗券とパスポートを再度確認いたしますので、お手元にご用意の上、搭乗口までお進みください。その他のお客様は、次のご案内まで今しばらくお待ちください。",
        "各位旅客{greet}！歡迎乘搭 HK Express UO{flight}，代號共享航班：國泰航空 CX{cx}前往{dest}。現在我們將首先邀請帶有嬰幼兒隨行、需要特別協助的旅客、香港快運優先服務的旅客登機。登機時請出示登機證及旅行證件以便地勤人員再次檢查。其他旅客請稍候片刻。多謝！")
) +
    rowBoarding("A321 NEO – 236Y row boarding", 26, 40, 11 to 25) +
    rowBoarding("A321 – 230Y row boarding", 26, 39, 11 to 25) +
    rowBoarding("A320 NEO – 188Y row boarding", 16, 32, null) +
    rowBoarding("A320 – 180Y row boarding", 16, 30, null) +
    listOf(
    t("Flight irregularities", "Delay – new time",
        "May I have your attention please, this is an announcement for HK Express flight UO {flight}, codeshare with Cathay Pacific flight CX {cx}, departing to {dest}. We regret to inform you that your flight has been delayed due to {reason}. Your new estimated {kind} time is {etd}. Further announcements will be made when the flight is ready for boarding. If you require further assistance, please contact our ground staff at the service desk. We apologize for the inconvenience caused. Thank you.",
        "香港エクスプレス {dest}行き UO{flight}便、キャセイパシフィック航空とのコードシェア CX{cx}便をご利用のお客様へお知らせいたします。当便は、{reason}により出発が遅れております。新しい{kind}時刻は、{etd}を予定しております。出発の準備が整い次第、改めてご案内させていただきます。なお、当遅延に関するお問い合わせがございましたら、サービスデスクへお越しください。この度は、遅延が生じましたことを深くお詫び申し上げます。",
        "各位旅客請注意，乘搭 HK Express UO{flight}，代號共享航班：國泰航空 CX{cx}前往{dest}的航班，由於{reason}的關係，引致航班延誤，新的{kind}時間將改為{etd}。當航班準備好登機時，我們將會再作宣佈。如您需要任何協助，請與我們的地勤人員聯絡。我們對於是次延誤所造成的不便深感歉意，多謝您的體諒。"),
    t("Flight irregularities", "Delay – until further notice",
        "May I have your attention please, this is an announcement for HK Express flight UO {flight}, codeshare with Cathay Pacific flight CX {cx}, departing to {dest}. We regret to inform you that your flight has been delayed due to {reason}. Further announcements will be made when the flight is ready for boarding. If you require further assistance, please contact our ground staff at the service desk. We apologize for the inconvenience caused. Thank you.",
        "香港エクスプレス UO{flight}便、キャセイパシフィック航空とのコードシェア CX{cx}便をご利用のお客様へお知らせいたします。当便は、{reason}により出発が遅れております。出発の準備が整い次第、改めてご案内させていただきますので、今しばらくお待ちください。なお、当遅延に関するお問い合わせがございましたら、サービスデスクへお越しください。この度は、遅延が生じましたことを深くお詫び申し上げます。",
        "各位旅客請注意，乘搭 HK Express UO{flight}，代號共享航班：國泰航空 CX{cx}前往{dest}的航班，由於{reason}的關係，引致航班延誤，當航班準備好登機時，我們將會再次宣佈新的起飛或登機時間。如您需要任何協助，請與我們的地勤人員聯絡。我們對於是次延誤所引致的不便深感歉意，多謝您的體諒。"),
    t("Flight irregularities", "Cancellation",
        "May I have your attention please. HK Express flight UO {flight}, codeshare with Cathay Pacific flight CX {cx}, departing to {dest}, has been cancelled due to {reason}. Further arrangements will be made. For more information or further assistance, please contact our ground staff at the service desk. We apologize for the inconvenience caused. Thank you.",
        "香港エクスプレス {dest}行き UO{flight}便、キャセイパシフィック航空とのコードシェア CX{cx}便をご利用のお客様へご案内いたします。当便は、{reason}により欠航させていただくこととなりました。今後の対応については、追ってご案内いたします。お問い合わせがあるお客様、お手伝いが必要なお客様は、サービスデスクへお越しください。この度は、ご迷惑をおかけいたしますことを深くお詫び申し上げます。",
        "各位旅客請注意，乘搭 HK Express UO{flight}，代號共享航班：國泰航空 CX{cx}前往{dest}的航班，由於{reason}的關係，引致航班取消，我們將作進一步安排。如您想了解更多或需要任何協助，請與我們的地勤人員聯絡。我們對於是次航班取消所引致的不便深感歉意，多謝您的體諒。")
)
