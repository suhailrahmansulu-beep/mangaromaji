"""Japanese (kanji + kana) -> romaji. Modified Hepburn. Uses MeCab + IPAdic."""
import re
import unicodedata
from collections import namedtuple

import ipadic
import MeCab

Tok = namedtuple("Tok", "surface pos d1 d2 cform reading")

_K = dict(zip(*[iter((
    "ア a イ i ウ u エ e オ o カ ka キ ki ク ku ケ ke コ ko ガ ga ギ gi グ gu ゲ ge ゴ go "
    "サ sa シ shi ス su セ se ソ so ザ za ジ ji ズ zu ゼ ze ゾ zo "
    "タ ta チ chi ツ tsu テ te ト to ダ da ヂ ji ヅ zu デ de ド do "
    "ナ na ニ ni ヌ nu ネ ne ノ no "
    "ハ ha ヒ hi フ fu ヘ he ホ ho バ ba ビ bi ブ bu ベ be ボ bo パ pa ピ pi プ pu ペ pe ポ po "
    "マ ma ミ mi ム mu メ me モ mo ヤ ya ユ yu ヨ yo ラ ra リ ri ル ru レ re ロ ro "
    "ワ wa ヰ i ヱ e ヲ o ヴ vu ァ a ィ i ゥ u ェ e ォ o ヮ wa ヵ ka ヶ ke"
).split(" "))] * 2))
_SY = {"ャ": "a", "ュ": "u", "ョ": "o"}
_SV = {"ァ": "a", "ィ": "i", "ゥ": "u", "ェ": "e", "ォ": "o"}
_MAC = {"a": "ā", "i": "ī", "u": "ū", "e": "ē", "o": "ō"}
_STRIP = {"ā": "a", "ī": "i", "ū": "u", "ē": "e", "ō": "o"}
_PUNCT = {"。": ".", "、": ",", "「": "“", "」": "”", "『": "“", "』": "”",
          "・": " ", "〜": "~", "～": "~", "―": "—"}
_CLOSE = {".", ",", "!", "?", ":", ";", ")", "]", "”", "’", "...", "~", "…"}
_OPEN = {"“", "(", "[", "‘"}
_PUNCT_ONLY = re.compile(r"^[^\w\s]+$")
_KANA = re.compile(r"^[\u3040-\u30ff]+$")
_SENT = re.compile(r"(^|[.!?]\s+|[“\"]\s*)([a-zāīūēō])")
JP = re.compile(r"[\u3040-\u30ff\u3400-\u9fff]")
_GAP = re.compile(r"(?<=[^\x00-\x7F])\s+(?=[^\x00-\x7F])")


def _hira2kata(s):
    return "".join(chr(ord(c) + 0x60) if ("\u3041" <= c <= "\u3096" or c in "\u309d\u309e") else c for c in s)


def _apply_gem(syl):
    if syl.startswith("ch"):
        return "t" + syl
    if syl and syl[0] in "bcdfghjklmnpqrstvwxyz":
        return syl[0] + syl
    return syl


def _combine(b, s):
    r = _K.get(b)
    if not r:
        return None
    if s in _SY:
        v = _SY[s]
        if r in ("shi", "chi", "ji"):
            return r[:-1] + v
        if len(r) > 1 and r.endswith("i"):
            return r[:-1] + "y" + v
        if b in "テデフヴ":
            return r[:-1] + "y" + v
        return None
    if s in _SV:
        v = _SV[s]
        if r == "shi": c = "sh"
        elif r == "chi": c = "ch"
        elif r == "ji": c = "j"
        elif r == "tsu": c = "ts"
        elif b == "ウ": c = "w"
        elif len(r) > 1: c = r[:-1]
        else: return None
        return c + v
    return None


def _to_roma(kata):
    out = []
    gem = False
    i = 0
    n = len(kata)
    while i < n:
        ch = kata[i]
        nx = kata[i + 1] if i + 1 < n else None
        if ch == "ッ":
            gem = True; i += 1; continue
        if ch == "ー":
            if out and out[-1] and out[-1][-1] in _MAC:
                out[-1] = out[-1][:-1] + _MAC[out[-1][-1]]
            i += 1; continue
        syl = None; adv = 1
        if ch in _K and ch not in _SY and ch not in _SV and nx and (nx in _SY or nx in _SV):
            c = _combine(ch, nx)
            if c:
                syl = c; adv = 2
        if syl is None:
            if ch == "ン":
                syl = "n'" if (nx and nx in "アイウエオヤユヨ") else "n"
            elif ch in _K:
                syl = _K[ch]
            else:
                syl = ch
        out.append(_apply_gem(syl) if gem else syl)
        gem = False
        i += adv
    s = "".join(out)
    return s + "\u0001" if gem else s


