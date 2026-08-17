package moe.antimony.hoshi.features.reader

object ReaderRecommendedFontCatalog {
    const val sourceUrl =
        "https://github.com/google/fonts/tree/${ReaderRemoteFontFile.GOOGLE_FONTS_COMMIT}/ofl"
    const val licenseUrl =
        "https://github.com/google/fonts/blob/${ReaderRemoteFontFile.GOOGLE_FONTS_COMMIT}/ofl/notosansjp/OFL.txt"

    val families: List<ReaderFontFamily> by lazy { listOf(
        variableFamily("notoserifjp", "Noto Serif JP", ReaderFontCategory.SERIF, 200..900 step 100,
            "ofl/notoserifjp/NotoSerifJP[wght].ttf", "NotoSerifJP-wght.ttf", 13_574_352,
            "2fd527ba12b6a44ec30d796d633360da0aeba6c5d4af1304ce12bb4dc15a7dfc", 200..900),
        staticFamily("shipporimincho", "Shippori Mincho", ReaderFontCategory.SERIF, listOf(
            static(400, "Regular"), static(500, "Medium"), static(600, "SemiBold"),
            static(700, "Bold"), static(800, "ExtraBold"),
        )),
        staticFamily("bizudpmincho", "BIZ UDPMincho", ReaderFontCategory.SERIF, listOf(
            static(400, "Regular"), static(700, "Bold"),
        )),
        staticFamily("zenoldmincho", "Zen Old Mincho", ReaderFontCategory.SERIF, listOf(
            static(400, "Regular"), static(500, "Medium"), static(600, "SemiBold"),
            static(700, "Bold"), static(900, "Black"),
        )),
        variableFamily("notosansjp", "Noto Sans JP", ReaderFontCategory.SANS_SERIF, 100..900 step 100,
            "ofl/notosansjp/NotoSansJP[wght].ttf", "NotoSansJP-wght.ttf", 9_589_900,
            "c2f3b4d463500a2ddcd3849cded1fceeb9fd6d1c32e6cbecd568453ba50fc68f", 100..900),
        staticFamily("bizudpgothic", "BIZ UDPGothic", ReaderFontCategory.SANS_SERIF, listOf(
            static(400, "Regular"), static(700, "Bold"),
        )),
        staticFamily("zenkakugothicnew", "Zen Kaku Gothic New", ReaderFontCategory.SANS_SERIF, listOf(
            static(300, "Light"), static(400, "Regular"), static(500, "Medium"),
            static(700, "Bold"), static(900, "Black"),
        )),
        staticFamily("mplusrounded1c", "M PLUS Rounded 1c", ReaderFontCategory.ROUNDED, listOf(
            static(100, "Thin"), static(300, "Light"), static(400, "Regular"),
            static(500, "Medium"), static(700, "Bold"), static(800, "ExtraBold"), static(900, "Black"),
        )),
        staticFamily("kiwimaru", "Kiwi Maru", ReaderFontCategory.ROUNDED, listOf(
            static(300, "Light"), static(400, "Regular"), static(500, "Medium"),
        )),
        staticFamily("kleeone", "Klee One", ReaderFontCategory.HANDWRITING, listOf(
            static(400, "Regular"), static(600, "SemiBold"),
        )),
    ) }

    private data class StaticVariant(val weight: Int, val suffix: String)
    private data class StaticMetadata(val size: Long, val sha256: String)

