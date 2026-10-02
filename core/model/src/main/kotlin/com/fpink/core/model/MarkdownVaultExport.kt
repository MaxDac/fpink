package com.fpink.core.model

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** One Markdown file inside an exported vault; [path] is relative and uses `/` separators. */
data class VaultDocument(val path: String, val content: String)

data class VaultOptions(
    /** Place notes in one folder per Zettelkasten category and tag them with their category. */
    val groupByCategory: Boolean,
    val exportedAt: Instant,
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
)

/**
 * Builds an Obsidian-compatible Markdown vault: one note per file with YAML front matter,
 * `[[wikilinks]]` between exported notes, and source images embedded from `attachments/`.
 * The same layout is understood by other FOSS Markdown note tools (Logseq, Zettlr, Foam…).
 */
object MarkdownVaultExport {
    const val ATTACHMENTS_DIR = "attachments"
    const val INDEX_FILE = "FPInk index.md"
    private const val MAX_TITLE_LENGTH = 60
    private const val TITLE_WORDS = 8
    private val FORBIDDEN_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|#^\\[\\]\\p{Cntrl}]")
    private val WHITESPACE = Regex("\\s+")
    private val TAG_FORBIDDEN = Regex("[^\\p{L}\\p{N}_/-]")

    /** Vault path where the source image at [imagePath] (e.g. `images/abc.jpg`) is stored. */
    fun attachmentPath(imagePath: String): String = "$ATTACHMENTS_DIR/${imagePath.substringAfterLast('/')}"

    fun categoryFolder(category: ZettelkastenCategory): String =
        category.name.lowercase().replaceFirstChar { it.uppercaseChar() }

    /**
     * [embeddedImages] lists the source `imagePath`s actually written to the vault; notes whose
     * image is absent are exported without an embed rather than with a broken link.
     */
    fun build(notes: List<Note>, options: VaultOptions, embeddedImages: Set<String>): List<VaultDocument> {
        val names = uniqueNames(notes, options)
        val documents = notes.map { note ->
            val name = names.getValue(note.id)
            VaultDocument(path = "${folderPrefix(note, options)}$name.md", content = noteContent(note, names, options, embeddedImages))
        }
        return documents + VaultDocument(INDEX_FILE, indexContent(notes, names, options))
    }

    private fun folderPrefix(note: Note, options: VaultOptions): String =
        if (options.groupByCategory) "${categoryFolder(note.zettelkastenCategory)}/" else ""

    private fun uniqueNames(notes: List<Note>, options: VaultOptions): Map<String, String> {
        // Obsidian resolves wikilinks by file name across folders, so names are vault-unique.
        val used = mutableSetOf(INDEX_FILE.removeSuffix(".md").lowercase())
        return notes.associate { note ->
            val base = "${timestamp(note.capturedAt, options.timeZone)} ${title(note)}".trim()
            var candidate = base
            var counter = 2
            while (!used.add(candidate.lowercase())) candidate = "$base (${counter++})"
            note.id to candidate
        }
    }

    internal fun title(note: Note): String {
        val firstLine = note.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val words = firstLine.replace(FORBIDDEN_FILENAME_CHARS, " ").trim().split(WHITESPACE).filter { it.isNotEmpty() }
        var title = words.take(TITLE_WORDS).joinToString(" ")
        if (title.length > MAX_TITLE_LENGTH) {
            // Never split a surrogate pair: a lone surrogate cannot be encoded in a file name.
            val end = if (title[MAX_TITLE_LENGTH - 1].isHighSurrogate()) MAX_TITLE_LENGTH - 1 else MAX_TITLE_LENGTH
            title = title.take(end)
        }
        title = title.trimStart('.', ' ').trimEnd('.', ' ')
        return title.ifEmpty { "Untitled" }
    }

    private fun timestamp(instant: Instant, timeZone: TimeZone): String {
        val local = instant.toLocalDateTime(timeZone)
        fun two(value: Int) = value.toString().padStart(2, '0')
        return "${local.year}-${two(local.month.ordinal + 1)}-${two(local.day)} ${two(local.hour)}${two(local.minute)}"
    }

    private fun noteContent(
        note: Note,
        names: Map<String, String>,
        options: VaultOptions,
        embeddedImages: Set<String>,
    ): String = buildString {
        appendLine("---")
        appendLine("id: ${yamlString(note.id)}")
        appendLine("created: ${yamlString(note.capturedAt.toString())}")
        appendLine("type: ${note.zettelkastenCategory.name.lowercase()}")
        appendLine("tags: ${yamlList(tags(note, options))}")
        appendLine("aliases: ${yamlList(listOf(title(note)))}")
        note.inkColorHex?.let { appendLine("ink-color: ${yamlString(it)}") }
        note.inkColorName?.let { appendLine("ink-color-name: ${yamlString(it)}") }
        note.sourceId?.let { appendLine("source: ${yamlString(it)}") }
        note.paragraphIndex?.let { appendLine("paragraph: $it") }
        note.recognitionProvider?.let { provider ->
            val version = note.recognitionModelVersion?.let { " $it" }.orEmpty()
            appendLine("recognition: ${yamlString(provider + version)}")
        }
        note.confidence?.let { appendLine("confidence: $it") }
        appendLine("user-edited: ${note.userEdited}")
        appendLine("---")
        appendLine()
        appendLine(note.text.trimEnd())
        if (note.imagePath in embeddedImages) {
            appendLine()
            appendLine("![[${attachmentPath(note.imagePath)}]]")
        }
        if (note.links.isNotEmpty()) {
            appendLine()
            appendLine("## Links")
            appendLine()
            note.links.forEach { target ->
                appendLine(names[target]?.let { "- [[$it]]" } ?: "- ${target}")
            }
        }
    }

    private fun tags(note: Note, options: VaultOptions): List<String> = buildList {
        add("fpink")
        if (options.groupByCategory) add("zettelkasten/${note.zettelkastenCategory.name.lowercase()}")
        note.tags.forEach { tag ->
            val cleaned = tag.trim().removePrefix("#").replace(WHITESPACE, "-").replace(TAG_FORBIDDEN, "")
            if (cleaned.isNotEmpty() && cleaned !in this) add(cleaned)
        }
    }

    private fun indexContent(notes: List<Note>, names: Map<String, String>, options: VaultOptions): String = buildString {
        appendLine("---")
        appendLine("exported: ${yamlString(options.exportedAt.toString())}")
        appendLine("notes: ${notes.size}")
        appendLine("tags: ${yamlList(listOf("fpink"))}")
        appendLine("---")
        appendLine()
        appendLine("# FPInk export")
        if (options.groupByCategory) {
            ZettelkastenCategory.entries.forEach { category ->
                val members = notes.filter { it.zettelkastenCategory == category }
                if (members.isEmpty()) return@forEach
                appendLine()
                appendLine("## ${categoryFolder(category)}")
                appendLine()
                members.forEach { appendLine("- [[${names.getValue(it.id)}]]") }
            }
        } else {
            appendLine()
            notes.forEach { appendLine("- [[${names.getValue(it.id)}]]") }
        }
    }

    private fun yamlList(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { yamlString(it) }

    internal fun yamlString(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.isISOControl()) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
