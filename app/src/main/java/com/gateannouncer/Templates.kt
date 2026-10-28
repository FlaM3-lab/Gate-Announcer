package com.gateannouncer

/** To add a language: add a Lang here, then add its text to each template below (or let custom templates fall back to English). */
data class Lang(val code: String, val name: String, val tag: String, val defaultVoice: String)

val LANGS = listOf(
    Lang("en", "English", "en-US", "en-US-Neural2-F"),
    Lang("ja", "Japanese 日本語", "ja-JP", "ja-JP-Neural2-B"),
    Lang("yue", "Cantonese 廣東話", "zh-HK", "yue-HK-Standard-A")
)

fun langOf(code: String) = LANGS.firstOrNull { it.code == code } ?: LANGS[0]

/** One spoken part of an announcement: [text] is shown, [speech] is what is read aloud. */
data class Item(val lang: String, val text: String, val speech: String = text)

private val KEY = Regex("\\{(\\w+)\\}")

data class Template(val title: String, val texts: Map<String, String>, val custom: Boolean = false) {
    val keys: List<String>
        get() = texts.values.flatMap { v -> KEY.findAll(v).map { it.groupValues[1] }.toList() }.distinct()
    fun hasLang(code: String) = !texts[code].isNullOrBlank()
    fun textFor(code: String) = if (hasLang(code)) texts[code]!! else texts["en"].orEmpty()
}

val SAVED_KEYS = setOf("airline", "flight", "dest", "gate")

private val LABELS = mapOf(
    "airline" to "Airline", "flight" to "Flight number", "dest" to "Destination",
    "gate" to "Gate", "newgate" to "New gate", "time" to "Departure time",
    "newtime" to "New departure time", "delay" to "Delay (minutes)",
    "group" to "Boarding group", "name" to "Passenger name"
)

fun label(k: String) = LABELS[k] ?: k.replace('_', ' ')

private fun spaceDigits(v: String) =
    if (Regex("^[A-Za-z]{0,3}\\s*\\d+$").matches(v)) v.replace(Regex("(\\d)"), "\$1 ").trim() else v

fun fill(t: Template, vals: Map<String, String>, lang: String, forSpeech: Boolean): String =
    KEY.replace(t.textFor(lang)) { m ->
        val k = m.groupValues[1]
        val v = vals[k]?.trim().orEmpty()
        when {
            v.isEmpty() -> "[${label(k).lowercase()}]"
            forSpeech && k == "flight" -> spaceDigits(v)
            else -> v
        }
    }

fun build(t: Template, vals: Map<String, String>, langs: List<String>): List<Item> =
    langs.map { Item(it, fill(t, vals, it, false), fill(t, vals, it, true)) }

private fun t(title: String, en: String, ja: String, yue: String) =
    Template(title, mapOf("en" to en, "ja" to ja, "yue" to yue))