    private val staticMetadata = mapOf(
        "shipporimincho/Regular" to StaticMetadata(8_677_284, "769b5269f0f9bc6534b352c0e6bd856a566e03ff788f107191c2d835863570b2"),
        "shipporimincho/Medium" to StaticMetadata(8_678_848, "700e505afc4cded2338eba29478a041e04c1c2ea5114fbb3e0b04e76c302c5d8"),
        "shipporimincho/SemiBold" to StaticMetadata(8_650_032, "bc7925544894a91466449adb73c6d943f50c3e53eb1c74d0673fe2dbafcd4d2d"),
        "shipporimincho/Bold" to StaticMetadata(8_563_788, "63bc4eddc74793f671c3ab827c5175e773ffbe569d0bf50ee65375ea9e3bc286"),
        "shipporimincho/ExtraBold" to StaticMetadata(8_563_208, "bdb787644b4b347e9a7efdd576f0d16ee4528cc9b5c86d23e06fa1a14ae0444c"),
        "bizudpmincho/Regular" to StaticMetadata(6_156_444, "dbcea04578ac1e9d3484525e870ce491bd04361768f4d2ba4b827d96e20f891d"),
        "bizudpmincho/Bold" to StaticMetadata(7_109_432, "99618e39b881597efc03d58706bd194757c968c1bf7a7017a893efead6b1b260"),
        "zenoldmincho/Regular" to StaticMetadata(5_442_512, "4c051a78a21c4e8e9dccf1c754776d33f356b8cc6ef95d9b64761b9bae814b84"),
        "zenoldmincho/Medium" to StaticMetadata(5_502_836, "e60c7961e5110d0f08f902de43fe60865f1538845ff2092c779837257efac3bf"),
        "zenoldmincho/SemiBold" to StaticMetadata(5_521_388, "d98b9783652081f7f9e662b0568bddeaf6462962dd39edf68781268eb60ea3a0"),
        "zenoldmincho/Bold" to StaticMetadata(5_436_460, "d6b95c1ff45c8dac153d28961e4c37d7d03b648330c71f884d124dc652a13c0d"),
        "zenoldmincho/Black" to StaticMetadata(5_416_348, "84a80d8bca79d7d9478935045b216ed003ad40fdea5fd9116d524eb26e872cdc"),
        "bizudpgothic/Regular" to StaticMetadata(4_669_688, "258d7156c165f2ff774b6efee637c22c3b950de0d8a10e501137061bc8085d01"),
        "bizudpgothic/Bold" to StaticMetadata(4_640_596, "30eba52fc837e8b62c97d4b82e6706583149fb7294e3712dd71a655eaea80a90"),
        "zenkakugothicnew/Light" to StaticMetadata(2_181_020, "ad4e9733f96397ed0c99c295d9b3b56e39a0e0bc02b0ac56ce3ade7793a1eef1"),
        "zenkakugothicnew/Regular" to StaticMetadata(2_360_248, "b840cd07a67d89cacca44249ae49aa99ee7640eb5ce623be8d8983d6aabac801"),
        "zenkakugothicnew/Medium" to StaticMetadata(2_328_176, "651a3f7280b7f36262601ee76d8388a8dc4372dcc67aff025a608939a562b525"),
        "zenkakugothicnew/Bold" to StaticMetadata(2_314_424, "0081cedabc4921982fcd061f845a005664ac7fb642af2dd34b4007bc63ccd235"),
        "zenkakugothicnew/Black" to StaticMetadata(2_300_600, "795819a979184981842994d8f4eb9e14ce443d687bd5e731d6ca67ded8f92261"),
        "mplusrounded1c/Thin" to StaticMetadata(2_907_880, "180c0959fff5af21637c3887c0ec47df8164877218ef7e866a7a227f2c1e1a9f"),
        "mplusrounded1c/Light" to StaticMetadata(3_294_556, "ade5c673a5d097b59c7bc5b1a6c1e37d3dd63c3ac98468a647d8ab2392b98b49"),
        "mplusrounded1c/Regular" to StaticMetadata(3_389_792, "b75708b53e45b06d17d470aeeca5b766e3d1b3999f03f13ec4eb863ca846c14c"),
        "mplusrounded1c/Medium" to StaticMetadata(3_432_624, "adfde1b6bae58719c4e0144612a94232e72fc5ca655c4722165fe88d06521a70"),
        "mplusrounded1c/Bold" to StaticMetadata(3_542_592, "c358630584e8e2d8fbd6121d0f4693255ffef6d1e6d4f3441fd6e5a963a11f9e"),
        "mplusrounded1c/ExtraBold" to StaticMetadata(3_628_512, "8e7c15901dca87f1451b356dda594f7d092ba252a5dcc47da74523a242493c36"),
        "mplusrounded1c/Black" to StaticMetadata(3_635_504, "d5981a59ccc5f00da1bd3ae46750fa95cd165b0e6b3a5fc7a1945f94c59449e3"),
        "kiwimaru/Light" to StaticMetadata(4_982_684, "30f856bc944911b025bfbf640bf6b9ffe18a9c7b06b20d4ef26fe5cc9b3819f8"),
        "kiwimaru/Regular" to StaticMetadata(5_065_572, "b0c3103b2639f690c1fcb44e060058383174bfd2eb72e6635bc9869b374dee87"),
        "kiwimaru/Medium" to StaticMetadata(5_123_612, "b2659f300a7d48c3f29eb273ffc5e1b26cc416ac8c37ff6bb2f3e43c2f4d235a"),
        "kleeone/Regular" to StaticMetadata(8_724_204, "bf4063f030cc2ae6adf0a11424a1888e5c0eb4438f1f6d02f52294af868e9b3a"),
        "kleeone/SemiBold" to StaticMetadata(8_905_128, "b031ec426c23ca1143ef1f7d58bee7a79efe119ed654152f121c922202b303fd"),
    )

