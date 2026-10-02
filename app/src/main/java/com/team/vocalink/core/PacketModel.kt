package com.team.vocalink.core

import android.os.ParcelUuid
import java.util.UUID

object ProtocolConstants {
    const val AIRHOP_PREAMBLE: Byte = 0x7E
    const val DEFAULT_TTL: Byte = 10
    const val PACKET_SIZE: Int = 40\n    const val NODE_ID_SIZE: Int = 4\n    const val MESSAGE_ID_SIZE: Int = 4\n    const val MAX_REASSEMBLY_CHUNKS: Int = 64\n    const val REPLAY_WINDOW_MS: Long = 120_000L
    const val DATA_SIZE: Int = 32
    const val PARITY_SIZE: Int = 8
    const val TOKEN_COUNT: Int = 13

    // Flags
    const val FLAG_EMERGENCY_SOS: Byte = 0x80.toByte() // Bit 7
    const val FLAG_PRIORITY_HIGH: Byte = 0x40.toByte() // Bit 6
    const val FLAG_PRIORITY_URGENT: Byte = 0x20.toByte() // Bit 5
    const val FLAG_ACK: Byte = 0x10.toByte() // Bit 4: Delivery Acknowledgment (Blue Tick!)
    const val FLAG_LANG_MASK: Byte = 0x0F.toByte()

    // 10 Indian Languages (As featured in SIH Video Scene 7)
    const val LANG_KANNADA: Byte = 0x01   // ಕನ್ನಡ
    const val LANG_HINDI: Byte = 0x02     // हिंदी
    const val LANG_TAMIL: Byte = 0x03     // தமிழ்
    const val LANG_TELUGU: Byte = 0x04    // తెలుగు
    const val LANG_MALAYALAM: Byte = 0x05 // മലയാളം
    const val LANG_BENGALI: Byte = 0x06   // বাংলা
    const val LANG_MARATHI: Byte = 0x07   // मराठी
    const val LANG_GUJARATI: Byte = 0x08  // ગુજરાતી
    const val LANG_PUNJABI: Byte = 0x09   // ਪੰਜਾਬੀ
    const val LANG_ODIA: Byte = 0x0A      // ଓଡ଼ିଆ
    const val LANG_ENGLISH: Byte = 0x00   // English

    // BLE Service Data UUID (16-bit 0xFD6F)
    val SERVICE_DATA_UUID: UUID = UUID.fromString("0000FD6F-0000-1000-8000-00805F9B34FB")
    val PARCEL_SERVICE_UUID: ParcelUuid = ParcelUuid(SERVICE_DATA_UUID)

    // Benchmark Mysuru Flood Geofence Coordinates
    const val BENCHMARK_MYSURU_LAT = 12.2958
    const val BENCHMARK_MYSURU_LON = 76.6393
    const val BENCHMARK_DEFAULT_RADIUS_METERS = 5000.0
}

data class AirHopPacket(
    val preamble: Byte = ProtocolConstants.AIRHOP_PREAMBLE,
    val flags: Byte,
    val ttl: Byte = ProtocolConstants.DEFAULT_TTL,
    val msgId: Int,
    val targetZone: Int,\n    val destinationId: Int = targetZone,
    val latE7: Int,
    val lonE7: Int,
    val tokens: ByteArray,
    val fecParity: ByteArray
) {
    val isEmergencySos: Boolean get() = (flags.toInt() and ProtocolConstants.FLAG_EMERGENCY_SOS.toInt()) != 0
    val isHighPriority: Boolean get() = (flags.toInt() and ProtocolConstants.FLAG_PRIORITY_HIGH.toInt()) != 0
    val isAck: Boolean get() = (flags.toInt() and ProtocolConstants.FLAG_ACK.toInt()) != 0
    val languageId: Byte get() = (flags.toInt() and ProtocolConstants.FLAG_LANG_MASK.toInt()).toByte()

    val latitude: Double get() = latE7 / 1e7
    val longitude: Double get() = lonE7 / 1e7

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AirHopPacket
        return msgId == other.msgId && flags == other.flags && ttl == other.ttl
    }

    override fun hashCode(): Int = msgId
}

data class PacketRepairResult(
    val success: Boolean,
    val correctedBytes: Int,
    val hadErrors: Boolean,
    val repairedPacket: ByteArray?,
    val msgId: Int,
    val flags: Int,
    val ttl: Int,
    val targetZone: Int,
    val latE7: Int,
    val lonE7: Int,
    val tokens: ByteArray?
) {
    val isEmergencySos: Boolean get() = (flags and ProtocolConstants.FLAG_EMERGENCY_SOS.toInt()) != 0
    val latitude: Double get() = latE7 / 1e7
    val longitude: Double get() = lonE7 / 1e7
}

