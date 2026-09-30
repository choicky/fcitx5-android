/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

/**
 * Provenance and integrity metadata for a Phase 5B dictionary release artifact.
 *
 * Artifact hash and size fields mirror the published release's index.json. That
 * release index and SHA256SUMS are the authoritative metadata for downloads.
 */
internal data class PinyinDictionaryCatalogEntry(
    val id: String,
    val displayName: String,
    val canonicalName: String = displayName,
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String,
    val sourceRepository: String,
    val sourceRevision: String,
    val sourceInputSha256: String? = null,
    val limitations: String,
    val attribution: String,
    val modificationStatement: String,
    /** Number of rows in the compiled dictionary dump, when supplied by the release index. */
    val entryCount: Long? = null,
    val researchOnly: Boolean = false,
    val publicReleaseApproved: Boolean = true,
    /** A private artifact is selected through the existing local-import flow, not HTTP. */
    val privateImportOnly: Boolean = false
) {
    val fileName = "$id.dict"
}

internal object PinyinDictionaryCatalog {
    const val RELEASE_TAG = "dictionary-v1.1.1"

    private const val RELEASE_BASE =
        "https://github.com/choicky/fcitx5-moqi/releases/download/$RELEASE_TAG"

    val entries = listOf(
        PinyinDictionaryCatalogEntry(
            id = "rime-ice",
            displayName = "雾凇",
            canonicalName = "Rime-Ice",
            version = "research-3aea6d36",
            url = "",
            size = 33_670_856,
            sha256 = "d1ee425424834ffa1508583fff4b98c9b4193c03128451dcf6e050f4bd00b2fb",
            license = "GPL-3.0-only (Rime-Ice project; external data permissions pending)",
            licenseUrl = "https://raw.githubusercontent.com/iDvel/rime-ice/3aea6d3694fb3d94ec663641f021f788822897ad/LICENSE",
            sourceRepository = "https://github.com/iDvel/rime-ice",
            sourceRevision = "3aea6d3694fb3d94ec663641f021f788822897ad",
            sourceInputSha256 = "491cba198fac1373dc694571629026a8211a5edb3bc0f6056f75a0c011b1c02f",
            limitations = "Private/research testing only. Huayu and indiejoseph Gist public-distribution provenance/permission is pending; do not redistribute this artifact.",
            attribution = "Rime-Ice contributors; includes 8105/base/ext/tencent/others source tables. Preserve Ice and upstream data-source attribution.",
            modificationStatement = "Materialized through pinned Librime and converted/normalized for Fcitx5/LibIME compatibility; exact official negative values are retained.",
            entryCount = 1_885_206,
            researchOnly = true,
            publicReleaseApproved = false,
            privateImportOnly = true
        ),
        PinyinDictionaryCatalogEntry(
            id = "rime-frost",
            displayName = "白霜",
            canonicalName = "Rime-Frost",
            version = RELEASE_TAG,
            url = "$RELEASE_BASE/rime-frost.dict",
            size = 37_322_174,
            sha256 = "b4880861161d585b21413fe554aa8f416beb39d68cf4ce3fba728df5fea584ff",
            license = "GPL-3.0-only",
            licenseUrl = "https://raw.githubusercontent.com/gaboolic/rime-frost/211de1ca927b6c876e384c6de42e1cc8af868c68/LICENSE",
            sourceRepository = "https://github.com/gaboolic/rime-frost",
            sourceRevision = "211de1ca927b6c876e384c6de42e1cc8af868c68",
            sourceInputSha256 = "d14de272e0c39b8c446618bcc0be8bb07e5a7bdfc8d42cfa8c34b5e4f863ae2c",
            limitations = "Rime source converted to LibIME pinyindict format.",
            attribution = "Rime-Frost contributors; source revision is pinned above.",
            modificationStatement = "Converted and normalized for Fcitx5/LibIME compatibility.",
            // dictionary-v1.0.0 predates the index entry_count field; this is
            // its authoritative Phase 5B audit roundtrip_rows value.
            entryCount = 2_010_605
        ),
        PinyinDictionaryCatalogEntry(
            id = "rime-wanxiang",
            displayName = "万象",
            canonicalName = "Rime-Wanxiang (jichu)",
            version = RELEASE_TAG,
            url = "$RELEASE_BASE/rime-wanxiang.dict",
            size = 24_683_718,
            sha256 = "492a452604f1d63ec1edf5682846291db72f3caadc3b6cc8e51af52fab3772da",
            license = "CC-BY-4.0",
            licenseUrl = "https://raw.githubusercontent.com/amzxyz/rime-wanxiang/94f1e8d7b6d1267a9c8752a2e62145705dd1fb92/LICENSE",
            sourceRepository = "https://github.com/amzxyz/rime-wanxiang",
            sourceRevision = "94f1e8d7b6d1267a9c8752a2e62145705dd1fb92",
            sourceInputSha256 = "4a6b1d17b812048f217cce0118a44c9ab7b9369768f0ce4ed5a9b6ae40100074",
            limitations = "Only the pinned jichu table is included.",
            attribution = "Rime-Wanxiang contributors; jichu table only.",
            modificationStatement = "Converted and normalized for Fcitx5/LibIME compatibility.",
            // dictionary-v1.0.0 predates the index entry_count field; this is
            // its authoritative Phase 5B audit roundtrip_rows value.
            entryCount = 1_425_249
        ),
        PinyinDictionaryCatalogEntry(
            id = "zhwiki",
            displayName = "中文维基",
            canonicalName = "fcitx5-pinyin-zhwiki",
            version = RELEASE_TAG,
            url = "$RELEASE_BASE/zhwiki.dict",
            size = 34_496_975,
            sha256 = "9b24a65edc15e6e440cf0bf85308f69dc11359c82abcbea1904da71693eca4a5",
            license = "GFDL-1.3-or-later AND CC-BY-SA-4.0 (Wikimedia data; exceptions apply)",
            licenseUrl = "https://dumps.wikimedia.org/legal.html",
            sourceRepository = "https://github.com/felixonmars/fcitx5-pinyin-zhwiki",
            sourceRevision = "b0e079a6dadd30e67fd355e8f6e73b294367aac6",
            sourceInputSha256 = "9bb6fd03f0350cc13340ec34ff59f695bdf7eb0f14db6acf13c1ebc91f89823b",
            limitations = "Wikimedia-derived data; applicable Terms of Use, exceptions, attribution and ShareAlike/source obligations apply.",
            attribution = "fcitx5-pinyin-zhwiki and Wikimedia dumps; preserve Wikimedia attribution and license notices.",
            modificationStatement = "Derived from upstream/Wikimedia data and converted/normalized for Fcitx5/LibIME compatibility.",
            entryCount = 1_673_006
        ),
        PinyinDictionaryCatalogEntry(
            id = "custom-pinyin",
            displayName = "自定义拼音词库",
            canonicalName = "CustomPinyinDictionary",
            version = RELEASE_TAG,
            url = "$RELEASE_BASE/custom-pinyin.dict",
            size = 28_438_655,
            sha256 = "3b0a69679b71a3d3b88e9210906bea340c7633809fa872d2cfb9b53b00fe5555",
            license = "CC-BY-SA-4.0",
            licenseUrl = "https://raw.githubusercontent.com/wuhgit/CustomPinyinDictionary/cf17f96af885cb818c2fad87184f383a52482351/LICENSE",
            sourceRepository = "https://github.com/wuhgit/CustomPinyinDictionary",
            sourceRevision = "0673212e83c9db1fef24fdf950b22c994bf27e9c",
            sourceInputSha256 = "63677b0e1bcd9276e8eeef41553ab532bf6061278558d9efa3629b0ebe8836e5",
            limitations = "Normalized against the pinned official LibIME Base; upstream README attribution for included third-party sources remains applicable.",
            attribution = "wuhgit/CustomPinyinDictionary; preserve the upstream README's listed source attributions.",
            modificationStatement = "Converted and normalized for Fcitx5/LibIME compatibility.",
            entryCount = 1_498_781
        )
    )

    fun find(id: String) = entries.firstOrNull { it.id == id }
}