    private fun static(weight: Int, suffix: String) = StaticVariant(weight, suffix)

    private fun variableFamily(
        slug: String,
        name: String,
        category: ReaderFontCategory,
        weights: Iterable<Int>,
        path: String,
        fileName: String,
        size: Long,
        sha256: String,
        range: IntRange,
    ): ReaderFontFamily {
        val remote = ReaderRemoteFontFile(path, fileName, size, sha256, range)
        return recommendedFamily(slug, name, category, weights.map { weight ->
            ReaderFontVariant(
                id = "wght-$weight-normal",
                displayName = weightName(weight),
                weight = weight,
                variationSettings = mapOf("wght" to weight.toFloat()),
                remoteFile = remote,
            )
        })
    }

    private fun staticFamily(
        slug: String,
        name: String,
        category: ReaderFontCategory,
        variants: List<StaticVariant>,
    ): ReaderFontFamily = recommendedFamily(slug, name, category, variants.map { variant ->
        val compactName = name.replace(" ", "")
            .replace("MPLUS", "MPLUS")
        val fileName = when (slug) {
            "mplusrounded1c" -> "MPLUSRounded1c-${variant.suffix}.ttf"
            "bizudpmincho" -> "BIZUDPMincho-${variant.suffix}.ttf"
            "bizudpgothic" -> "BIZUDPGothic-${variant.suffix}.ttf"
            "kiwimaru" -> "KiwiMaru-${variant.suffix}.ttf"
            "kleeone" -> "KleeOne-${variant.suffix}.ttf"
            "shipporimincho" -> "ShipporiMincho-${variant.suffix}.ttf"
            "zenoldmincho" -> "ZenOldMincho-${variant.suffix}.ttf"
            "zenkakugothicnew" -> "ZenKakuGothicNew-${variant.suffix}.ttf"
            else -> "$compactName-${variant.suffix}.ttf"
        }
        val metadata = requireNotNull(staticMetadata["$slug/${variant.suffix}"])
        ReaderFontVariant(
            id = "wght-${variant.weight}-normal",
            displayName = weightName(variant.weight),
            weight = variant.weight,
            remoteFile = ReaderRemoteFontFile(
                path = "ofl/$slug/$fileName",
                fileName = fileName,
                expectedSize = metadata.size,
                sha256 = metadata.sha256,
            ),
        )
    })

    private fun recommendedFamily(
        slug: String,
        name: String,
        category: ReaderFontCategory,
        variants: List<ReaderFontVariant>,
    ) = ReaderFontFamily(
        id = "recommended:$slug",
        displayName = name,
        cssFamily = "hoshi-font-recommended-$slug",
        source = ReaderFontSource.RECOMMENDED,
        category = category,
        variants = variants,
    )

    fun weightName(weight: Int): String = when (weight) {
        100 -> "Thin"
        200 -> "Extra Light"
        300 -> "Light"
        400 -> "Regular"
        500 -> "Medium"
        600 -> "Semi Bold"
        700 -> "Bold"
        800 -> "Extra Bold"
        900 -> "Black"
        else -> weight.toString()
    }
}