enum class VadState(val code: Int) {
    INACTIVE(0),
    STARTING(1),
    ACTIVE(2),
    HANGOVER(3);

    companion object {
        fun fromCode(code: Int): VadState = values().firstOrNull { it.code == code } ?: INACTIVE
    }
}

enum class VadEvent(val code: Int) {
    NONE(0),
    SPEECH_STARTED(1),
    SPEECH_ONGOING(2),
    SPEECH_FINISHED(3);

    companion object {
        fun fromCode(code: Int): VadEvent = values().firstOrNull { it.code == code } ?: NONE
    }
}

enum class PacketAction {
    INGESTED,
    RELAYED,
    DROPPED_DUPLICATE,
    DROPPED_TTL,
    REPAIRED,
    ALERT_TRIGGERED
}

data class WaterfallLogItem(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val msgId: Int,
    val ttl: Int,
    val isSos: Boolean,
    val action: PacketAction,
    val correctedBytes: Int,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double?,
    val inGeofence: Boolean,
    val tokensSummary: String
)

enum class MessageStatus {
    SENDING,
    SENT,        // Single Grey Tick: ✓
    DELIVERED    // Double Blue Tick: ✓✓
}

data class ChatMessage(
    val id: Int,
    val text: String,
    val senderName: String,
    val isFromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val hopCount: Int = 1,
    var status: MessageStatus = MessageStatus.SENT,
    val latencyMs: Long? = null
)

object DisasterPhraseCodebook {
    val PHRASES_EN = mapOf(
        1 to "Flood Emergency: Water level rising fast, 3 people trapped!",
        2 to "Urgent Evacuation: Move to higher ground immediately!",
        3 to "Medical Emergency: Critical patient needs first aid kit!",
        4 to "Structural Collapse: People trapped under rubble, send help!",
        5 to "Relief Center: Drinking water & dry rations available here.",
        6 to "Search and Rescue team is on the way, stay in position.",
        7 to "Disaster radio mesh active, link verified across phones.",
        8 to "Safe evacuation route confirmed on northern high ground."
    )

    val PHRASES_KN = mapOf(
        1 to "ತುರ್ತು ಎಚ್ಚರಿಕೆ! ಪ್ರವಾಹ ಮಟ್ಟ ಏರುತ್ತಿದೆ, 3 ಜನರು ಸಿಲುಕಿದ್ದಾರೆ!",
        2 to "ಕೂಡಲೇ ಸುರಕ್ಷಿತ ಎತ್ತರದ ಪ್ರದೇಶಕ್ಕೆ ತೆರಳಿ!",
        3 to "ವೈದ್ಯಕೀಯ ತುರ್ತು ನೆರವು ಅಗತ್ಯವಿದೆ!",
        4 to "ಕಟ್ಟಡದ ಅವಶೇಷಗಳಲ್ಲಿ ಜನರು ಸಿಲುಕಿದ್ದಾರೆ!",
        5 to "ಕುಡಿಯುವ ನೀರು ಮತ್ತು ಆಹಾರ ವಿತರಣಾ ಕೇಂದ್ರ ಇಲ್ಲಿದೆ.",
        6 to "ರಕ್ಷಣಾ ಪಡೆಗಳು ಮಾರ್ಗದಲ್ಲಿವೆ, ಧೈರ್ಯವಾಗಿರಿ.",
        7 to "ಜಾಲಬಂಧ ಪ್ರಸಾರ ಸಕ್ರಿಯವಾಗಿದೆ.",
        8 to "ಸುರಕ್ಷಿತ ಸ್ಥಳಕ್ಕೆ ದಾರಿ ತೆರೆದಿದೆ."
    )

    val PHRASES_HI = mapOf(
        1 to "आपातकालीन चेतावनी! बाढ़ का स्तर तेजी से बढ़ रहा है, लोग फंसे हैं!",
        2 to "तुरंत सुरक्षित ऊंचे स्थान पर जाएं! बाढ़ की चेतावनी।",
        3 to "चिकित्सा सहायता की तत्काल आवश्यकता है!",
        4 to "मलबे में लोग फंसे हुए हैं, बचाव दल भेजें!",
        5 to "राहत सामग्री और पेयजल वितरण केंद्र यहां उपलब्ध है।",
        6 to "बचाव दल रास्ते में है, सुरक्षित स्थान पर रहें।",
        7 to "आपदा रेडियो नेटवर्क सक्रिय है।",
        8 to "सुरक्षित निकासी मार्ग उपलब्ध है।"
    )

