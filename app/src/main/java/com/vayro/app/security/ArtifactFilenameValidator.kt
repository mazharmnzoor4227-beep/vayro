package com.vayro.app.security

import java.io.File
import java.util.UUID

object ArtifactFilenameValidator {

    sealed interface FilenameValidation {
        data object Valid : FilenameValidation
        data class Invalid(val reason: String) : FilenameValidation
    }

    fun validate(filename: String): FilenameValidation {
        if (filename.isBlank()) return FilenameValidation.Invalid("blank filename")
        if (filename.contains('\u0000')) return FilenameValidation.Invalid("null byte in filename")
        if (filename.any { it.code < 0x20 }) return FilenameValidation.Invalid("control characters in filename")
        if (filename.contains('/') || filename.contains('\\')) {
            return FilenameValidation.Invalid("filename must be a single path segment")
        }
        if (filename == "." || filename == "..") return FilenameValidation.Invalid("dot segment")
        return FilenameValidation.Valid
    }

    fun resolveTarget(
        destinationDir: File,
        filename: String,
        pathAuditor: PathSecurityAuditor = PathSecurityAuditor()
    ): File? {
        if (validate(filename) is FilenameValidation.Invalid) return null
        val target = File(destinationDir.absoluteFile, filename)
        return when (pathAuditor.audit(target.absolutePath, destinationDir)) {
            is PathSecurityAuditor.PathAuditResult.Safe -> target
            is PathSecurityAuditor.PathAuditResult.Rejected -> null
        }
    }

    fun newTempFile(destinationDir: File): File =
        File(destinationDir.absoluteFile, "artifact-" + UUID.randomUUID() + ".tmp")

    fun moveVerifiedFile(src: File, dest: File): Boolean =
        runCatching {
            dest.parentFile?.mkdirs()
            if (src.renameTo(dest)) return true
            src.copyTo(dest, overwrite = true)
            src.delete()
            true
        }.getOrDefault(false)
}
