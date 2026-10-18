package com.example.mangaromaji

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import java.text.Normalizer

/** Japanese (kanji + kana) -> romaji, modified Hepburn with macrons. */
class Romanizer {

    private val tk = Tokenizer.Builder().build()
    private val cache = HashMap<String, String>()

    fun convert(raw: String): String = cache.getOrPut(raw) {
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .split('\n').filter { it.isNotBlank() }
            .joinToString(" ") { line(it) }
    }

    private class It(var text: String, val kind: Int, var tok: Token?, val proper: Boolean = false, val first: Boolean = false)

    private fun line(line: String): String {
        val items = ArrayList<It>()
        var glueNext = false
        var prev: Token? = null

        for (t in tk.tokenize(line)) {
            val s = t.surface
            if (t.partOfSpeechLevel1 == "記号" || PUNCT_ONLY.matches(s)) {
                val p = PUNCT[s] ?: s
                if (p == " ") { prev = t; glueNext = false; continue }
                val kind = when {
                    CLOSE.contains(p) || p.all { CLOSE.contains(it.toString()) } -> 1
                    OPEN.contains(p) -> 2
                    else -> 0
                }
                items.add(It(p, kind, t))
                prev = t; glueNext = false
                continue
            }

            val rt = tokenRomaji(t)
            val d1 = t.partOfSpeechLevel2
            val d2 = t.partOfSpeechLevel3
            val last = items.lastOrNull()
            val canGlue = last != null && last.kind == 0 && last.first

            val copula = t.partOfSpeechLevel1 == "助動詞" &&
                s in setOf("だ", "です", "だっ", "でし", "だろ", "でしょ") &&
                prev?.partOfSpeechLevel1 == "名詞"
            val contraction = t.partOfSpeechLevel1 == "動詞" && d1 == "非自立" && (s.startsWith("て") || s.startsWith("で"))
            val suffix = contraction ||
                (!copula && t.partOfSpeechLevel1 == "助動詞") ||
                d1 == "接尾" ||
                (t.partOfSpeechLevel1 == "助詞" && d1 == "接続助詞" && (s == "て" || s == "で"))

            if (canGlue && (glueNext || suffix)) {
                val hyphen = d1 == "接尾" && d2 == "人名" && prev?.partOfSpeechLevel2 == "固有名詞"
                var x = rt
                if (last!!.text.endsWith('\u0001')) {
                    last.text = last.text.dropLast(1)
                    x = applyGem(x)
                }
                last.text += (if (hyphen) "-" else "") + x
                last.tok = t
            } else {
                if (last != null && last.text.endsWith('\u0001')) last.text = last.text.dropLast(1)
                items.add(It(rt, 0, t, proper = d1 == "固有名詞", first = true))
            }
            glueNext = t.partOfSpeechLevel1 == "接頭詞"
            prev = t
        }

        val out = StringBuilder()
        var pk = "start"
        for (it in items) {
            var txt = it.text.replace("\u0001", "")
            if (it.kind == 0 && it.first) {
                txt = mergeLong(txt, it.tok)
                if (it.proper && txt.isNotEmpty()) txt = txt.substring(0, 1).uppercase() + txt.substring(1)
            }
            when (it.kind) {
                1 -> { out.append(txt); pk = "close" }
                2 -> { if (out.isNotEmpty() && pk != "open") out.append(' '); out.append(txt); pk = "open" }
                else -> { if (out.isNotEmpty() && pk != "open") out.append(' '); out.append(txt); pk = "word" }
            }
        }
        return out.toString().replace(SENTENCE_START) { it.groupValues[1] + it.groupValues[2].uppercase() }
    }

    private fun mergeLong(w: String, last: Token?): String {
        fun m(x: String) = x.replace(Regex("ou|oo"), "ō").replace("uu", "ū").replace("aa", "ā").replace("ee", "ē")
        if (last != null && last.partOfSpeechLevel1 == "動詞" && last.conjugationForm == "基本形" &&
            Regex("(ou|uu)$").containsMatchIn(w)
        ) return m(w.dropLast(2)) + w.takeLast(2)
        return m(w)
    }

    private fun tokenRomaji(t: Token): String {
        val s = t.surface
        if (t.partOfSpeechLevel1 == "助詞") {
            when (s) { "は" -> return "wa"; "へ" -> return "e"; "を" -> return "o" }
        }
        if (s == "こんにちは") return "konnichiwa"
        if (s == "こんばんは") return "konbanwa"
        val r = t.reading
        if (r != null && r != "*") return toRoma(r)
        if (KANA.matches(s)) return toRoma(hira2kata(s))
        return s
    }