    val PHRASES_TA = mapOf(
        1 to "வெள்ள எச்சரிக்கை! நீர் மட்டம் வேகமாக உயர்கிறது, மக்கள் சிக்கியுள்ளனர்!",
        2 to "உடனடியாக பாதுகாப்பான உயரமான பகுதிக்கு செல்லுங்கள்!",
        3 to "மருத்துவ உதவி உடனடியாக தேவைப்படுகிறது!",
        4 to "இடிபாடுகளில் மக்கள் சிக்கியுள்ளனர், உதவி அனுப்பவும்!",
        5 to "குடிநீர் மற்றும் உணவு நிவாரண மையம் இங்கே உள்ளது.",
        6 to "மீட்புக் குழுக்கள் விரைந்து வருகின்றன, பாதுகாப்பாக இருங்கள்.",
        7 to "பேரிடர் ரேடியோ மெஷ் நெட்வொர்க் செயலில் உள்ளது.",
        8 to "பாதுகாப்பான வெளியேற்றப் பாதை உறுதி செய்யப்பட்டது."
    )

    val PHRASES_TE = mapOf(
        1 to "వరద హెచ్చరిక! నీటి మట్టం వేగంగా పెరుగుతోంది, ప్రజలు చిక్కుకున్నారు!",
        2 to "వెంటనే సురక్షితమైన ఎత్తైన ప్రాంతానికి వెళ్ళండి!",
        3 to "వైద్య అత్యవసర సహాయం తక్షణమే అవసరం!",
        4 to "శిథిలాల క్రింద ప్రజలు చిక్కుకున్నారు, సహాయం పంపండి!",
        5 to "తాగునీరు మరియు ఆహార పంపిణీ కేంద్రం ఇక్కడ ఉంది.",
        6 to "రక్షణ బృందాలు వస్తున్నాయి, సురక్షితంగా ఉండండి.",
        7 to "విపత్తు రేడియో మెష్ నెట్‌వర్క్ క్రియాశీలంగా ఉంది.",
        8 to "సురక్షిత తరలింపు మార్గం నిర్ధారించబడింది."
    )

    val PHRASES_ML = mapOf(
        1 to "വെള്ളപ്പൊക്ക മുന്നറിയിപ്പ്! ജലനിരപ്പ് അതിവേഗം ഉയരുന്നു!",
        2 to "ഉടൻ സുരക്ഷിതമായ ഉയർന്ന സ്ഥലത്തേക്ക് മാറുക!",
        3 to "വൈദ്യസഹായം അടിയന്തിരമായി ആവശ്യമുണ്ട്!",
        4 to "അവശിഷ്ടങ്ങൾക്കടിയിൽ ആളുകൾ കുടുങ്ങിക്കിടക്കുന്നു!",
        5 to "കുടിവെള്ളവും ഭക്ഷണ വിതരണ കേന്ദ്രവും ഇവിടെയുണ്ട്.",
        6 to "രക്ഷാപ്രവർത്തകർ എത്തിക്കൊണ്ടിരിക്കുന്നു.",
        7 to "ദുരന്ത റേഡിയോ മെഷ് നെറ്റ്‌വർക്ക് സജീവമാണ്.",
        8 to "സുരക്ഷിത ഒഴിപ്പിക്കൽ പാത തയ്യാറാണ്."
    )

    val PHRASES_MR = mapOf(
        1 to "पुराचा इशारा! पाण्याची पातळी झपाट्याने वाढत आहे, लोक अडकले आहेत!",
        2 to "त्वरित सुरक्षित उंच ठिकाणी जा! पुराचा धोका.",
        3 to "वैद्यकीय मदतीची तातडीने गरज आहे!",
        4 to "इमारतीच्या ढिगाऱ्याखाली लोक अडकले आहेत!",
        5 to "पिण्याचे पाणी व अन्न वितरण केंद्र येथे उपलब्ध आहे.",
        6 to "बचाव पथक मार्गावर आहे, सुरक्षित राहा.",
        7 to "आपत्ती रेडिओ मेश सक्रिय आहे.",
        8 to "सुरक्षित बाहेर पडण्याचा मार्ग उपलब्ध आहे."
    )