class _It:
    def __init__(self, text, kind, tok, proper=False, first=False):
        self.text, self.kind, self.tok, self.proper, self.first = text, kind, tok, proper, first


class Romanizer:
    def __init__(self, macrons=True):
        self.tagger = MeCab.Tagger(ipadic.MECAB_ARGS)
        self.macrons = macrons
        self._cache = {}

    def convert(self, raw):
        key = (raw, self.macrons)
        if key not in self._cache:
            text = unicodedata.normalize("NFKC", raw)
            out = " ".join(self._line(l) for l in text.split("\n") if l.strip())
            if not self.macrons:
                out = "".join(_STRIP.get(c, c) for c in out)
            self._cache[key] = out
        return self._cache[key]

    def _tokens(self, line):
        node = self.tagger.parseToNode(line)
        while node:
            s = node.surface
            if s:
                f = node.feature.split(",")
                f += ["*"] * (9 - len(f))
                reading = f[7] if f[7] != "*" else None
                yield Tok(s, f[0], f[1], f[2], f[5], reading)
            node = node.next

    def _token_romaji(self, t):
        s = t.surface
        if t.pos == "助詞":
            if s == "は": return "wa"
            if s == "へ": return "e"
            if s == "を": return "o"
        if s == "こんにちは": return "konnichiwa"
        if s == "こんばんは": return "konbanwa"
        if t.reading:
            return _to_roma(t.reading)
        if _KANA.match(s):
            return _to_roma(_hira2kata(s))
        return s

    @staticmethod
    def _merge_long(w, last):
        def m(x):
            return x.replace("ou", "ō").replace("oo", "ō").replace("uu", "ū").replace("aa", "ā").replace("ee", "ē")
        if last and last.pos == "動詞" and last.cform == "基本形" and re.search(r"(ou|uu)$", w):
            return m(w[:-2]) + w[-2:]
        return m(w)

    def _line(self, line):
        items = []
        glue_next = False
        prev = None
        for t in self._tokens(line):
            s = t.surface
            if t.pos == "記号" or _PUNCT_ONLY.match(s):
                p = _PUNCT.get(s, s)
                if p == " ":
                    prev = t; glue_next = False; continue
                kind = 1 if (p in _CLOSE or all(c in _CLOSE for c in p)) else 2 if p in _OPEN else 0
                items.append(_It(p, kind, t)); prev = t; glue_next = False
                continue
            rt = self._token_romaji(t)
            last = items[-1] if items else None
            can_glue = last is not None and last.kind == 0 and last.first
            copula = (t.pos == "助動詞" and s in ("だ", "です", "だっ", "でし", "だろ", "でしょ")
                      and prev is not None and prev.pos == "名詞")
            contraction = t.pos == "動詞" and t.d1 == "非自立" and s[:1] in ("て", "で")
            suffix = (contraction or (not copula and t.pos == "助動詞") or t.d1 == "接尾"
                      or (t.pos == "助詞" and t.d1 == "接続助詞" and s in ("て", "で")))
            if can_glue and (glue_next or suffix):
                hyphen = t.d1 == "接尾" and t.d2 == "人名" and prev is not None and prev.d1 == "固有名詞"
                x = rt
                if last.text.endswith("\u0001"):
                    last.text = last.text[:-1]
                    x = _apply_gem(x)
                last.text += ("-" if hyphen else "") + x
                last.tok = t
            else:
                if last is not None and last.text.endswith("\u0001"):
                    last.text = last.text[:-1]
                items.append(_It(rt, 0, t, proper=(t.d1 == "固有名詞"), first=True))
            glue_next = t.pos == "接頭詞"
            prev = t
        out = ""
        pk = "start"
        for it in items:
            txt = it.text.replace("\u0001", "")
            if it.kind == 0 and it.first:
                txt = self._merge_long(txt, it.tok)
                if it.proper and txt:
                    txt = txt[0].upper() + txt[1:]
            if it.kind == 1:
                out += txt; pk = "close"
            elif it.kind == 2:
                out += (" " if out and pk != "open" else "") + txt; pk = "open"
            else:
                out += (" " if out and pk != "open" else "") + txt; pk = "word"
        return _SENT.sub(lambda m: m.group(1) + m.group(2).upper(), out)


def clean_ocr(text):
    """Remove spaces OCR puts between Japanese characters."""
    return _GAP.sub("", text)
