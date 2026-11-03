from romaji import Romanizer, clean_ocr

r = Romanizer()
cases = {
    "私は東京へ行きます。": "Tōkyō",
    "おはよう、田中さん！": "Tanaka-san",
    "お母さんが学校に行った": "itta",
    "ちょっと待って…何してるの？": "matte",
    "一緒に帰ろう": "issho",
    "ファイトだ、これは本です": "hon desu",
    "神里綾華さんが好き": "Kamisato Ayaka-san ga suki",
    "魈は強い": "Shō wa tsuyoi",
    "原神をやってる": "Genshin o",
    clean_ocr("コ一ヒ一を飲む"): "kōhī o nomu",
    "璃月港に行こう": "Ritsuki-kō ni",
}
for src, must in cases.items():
    out = r.convert(src)
    print(ascii(src), "=>", ascii(out))
    assert must.lower() in out.lower(), f"expected {must!r} in {out!r}"
print("romaji self-test OK")