    private fun hira2kata(s: String) = buildString {
        for (c in s) append(if (c in '\u3041'..'\u3096' || c == '\u309d' || c == '\u309e') (c.code + 0x60).toChar() else c)
    }

    private fun applyGem(syl: String): String = when {
        syl.startsWith("ch") -> "t$syl"
        syl.isNotEmpty() && syl[0] in "bcdfghjklmnpqrstvwxyz" -> syl[0] + syl
        else -> syl
    }

    private fun combine(b: Char, s: Char): String? {
        val r = K[b] ?: return null
        SY[s]?.let { v ->
            if (r == "shi" || r == "chi" || r == "ji") return r.dropLast(1) + v
            if (r.length > 1 && r.endsWith("i")) return r.dropLast(1) + "y" + v
            if (b in "テデフヴ") return r.dropLast(1) + "y" + v
            return null
        }
        SV[s]?.let { v ->
            val c = when {
                r == "shi" -> "sh"
                r == "chi" -> "ch"
                r == "ji" -> "j"
                r == "tsu" -> "ts"
                b == 'ウ' -> "w"
                r.length > 1 -> r.dropLast(1)
                else -> return null
            }
            return c + v
        }
        return null
    }

    private fun toRoma(kata: String): String {
        val out = StringBuilder()
        var gem = false
        var i = 0
        while (i < kata.length) {
            val ch = kata[i]
            val nx = if (i + 1 < kata.length) kata[i + 1] else null
            if (ch == 'ッ') { gem = true; i++; continue }
            if (ch == 'ー') {
                if (out.isNotEmpty()) MAC[out.last()]?.let { out.setCharAt(out.length - 1, it) }
                i++; continue
            }
            var syl: String? = null
            var adv = 1
            if (K.containsKey(ch) && !SY.containsKey(ch) && !SV.containsKey(ch) &&
                nx != null && (SY.containsKey(nx) || SV.containsKey(nx))
            ) {
                combine(ch, nx)?.let { syl = it; adv = 2 }
            }
            val base = syl ?: when {
                ch == 'ン' -> if (nx != null && "アイウエオヤユヨ".indexOf(nx) >= 0) "n'" else "n"
                K.containsKey(ch) -> K[ch]!!
                else -> ch.toString()
            }
            out.append(if (gem) applyGem(base) else base)
            gem = false
            i += adv
        }
        if (gem) out.append('\u0001')
        return out.toString()
    }

    companion object {
        private val K: Map<Char, String> = (
            "ア a イ i ウ u エ e オ o カ ka キ ki ク ku ケ ke コ ko ガ ga ギ gi グ gu ゲ ge ゴ go " +
            "サ sa シ shi ス su セ se ソ so ザ za ジ ji ズ zu ゼ ze ゾ zo " +
            "タ ta チ chi ツ tsu テ te ト to ダ da ヂ ji ヅ zu デ de ド do " +
            "ナ na ニ ni ヌ nu ネ ne ノ no " +
            "ハ ha ヒ hi フ fu ヘ he ホ ho バ ba ビ bi ブ bu ベ be ボ bo パ pa ピ pi プ pu ペ pe ポ po " +
            "マ ma ミ mi ム mu メ me モ mo ヤ ya ユ yu ヨ yo ラ ra リ ri ル ru レ re ロ ro " +
            "ワ wa ヰ i ヱ e ヲ o ヴ vu ァ a ィ i ゥ u ェ e ォ o ヮ wa ヵ ka ヶ ke"
            ).split(" ").chunked(2).associate { it[0][0] to it[1] }
        private val SY = mapOf('ャ' to "a", 'ュ' to "u", 'ョ' to "o")
        private val SV = mapOf('ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o")
        private val MAC = mapOf('a' to 'ā', 'i' to 'ī', 'u' to 'ū', 'e' to 'ē', 'o' to 'ō')
        private val PUNCT = mapOf(
            "。" to ".", "、" to ",", "「" to "“", "」" to "”", "『" to "“", "』" to "”",
            "・" to " ", "〜" to "~", "～" to "~", "―" to "—"
        )
        private val CLOSE = setOf(".", ",", "!", "?", ":", ";", ")", "]", "”", "’", "...", "~", "…")
        private val OPEN = setOf("“", "(", "[", "‘")
        private val PUNCT_ONLY = Regex("""^[^\p{L}\p{N}\s]+$""")
        private val KANA = Regex("^[\u3040-\u30ff]+$")
        private val SENTENCE_START = Regex("(^|[.!?]\\s+|[“\"]\\s*)([a-zāīūēō])")
    }
}
