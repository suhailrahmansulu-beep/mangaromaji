from romaji import Romanizer

r = Romanizer()
cases = {
    "私は東京へ行きます。": "Tōkyō",
    "おはよう、田中さん！": "Tanaka-san",
    "お母さんが学校に行った": "itta",
    "ちょっと待って…何してるの？": "matte",
    "一緒に帰ろう": "issho",
    "ファイトだ、これは本です": "hon desu",
}
for src, must in cases.items():
    out = r.convert(src)
    print(src, "=>", out)
    assert must.lower() in out.lower(), f"expected {must!r} in {out!r}"
print("romaji self-test OK")
