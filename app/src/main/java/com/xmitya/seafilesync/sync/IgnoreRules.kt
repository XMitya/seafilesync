package com.xmitya.seafilesync.sync

import java.io.File

/**
 * Which local files stay out of the library.
 *
 * Reads `seafile-ignore.txt` from the library root, the same file the desktop client uses, so a
 * library shared between desktop and phone behaves the same on both. Syntax is the gitignore
 * subset Seafile documents: one glob per line, `#` for comments, a trailing `/` for
 * directory-only rules, and a leading `/` to anchor at the library root.
 */
class IgnoreRules private constructor(
    private val patterns: List<Pattern>,
) {

    private data class Pattern(
        val regex: Regex,
        val directoryOnly: Boolean,
        val anchored: Boolean,
    )

    fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean {
        val path = relativePath.trimStart('/')
        if (path.isEmpty()) return false

        return patterns.any { pattern ->
            if (pattern.directoryOnly && !isDirectory) return@any false
            if (pattern.anchored) {
                pattern.regex.matches(path)
            } else {
                // An unanchored rule matches at any depth, so "*.tmp" catches "a/b/c.tmp" too.
                pattern.regex.matches(path) ||
                    path.split('/').any { pattern.regex.matches(it) } ||
                    generateSequence(path) { it.substringAfter('/', "").ifEmpty { null } }
                        .any { pattern.regex.matches(it) }
            }
        }
    }

    companion object {
        const val FILE_NAME = "seafile-ignore.txt"

        val EMPTY = IgnoreRules(emptyList())

        fun load(libraryRoot: File): IgnoreRules {
            val file = File(libraryRoot, FILE_NAME)
            return if (file.isFile) parse(file.readText()) else EMPTY
        }

        fun parse(text: String): IgnoreRules {
            val patterns = text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val directoryOnly = line.endsWith("/")
                    val anchored = line.startsWith("/")
                    val glob = line.trim('/')
                    Pattern(globToRegex(glob), directoryOnly, anchored || glob.contains('/'))
                }.toList()
            return IgnoreRules(patterns)
        }

        /**
         * Translates a glob rather than handing the string to Regex, so that a rule containing
         * regex metacharacters -- a filename with a `+` or `(` in it, say -- matches literally
         * instead of turning into an accidental pattern.
         */
        private fun globToRegex(glob: String): Regex = buildString {
            for (char in glob) {
                when (char) {
                    '*' -> append("[^/]*")
                    '?' -> append("[^/]")
                    '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                        append('\\').append(char)
                    else -> append(char)
                }
            }
        }.toRegex()
    }
}
