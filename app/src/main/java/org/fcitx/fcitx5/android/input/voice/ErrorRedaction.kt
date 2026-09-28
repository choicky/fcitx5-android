/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import java.io.File

/**
 * Failure details come from exceptions and server replies, which can echo request URLs (a
 * signed Tencent URL carries its SecretId and signature in the query), headers or keys. Every
 * detail is passed through here before it is logged, shown or stored.
 */
internal object ErrorRedaction {

    private const val MASK = "***"

    /** Upper bound for a stored or displayed detail. */
    const val MAX_LENGTH = 160

    private val url = Regex("""\b((?:wss?|https?)://)(?:[^\s/@?#]*@)?([^\s/?#]*)([^\s?#]*)([?#][^\s]*)?""", RegexOption.IGNORE_CASE)
    private val bearer = Regex("""\b(Bearer|Basic)\s+[^\s,;"']+""", RegexOption.IGNORE_CASE)
    private val keyValue = Regex(
        """\b((?:x-)?api[-_]?key|access[-_]?(?:key|token)|app[-_]?key|secret[-_]?(?:id|key)?|signature|token|password|authorization)(["']?\s*[=:]\s*["']?)[^\s&"',;}]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Runs as long as typical keys (32 hex, `sk-…`, `AKID…`, UUIDs). Only runs where letters and
     * digits alternate like random text are masked, so names such as
     * `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` stay readable. Log IDs are
     * kept (see [redact]).
     */
    private val opaque = Regex("""[A-Za-z0-9+/_\-]{24,}={0,2}""")
    private val logId = Regex("""(X-Tt-Logid=)([A-Za-z0-9]+)""")

    fun redact(detail: String, secrets: Collection<String> = emptyList()): String {
        var text = detail
        // exact values first, so a key is masked even where no pattern below would match it
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach {
            text = text.replace(it, MASK)
        }
        // user info, query and fragment go; scheme, host and path stay useful for diagnosis
        text = url.replace(text) { m ->
            val (scheme, host, path, rest) = m.destructured
            scheme + host + path + if (rest.isEmpty()) "" else "?$MASK"
        }
        text = bearer.replace(text) { "${it.groupValues[1]} $MASK" }
        text = keyValue.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        val kept = mutableListOf<String>()
        text = logId.replace(text) { kept += it.groupValues[2]; "${it.groupValues[1]}\u0000${kept.size - 1}\u0000" }
        text = opaque.replace(text) { if (looksRandom(it.value)) MASK else it.value }
        kept.forEachIndexed { i, id -> text = text.replace("\u0000$i\u0000", id) }
        return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH - 1) + "…" else text
    }

    private fun looksRandom(run: String) =
        run.zipWithNext().count { (a, b) -> a.isLetter() && b.isDigit() || a.isDigit() && b.isLetter() } >= 4
}

/**
 * The most recent service failure, as "<service key>\n<redacted detail>". It lives in the
 * no-backup directory, outside shared preferences, so it is neither backed up nor part of the
 * user-data export.
 */
internal class LastErrorRecord(private val file: () -> File) {

    fun read(): Pair<String, String>? = runCatching { file().readText() }.getOrNull()
        ?.split('\n', limit = 2)
        ?.takeIf { it.size == 2 && it[0].isNotEmpty() }
        ?.let { it[0] to it[1] }

    fun write(value: Pair<String, String>?) {
        runCatching {
            val target = file()
            if (value == null) {
                target.delete()
                return
            }
            val (service, detail) = value
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            // service keys never contain a newline; the detail is redacted again on the way in
            tmp.writeText(service.replace('\n', ' ') + "\n" + ErrorRedaction.redact(detail.replace('\n', ' ')))
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }
}