    val PHRASES_BN = mapOf(
        1 to "বন্যা সতর্কতা! পানির স্তর দ্রুত বাড়ছে, মানুষ আটকা পড়েছে!",
        2 to "অবিলম্বে নিরাপদ উঁচু স্থানে চলে যান!",
        3 to "জরুরী চিকিৎসা সহায়তা প্রয়োজন!",
        4 to "ধ্বংসস্তূপের নিচে মানুষ আটকা পড়েছে!",
        5 to "পানীয় জল ও খাদ্য বিতরণ কেন্দ্র এখানে উপলব্ধ।",
        6 to "উদ্ধারকারী দল আসছে, নিরাপদ স্থানে থাকুন।",
        7 to "দুর্যোগ রেডিও মেশ সক্রিয় আছে।",
        8 to "নিরাপদ উদ্ধার পথ নিশ্চিত করা হয়েছে।"
    )

    val PHRASES_GU = mapOf(
        1 to "પૂરની ચેતવણી! પાણીની સપાટી ઝડપથી વધી રહી છે!",
        2 to "તરત જ સુરક્ષિત ઊંચા સ્થળે ખસી જાઓ!",
        3 to "તબીબી સહાયની તાત્કાલિક જરૂર છે!",
        4 to "કાટમાળ નીચે લોકો ફસાયા છે, મદદ મોકલો!",
        5 to "પીવાનું પાણી અને રાશન કેન્દ્ર અહીં ઉપલબ્ધ છે.",
        6 to "બચાવ ટીમ આવી રહી છે, સુરક્ષિત રહો.",
        7 to "આપત્તિ રેડિયો મેશ સક્રિય છે.",
        8 to "સુરક્ષિત બહાર નીકળવાનો માર્ગ ખુલ્લો છે."
    )

    val PHRASES_PA = mapOf(
        1 to "ਹੜ੍ਹ ਦੀ ਚੇਤਾਵਨੀ! ਪਾਣੀ ਦਾ ਪੱਧਰ ਤੇਜ਼ੀ ਨਾਲ ਵੱਧ ਰਿਹਾ ਹੈ!",
        2 to "ਤੁਰੰਤ ਸੁਰੱਖਿਅਤ ਉੱਚੀ ਥਾਂ ਤੇ ਜਾਓ!",
        3 to "ਡਾਕਟਰੀ ਸਹਾਇਤਾ ਦੀ ਤੁਰੰਤ ਲੋੜ ਹੈ!",
        4 to "ਮਲਬੇ ਹੇਠ ਲੋਕ ਫਸੇ ਹੋਏ ਹਨ, ਮਦਦ ਭੇਜੋ!",
        5 to "ਪੀਣ ਵਾਲਾ ਪਾਣੀ ਅਤੇ ਰਾਸ਼ਨ ਇੱਥੇ ਉਪਲਬਧ ਹੈ।",
        6 to "ਬਚਾਅ ਟੀਮ ਰਸਤੇ ਵਿੱਚ ਹੈ, ਸੁਰੱਖਿਅਤ ਰਹੋ।",
        7 to "ਆਫ਼ਤ ਰੇਡੀਓ ਮੈਸ਼ ਨੈੱਟਵਰਕ ਸਰਗਰਮ ਹੈ।",
        8 to "ਸੁਰੱਖਿਅਤ ਨਿਕਾਸੀ ਰਸਤਾ ਤਸਦੀਕ ਕੀਤਾ ਗਿਆ ਹੈ।"
    )

    val PHRASES_OR = mapOf(
        1 to "ବନ୍ୟା ସତର୍କତା! ଜଳସ୍ତର ଦ୍ରୁତ ଗତିରେ ବୃଦ୍ଧି ପାଉଛି!",
        2 to "ତୁରନ୍ତ ନିରାପଦ ଉଚ୍ଚ ସ୍ଥାନକୁ ଚାଲିଯାଆନ୍ତୁ!",
        3 to "ଡାକ୍ତରୀ ସହାୟତା ଜରୁରୀ ଆବଶ୍ୟକ!",
        4 to "ଭଗ୍ନାବଶେଷ ତଳେ ଲୋକମାନେ ଫସି ରହିଛନ୍ତି!",
        5 to "ପାନୀୟ ଜଳ ଏବଂ ଖାଦ୍ୟ ବିତରଣ କେନ୍ଦ୍ର ଏଠାରେ ଉପଲବ୍ଧ।",
        6 to "ଉଦ୍ଧାରକାରୀ ଦଳ ଆସୁଛନ୍ତି, ନିରାପଦରେ ରୁହନ୍ତୁ।",
        7 to "ବିପର୍ଯ୍ୟୟ ରେଡିଓ ମେସ୍ ସକ୍ରିୟ ଅଛି।",
        8 to "ନିରାପଦ ସ୍ଥାନାନ୍ତରଣ ମାର୍ଗ ଉପଲବ୍ଧ ଅଛି।"
    )

