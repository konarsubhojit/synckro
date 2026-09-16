package com.synckro.domain.sync

/**
 * Precompiled relative-path scope rules shared by full enumeration, targeted
 * candidate resolution, and Instant Sync eligibility.
 *
 * Hidden and temporary leaf files are always rejected. Empty file names are
 * inconclusive and therefore also fail closed. [excludedRelativePaths] (the
 * persisted per-pair folder-exclusion set) is checked before glob patterns and
 * excludes both the exact path and everything nested under it. Ignore globs take
 * precedence over include globs; when the include filter is inactive all
 * non-ignored files are accepted. MediaStore state is evaluated by callers that
 * have access to it; this scope applies only the name-based candidate rules.
 */
class SyncPathScope internal constructor(
    val includeGlobs: List<Regex>,
    val ignoreGlobs: List<Regex>,
    val includeFilterActive: Boolean,
    val excludeSubfolders: Boolean,
    val excludedRelativePaths: List<String> = emptyList(),
) {
    fun contains(relativePath: String): Boolean {
        if (FileCandidatePolicy.evaluate(relativePath) !is FileCandidateDecision.Eligible) return false
        if (excludeSubfolders && relativePath.contains('/')) return false
        if (isExcludedByFolder(relativePath, excludedRelativePaths)) return false

        if (ignoreGlobs.any { it.matches(relativePath) }) return false

        return !includeFilterActive || includeGlobs.any { it.matches(relativePath) }
    }

    companion object {
        private val GLOB_META_CHARS = setOf('\\', '*', '?', '{', '}', '[', ']')

        /**
         * Converts a glob pattern to a [Regex] that can be matched against a full
         * relative file path.
         *
         * Supported wildcards:
         * - `**`  matches any sequence of characters including `/`
         * - `*`   matches any sequence of characters **not** including `/`
         * - `?`   matches a single character **not** including `/`
         * - `{a,b}` matches either alternative `a` or `b`
         * - `[abc]` character classes are passed through as-is
         *
         * The match is case-sensitive on all platforms.
         */
        internal fun globToRegex(glob: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < glob.length) {
                when (val c = glob[i]) {
                    '\\' -> {
                        if (i + 1 < glob.length) {
                            sb.append(Regex.escape(glob[i + 1].toString()))
                            i += 2
                            continue
                        } else {
                            sb.append(Regex.escape(c.toString()))
                        }
                    }
                    '*' -> {
                        if (i + 1 < glob.length && glob[i + 1] == '*') {
                            sb.append(".*")
                            i++ // consume second '*'
                        } else {
                            sb.append("[^/]*")
                        }
                    }
                    '?' -> sb.append("[^/]")
                    '.' -> sb.append("\\.")
                    '{' -> {
                        val end = glob.indexOf('}', i + 1)
                        if (end == -1) {
                            sb.append(Regex.escape(c.toString()))
                        } else {
                            val alternatives = glob.substring(i + 1, end).split(',')
                            sb.append("(?:")
                            alternatives.joinTo(sb, "|") { Regex.escape(it) }
                            sb.append(')')
                            i = end // will be incremented below
                        }
                    }
                    '[' -> {
                        val end = glob.indexOf(']', i + 1)
                        if (end == -1) {
                            sb.append(Regex.escape(c.toString()))
                        } else {
                            sb.append('[')
                            sb.append(glob.substring(i + 1, end))
                            sb.append(']')
                            i = end // will be incremented below
                        }
                    }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            sb.append('$')
            return Regex(sb.toString())
        }

        /**
         * Escapes a literal path segment so it can be embedded safely in a
         * generated glob pattern without being interpreted as glob syntax.
         */
        internal fun escapeGlobLiteral(literal: String): String =
            buildString(literal.length) {
                literal.forEach { ch ->
                    if (ch in GLOB_META_CHARS) {
                        append('\\')
                    }
                    append(ch)
                }
            }

        /**
         * Returns `true` when [relativePath] exactly matches, or is nested under, one
         * of the folder paths in [excludedRelativePaths]. Entries are normalized by
         * trimming whitespace and leading/trailing `/` before comparison, and blank
         * entries are ignored. Matching is by path segment (via a `/` boundary) so
         * an excluded path like `"Photos/Private"` also excludes
         * `"Photos/Private/img.jpg"` but not an unrelated sibling like
         * `"Photos/PrivateNotes"`.
         */
        internal fun isExcludedByFolder(
            relativePath: String,
            excludedRelativePaths: List<String>,
        ): Boolean {
            if (excludedRelativePaths.isEmpty()) return false
            return excludedRelativePaths.any { raw ->
                val normalized = raw.trim().trim('/')
                normalized.isNotEmpty() &&
                    (relativePath == normalized || relativePath.startsWith("$normalized/"))
            }
        }

        /**
         * Converts a set of excluded folder paths (as persisted on
         * [com.synckro.domain.model.SyncPair.excludedRelativePaths]) into ignore-glob
         * patterns matching every file nested under each folder. Useful for callers
         * (such as [com.synckro.data.local.fs.LocalFsEnumerator]) that only understand
         * glob-based ignore lists rather than [SyncPathScope]'s folder-prefix check.
         */
        internal fun excludedFolderIgnoreGlobs(excludedRelativePaths: List<String>): List<String> =
            excludedRelativePaths.mapNotNull { raw ->
                val normalized = raw.trim().trim('/')
                if (normalized.isEmpty()) null else "${escapeGlobLiteral(normalized)}/**"
            }

        /**
         * Single-shot scope check for one relative file path. For batches, call
         * [compile] once and reuse [SyncPathScope.contains].
         */
        fun isInScope(
            relativePath: String,
            includeGlobs: List<String>,
            ignoreGlobs: List<String>,
            excludeSubfolders: Boolean,
            excludedRelativePaths: List<String> = emptyList(),
        ): Boolean =
            compile(
                includeGlobs = includeGlobs,
                ignoreGlobs = ignoreGlobs,
                excludeSubfolders = excludeSubfolders,
                excludedRelativePaths = excludedRelativePaths,
            ).contains(relativePath)

        fun compile(
            includeGlobs: List<String>,
            ignoreGlobs: List<String>,
            excludeSubfolders: Boolean,
            excludedRelativePaths: List<String> = emptyList(),
        ): SyncPathScope =
            SyncPathScope(
                includeGlobs = includeGlobs.mapNotNull { runCatching { globToRegex(it) }.getOrNull() },
                ignoreGlobs = ignoreGlobs.mapNotNull { runCatching { globToRegex(it) }.getOrNull() },
                includeFilterActive = includeGlobs.isNotEmpty(),
                excludeSubfolders = excludeSubfolders,
                excludedRelativePaths = excludedRelativePaths,
            )
    }
}
