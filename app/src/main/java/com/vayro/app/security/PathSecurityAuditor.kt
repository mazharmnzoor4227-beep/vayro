package com.vayro.app.security

import java.io.File

/**
 * M5: Path security auditor. Pure-JVM, deterministic.
 *
 * Audits a requested file path against an allowed root directory and rejects:
 * - null bytes / control characters (path smuggling)
 * - `..` segments that escape the allowed root (path traversal)
 * - absolute paths outside the allowed root
 * - symlinks whose resolved target escapes the allowed root (symlink escape)
 *
 * When [allowedRoot] is null or cannot be canonicalized, the auditor fails
 * closed: absolute paths are rejected outright and only structural checks
 * (null bytes, control characters, `..` escaping the base) apply to relative
 * paths. A missing root never silently disables path restrictions.
 */
class PathSecurityAuditor {

    sealed class PathAuditResult {
        data object Safe : PathAuditResult()
        data class Rejected(val reason: String, val kind: RejectionKind) : PathAuditResult()
    }

    enum class RejectionKind {
        NULL_BYTE,
        TRAVERSAL,
        ABSOLUTE_ESCAPE,
        SYMLINK_ESCAPE
    }

    /**
     * Audits [requestedPath] (absolute or relative) against [allowedRoot].
     * For non-existent paths, the nearest existing ancestor is resolved so
     * symlink escapes in parent directories are still caught.
     */
    fun audit(requestedPath: String, allowedRoot: File?): PathAuditResult {
        if (requestedPath.isBlank()) {
            return PathAuditResult.Rejected("Empty path", RejectionKind.TRAVERSAL)
        }
        if (requestedPath.contains('\u0000')) {
            return PathAuditResult.Rejected(
                "Path contains null byte",
                RejectionKind.NULL_BYTE
            )
        }
        val normalized = requestedPath.replace('\\', '/')
        if (normalized.any { it.code < 0x20 }) {
            return PathAuditResult.Rejected(
                "Path contains control characters",
                RejectionKind.NULL_BYTE
            )
        }

        val root = allowedRoot?.let { runCatching { it.canonicalFile }.getOrNull() }

        // Structural traversal check on the raw segments: any ".." that would
        // climb above the starting point is a traversal attempt.
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        var depth = 0
        for (seg in segments) {
            when (seg) {
                "." -> Unit
                ".." -> {
                    depth--
                    if (depth < 0) {
                        return PathAuditResult.Rejected(
                            "Path traversal: '..' escapes the base directory in '$requestedPath'",
                            RejectionKind.TRAVERSAL
                        )
                    }
                }
                else -> depth++
            }
        }

        val requestedFile = if (File(normalized).isAbsolute) File(normalized) else {
            if (root != null) File(root, normalized) else File(normalized).absoluteFile
        }

        // Containment check against the allowed root.
        // Lexical containment (no symlink resolution) tells us whether the
        // request itself points outside; resolved containment (following
        // symlinks, tolerating non-existent tails) tells us whether a symlink
        // smuggles the target outside. Lexically-inside but resolved-outside
        // is a symlink escape; lexically-outside is an absolute escape.
        if (root == null) {
            if (File(normalized).isAbsolute) {
                return PathAuditResult.Rejected(
                    "Absolute path with no valid allowed root: '$requestedPath'",
                    RejectionKind.ABSOLUTE_ESCAPE
                )
            }
            return PathAuditResult.Safe
        }

        val lexicalInside = isWithin(requestedFile.toPath().normalize().toFile(), root)
        val resolved = resolveRealPath(requestedFile)
        val resolvedOutside = if (resolved != null) !isWithin(resolved, root) else !lexicalInside
        if (resolvedOutside) {
            return if (lexicalInside) {
                PathAuditResult.Rejected(
                    "Symlink escape: resolved path '${resolved?.path}' is outside the allowed root",
                    RejectionKind.SYMLINK_ESCAPE
                )
            } else {
                PathAuditResult.Rejected(
                    "Absolute path escapes allowed root: '$requestedPath'",
                    RejectionKind.ABSOLUTE_ESCAPE
                )
            }
        }

        return PathAuditResult.Safe
    }

    private fun isWithin(candidate: File, root: File): Boolean {
        val rootPath = root.path
        val candPath = candidate.path
        return candPath == rootPath || candPath.startsWith(rootPath + File.separator)
    }

    /**
     * Resolves symlinks for [file], tolerating non-existent tail segments by
     * resolving the nearest existing ancestor. Returns null when nothing can
     * be resolved (fully deterministic, no exceptions escape).
     */
    private fun resolveRealPath(file: File): File? {
        var current: File? = file
        val tail = ArrayDeque<String>()
        while (current != null && !current.exists()) {
            tail.addFirst(current.name)
            current = current.parentFile
        }
        if (current == null) return null
        return runCatching {
            var resolved = current.toPath().toRealPath()
            for (seg in tail) {
                resolved = resolved.resolve(seg)
            }
            // Normalize without requiring existence of the tail.
            resolved.normalize().toFile()
        }.getOrNull()
    }

    /**
     * Convenience: true when [audit] reports [PathAuditResult.Safe].
     */
    fun isSafe(requestedPath: String, allowedRoot: File?): Boolean =
        audit(requestedPath, allowedRoot) is PathAuditResult.Safe
}