val baseTemplates = listOf(
    t("Pre-boarding",
        "Ladies and gentlemen, {airline} flight {flight} to {dest} is now pre-boarding at gate {gate}. We invite passengers needing extra assistance and families with small children to board at this time.",
        "ご搭乗のお客様にご案内いたします。{airline}{flight}便、{dest}行きは、{gate}ゲートにて事前搭乗を開始いたします。お手伝いが必要なお客様、小さなお子様をお連れのお客様は、お先にご搭乗ください。",
        "各位旅客請注意，前往{dest}的{airline}{flight}航班，現開始優先登機，登機閘口為{gate}。需要特別協助的旅客及攜帶幼童的家庭，請先行登機。"),
    t("Boarding",
        "Ladies and gentlemen, {airline} flight {flight} to {dest} is now boarding at gate {gate}. Please have your boarding pass and photo ID ready.",
        "{airline}{flight}便、{dest}行きは、ただいまより{gate}ゲートにてご搭乗を開始いたします。搭乗券と写真付き身分証明書をご用意ください。",
        "各位旅客請注意，前往{dest}的{airline}{flight}航班，現開始登機，登機閘口為{gate}。請準備好登機證及附有相片的身份證明文件。"),
    t("Boarding by group",
        "{airline} flight {flight} to {dest} is now boarding group {group} at gate {gate}. Please have your boarding pass ready.",
        "{airline}{flight}便、{dest}行きは、ただいま{group}グループのお客様のご搭乗を、{gate}ゲートにて開始いたします。搭乗券をご用意ください。",
        "前往{dest}的{airline}{flight}航班，現開始讓第{group}組旅客登機，登機閘口為{gate}。請準備好登機證。"),
    t("Final call",
        "This is the final boarding call for {airline} flight {flight} to {dest} at gate {gate}. The aircraft door will close shortly.",
        "{airline}{flight}便、{dest}行きの最終搭乗案内です。{gate}ゲートよりご搭乗ください。まもなく搭乗口を閉め切らせていただきます。",
        "前往{dest}的{airline}{flight}航班，現進行最後登機廣播，登機閘口為{gate}。機艙門即將關閉，請尚未登機的旅客立即前往登機。"),
    t("Door closing",
        "Attention please. Boarding for {airline} flight {flight} to {dest} is now closed. Thank you for your cooperation.",
        "お知らせいたします。{airline}{flight}便、{dest}行きの搭乗受付は終了いたしました。ご協力ありがとうございました。",
        "各位旅客請注意，前往{dest}的{airline}{flight}航班，已經停止登機。多謝各位合作。"),
    t("Delay",
        "Ladies and gentlemen, {airline} flight {flight} to {dest} is delayed by approximately {delay} minutes. We apologize for the inconvenience and will update you as soon as we have more information.",
        "ご案内いたします。{airline}{flight}便、{dest}行きは、約{delay}分遅れて出発する見込みです。ご迷惑をおかけし、誠に申し訳ございません。新しい情報が入り次第、お知らせいたします。",
        "各位旅客請注意，前往{dest}的{airline}{flight}航班，因故延誤，預計延遲約{delay}分鐘起飛。對於造成不便，我們深表歉意。如有最新消息，我們會再作通知。"),
    t("New departure time",
        "Attention passengers, {airline} flight {flight} to {dest} now has a new departure time of {newtime}. Please remain near gate {gate}. We apologize for the delay.",
        "ご案内いたします。{airline}{flight}便、{dest}行きの出発時刻は、{newtime}に変更となりました。{gate}ゲート付近でお待ちください。遅れて申し訳ございません。",
        "各位旅客請注意，前往{dest}的{airline}{flight}航班，起飛時間更改為{newtime}。請各位旅客留在{gate}閘口附近等候。對於延誤，我們深表歉意。"),
    t("Gate change",
        "Attention passengers, {airline} flight {flight} to {dest} has changed gates. The flight will now depart from gate {newgate} instead of gate {gate}.",
        "ご案内いたします。{airline}{flight}便、{dest}行きは、搭乗口が変更となりました。{gate}ゲートから{newgate}ゲートに変更となります。お間違えのないようご注意ください。",
        "各位旅客請注意，前往{dest}的{airline}{flight}航班，登機閘口已經更改。該航班現改為由{newgate}閘口登機，不再使用{gate}閘口。"),
    t("Page passenger",
        "Passenger {name}, traveling to {dest} on {airline} flight {flight}, please come to gate {gate} immediately. Your flight is boarding.",
        "{dest}行き、{airline}{flight}便をご利用の{name}様、至急{gate}ゲートまでお越しください。ただいま搭乗中です。",
        "請乘搭{airline}{flight}航班前往{dest}的{name}乘客，請立即前往{gate}閘口。你的航班現正登機。"),
    t("Cancellation",
        "Ladies and gentlemen, {airline} flight {flight} to {dest} has been cancelled. Please see the {airline} service desk for rebooking. We sincerely apologize for the inconvenience.",
        "ご案内いたします。{airline}{flight}便、{dest}行きは、欠航となりました。振替のお手続きについては、{airline}のサービスカウンターへお越しください。ご迷惑をおかけし、誠に申し訳ございません。",
        "各位旅客請注意，前往{dest}的{airline}{flight}航班已經取消。請前往{airline}服務櫃檯辦理改乘安排。對於造成不便，我們深表歉意。"),
    t("Volunteers needed",
        "Attention passengers on {airline} flight {flight} to {dest}. This flight is currently oversold. We are looking for volunteers to take a later flight in exchange for compensation. Please see the agent at gate {gate}.",
        "{dest}行き、{airline}{flight}便をご利用のお客様にお知らせいたします。本便は満席のため、後の便への振替にご協力いただけるお客様を募集しております。所定の補償をご用意しております。ご協力いただける方は、{gate}ゲートの係員までお申し出ください。",
        "乘搭{airline}{flight}航班前往{dest}的旅客請注意。本航班座位已經訂滿，我們現徵求自願旅客改乘較後的航班，並會提供補償。有意者請向{gate}閘口的職員查詢。"),
    t("Gate-check bags",
        "Attention passengers boarding {airline} flight {flight} to {dest}. This flight is very full. Carry-on bags that do not fit may be checked at the aircraft door at no charge. Please see the agent at gate {gate}.",
        "{dest}行き、{airline}{flight}便にご搭乗のお客様にお知らせいたします。本便は大変混雑しております。機内に持ち込めない手荷物は、搭乗口で無料でお預かりいたします。{gate}ゲートの係員までお申し出ください。",
        "乘搭{airline}{flight}航班前往{dest}的旅客請注意。本航班乘客眾多，未能放入機艙的手提行李，可在登機口免費托運。詳情請向{gate}閘口的職員查詢。"),
    t("Unattended bags",
        "Attention passengers at gate {gate}. For your safety, please keep your baggage with you at all times. Unattended items may be removed by security.",
        "{gate}ゲート付近のお客様にお知らせいたします。安全のため、お荷物から目を離さないようお願いいたします。放置された荷物は、保安上の理由により撤去される場合がございます。",
        "{gate}閘口附近的旅客請注意。為了安全起見，請隨身攜帶行李，切勿離開。無人看管的物品，可能會被保安人員移走。"),
    t("Standby list",
        "Attention passengers on the standby list for {airline} flight {flight} to {dest}. Please check in with the agent at gate {gate}.",
        "{dest}行き、{airline}{flight}便のキャンセル待ちのお客様にお知らせいたします。{gate}ゲートの係員までお越しください。",
        "輪候補位乘搭{airline}{flight}航班前往{dest}的旅客請注意，請前往{gate}閘口向職員報到。")
)