    fun decodeText(tokens: ByteArray, lang: Byte): String {
        if (tokens.isEmpty()) return PHRASES_EN[1]!!
        val code = tokens[0].toInt() and 0xFF
        if (code == 0xFE && tokens.size > 1) {
            val end = tokens.indexOfFirst { it == 0.toByte() }.let { if (it <= 1) tokens.size else it }
            return try {
                val str = String(tokens, 1, end - 1, Charsets.UTF_8).trim()
                if (str.isNotBlank()) str else PHRASES_EN[1]!!
            } catch (e: Exception) {
                PHRASES_EN[1]!!
            }
        }
        val phraseCode = if (code in 1..8) code else 1
        return when (lang) {
            ProtocolConstants.LANG_KANNADA -> PHRASES_KN[phraseCode] ?: PHRASES_KN[1]!!
            ProtocolConstants.LANG_HINDI -> PHRASES_HI[phraseCode] ?: PHRASES_HI[1]!!
            ProtocolConstants.LANG_TAMIL -> PHRASES_TA[phraseCode] ?: PHRASES_TA[1]!!
            ProtocolConstants.LANG_TELUGU -> PHRASES_TE[phraseCode] ?: PHRASES_TE[1]!!
            ProtocolConstants.LANG_MALAYALAM -> PHRASES_ML[phraseCode] ?: PHRASES_ML[1]!!
            ProtocolConstants.LANG_MARATHI -> PHRASES_MR[phraseCode] ?: PHRASES_MR[1]!!
            ProtocolConstants.LANG_BENGALI -> PHRASES_BN[phraseCode] ?: PHRASES_BN[1]!!
            ProtocolConstants.LANG_GUJARATI -> PHRASES_GU[phraseCode] ?: PHRASES_GU[1]!!
            ProtocolConstants.LANG_PUNJABI -> PHRASES_PA[phraseCode] ?: PHRASES_PA[1]!!
            ProtocolConstants.LANG_ODIA -> PHRASES_OR[phraseCode] ?: PHRASES_OR[1]!!
            else -> PHRASES_EN[phraseCode] ?: PHRASES_EN[1]!!
        }
    }

    fun getPhrase(phraseId: Int, lang: Byte): String {
        val code = if (phraseId in 1..8) phraseId else 1
        return when (lang) {
            ProtocolConstants.LANG_KANNADA -> PHRASES_KN[code] ?: PHRASES_KN[1]!!
            ProtocolConstants.LANG_HINDI -> PHRASES_HI[code] ?: PHRASES_HI[1]!!
            ProtocolConstants.LANG_TAMIL -> PHRASES_TA[code] ?: PHRASES_TA[1]!!
            ProtocolConstants.LANG_TELUGU -> PHRASES_TE[code] ?: PHRASES_TE[1]!!
            ProtocolConstants.LANG_MALAYALAM -> PHRASES_ML[code] ?: PHRASES_ML[1]!!
            ProtocolConstants.LANG_MARATHI -> PHRASES_MR[code] ?: PHRASES_MR[1]!!
            ProtocolConstants.LANG_BENGALI -> PHRASES_BN[code] ?: PHRASES_BN[1]!!
            ProtocolConstants.LANG_GUJARATI -> PHRASES_GU[code] ?: PHRASES_GU[1]!!
            ProtocolConstants.LANG_PUNJABI -> PHRASES_PA[code] ?: PHRASES_PA[1]!!
            ProtocolConstants.LANG_ODIA -> PHRASES_OR[code] ?: PHRASES_OR[1]!!
            else -> PHRASES_EN[code] ?: PHRASES_EN[1]!!
        }
    }

    fun getPhraseIdForText(text: String): Int {
        val clean = text.trim()
        for (i in 1..8) {
            if (PHRASES_EN[i] == clean || PHRASES_KN[i] == clean || PHRASES_HI[i] == clean ||
                PHRASES_TA[i] == clean || PHRASES_TE[i] == clean || PHRASES_ML[i] == clean ||
                PHRASES_MR[i] == clean || PHRASES_BN[i] == clean || PHRASES_GU[i] == clean ||
                PHRASES_PA[i] == clean || PHRASES_OR[i] == clean) {
                return i
            }
        }
        return 0xFE
    }

    fun encodeTextToTokens(text: String, phraseId: Int = 1): ByteArray {
        val tokens = ByteArray(13)
        tokens[0] = phraseId.toByte()
        if (phraseId == 0xFE) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val len = minOf(12, bytes.size)
            System.arraycopy(bytes, 0, tokens, 1, len)
        }
        return tokens
    }
}
