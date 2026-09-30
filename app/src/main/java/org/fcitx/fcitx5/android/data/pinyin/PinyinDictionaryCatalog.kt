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
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val license: String,
    val sourceRepository: String,
    val sourceRevision: String,
    val sourceInputSha256: String,
    val limitations: String,
    /** Number of rows in the compiled dictionary dump, when supplied by the release index. */
    val entryCount: Long? = null
) {
    val fileName = "$id.dict"
}

internal object PinyinDictionaryCatalog {
    const val RELEASE_TAG = "dictionary-v1.0.0"

    private const val RELEASE_BASE =
        "https://github.com/choicky/fcitx5-moqi/releases/download/$RELEASE_TAG"

    val entries = listOf(
        PinyinDictionaryCatalogEntry(
            id = "rime-frost",
            displayName = "Rime Frost",
            version = RELEASE_TAG,
            url = "$RELEASE_BASE/rime-frost.dict",
            size = 37_322_174,
            sha256 = "b4880861161d585b21413fe554aa8f416beb39d68cf4ce3fba728df5fea584ff",
            license = "GPL-3.0-only",
            sourceRepository = "https://github.com/gaboolic/rime-frost",
            sourceRevision = "211de1ca927b6c876e384c6de42e1cc8af868c68",
            sourceInputSha256 = "d14de272e0c39b8c446618bcc0be8bb07e5a7bdfc8d42cfa8c34b5e4f863ae2c",
            limitations = "Rime source converted to LibIME pinyindict format.",
            // dictionary-v1.0.0 predates the index entry_count field; this is
            // its authoritative Phase 5B audit roundtrip_rows value.
            entryCount = 2_010_605
        ),
        PinyinDictionaryCatalogEntry(
            id = "rime-wanxiang",
            displayName = "Rime Wanxiang (jichu)",
            version = RELEASE_TAG,
            url = "$RELEASE_BASE/rime-wanxiang.dict",
            size = 24_683_718,
            sha256 = "492a452604f1d63ec1edf5682846291db72f3caadc3b6cc8e51af52fab3772da",
            license = "CC-BY-4.0",
            sourceRepository = "https://github.com/amzxyz/rime-wanxiang",
            sourceRevision = "94f1e8d7b6d1267a9c8752a2e62145705dd1fb92",
            sourceInputSha256 = "4a6b1d17b812048f217cce0118a44c9ab7b9369768f0ce4ed5a9b6ae40100074",
            limitations = "Only the pinned jichu table is included.",
            // dictionary-v1.0.0 predates the index entry_count field; this is
            // its authoritative Phase 5B audit roundtrip_rows value.
            entryCount = 1_425_249
        )
    )

    fun find(id: String) = entries.firstOrNull { it.id == id }
}
