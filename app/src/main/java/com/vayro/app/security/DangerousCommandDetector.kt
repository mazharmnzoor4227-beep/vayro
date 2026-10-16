package com.vayro.app.security

import java.util.Locale

enum class DangerSeverity {
    SAFE,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class DangerousCommandResult(
    val isDangerous: Boolean,
    val severity: DangerSeverity,
    val reason: String,
    val matchedPattern: String? = null
) {
    companion object {
        fun safe(): DangerousCommandResult = DangerousCommandResult(
            isDangerous = false,
            severity = DangerSeverity.SAFE,
            reason = "Command evaluated safe"
        )
    }
}

/**
 * Evaluates shell commands against known destructive patterns, disk formatters,
 * fork bombs, untrusted network execution pipes, and system tampering vectors.
 */
class DangerousCommandDetector {

    private data class Rule(
        val regex: Regex,
        val severity: DangerSeverity,
        val reason: String
    )

    private val rules = listOf(
        // Root / system directory mass deletion
        Rule(
            regex = Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+|-[a-zA-Z]*f[a-zA-Z]*r[a-zA-Z]*\s+|--recursive\s+--force\s+|--force\s+--recursive\s+)(/|/\*|~|\${'$'}HOME|/system|/data|/etc|/boot|/usr|/bin|/sbin|/dev)(\s+|\z)"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Destructive system directory deletion"
        ),
        Rule(
            regex = Regex("""\brm\s+.*--no-preserve-root.*"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Explicit root preservation bypass deletion"
        ),

        // Filesystem format & raw partition wipes
        Rule(
            regex = Regex("""\bmkfs(\.[a-zA-Z0-9]+)?\s+/dev/.*"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Filesystem formatting command"
        ),
        Rule(
            regex = Regex("""\bdd\s+.*of=/dev/(sd[a-z]|vd[a-z]|nvme[0-9]|block/|zero|urandom|null).*"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Direct raw device/partition overwrite"
        ),
        Rule(
            regex = Regex("""\b(fdisk|parted|sfdisk|sgdisk)\s+/dev/.*"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Direct partition table modification"
        ),

        // Fork bombs & resource exhaust attacks
        Rule(
            regex = Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Fork bomb resource exhaustion attack"
        ),
        Rule(
            regex = Regex("""perl\s+-e\s+['"].*fork\s+while\s+fork.*['"]"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Perl fork bomb detected"
        ),
        Rule(
            regex = Regex("""python[0-9.]*\s+-c\s+['"].*os\.fork\(\).*['"]"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Python fork bomb detected"
        ),

        // Destructive permissions modification on system roots
        Rule(
            regex = Regex("""\bchmod\s+(-R\s+)?(000|777)\s+(/|/etc|/bin|/sbin|/usr|/system|/data)(\s+|\z)"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Destructive system root permission wipe"
        ),

        // Unsafe remote curl/wget pipe to shell
        Rule(
            regex = Regex("""\b(curl|wget|fetch)\s+[^|]+\|\s*(sudo\s+)?(sh|bash|zsh|dash|ksh)(\s+|\z)"""),
            severity = DangerSeverity.HIGH,
            reason = "Piping unverified remote script directly into shell"
        ),

        // System password & auth configuration tampering
        Rule(
            regex = Regex("""(>|>>|tee)\s+(/etc/passwd|/etc/shadow|/etc/sudoers|/etc/hosts)"""),
            severity = DangerSeverity.HIGH,
            reason = "System credential and authentication file tampering"
        ),
        Rule(
            regex = Regex("""\brm\s+.*(/etc/passwd|/etc/shadow|/etc/sudoers)"""),
            severity = DangerSeverity.HIGH,
            reason = "System authentication file deletion"
        ),

        // Sensitive secret inspection / modification via raw shell
        Rule(
            regex = Regex("""\b(cat|grep|cp|mv|rm)\s+.*(\.env|\.git-credentials|id_rsa|id_ed25519|\.keystore)\b"""),
            severity = DangerSeverity.MEDIUM,
            reason = "Direct shell access to sensitive credentials or keys"
        ),

        // Interactive Reverse Shells & Socket Backdoors
        Rule(
            regex = Regex("""/dev/(tcp|udp)/[0-9a-zA-Z._-]+/\d+"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Interactive reverse shell via /dev/tcp or /dev/udp redirection"
        ),
        Rule(
            regex = Regex("""\b(nc|netcat|ncat)\s+.*(-e|-c)\s*(/bin/)?(sh|bash|zsh)"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Netcat reverse shell backdoor payload"
        ),
        Rule(
            regex = Regex("""\bsocat\s+.*(exec|system):.*(sh|bash)"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Socat interactive shell payload"
        ),
        Rule(
            regex = Regex("""python[0-9.]*\s+-c\s+['"].*socket.*(subprocess|os\.dup2|/bin/sh).*['"]"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Python socket reverse shell payload"
        ),

        // Cryptocurrency Miners & Pool Protocols
        Rule(
            regex = Regex("""\b(xmrig|minerd|cpuminer|ccminer|ethminer|cgminer)\b"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Cryptocurrency mining binary detected"
        ),
        Rule(
            regex = Regex("""stratum\+(tcp|ssl)://[0-9a-zA-Z._:-]+"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Stratum crypto mining pool protocol connection"
        ),

        // Destructive Device Wiping
        Rule(
            regex = Regex("""\bwipefs\s+(-a\s+)?/dev/.*"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Filesystem signature wipe on raw device"
        ),
        Rule(
            regex = Regex("""\bshred\s+.*(/dev/|/boot|/system)"""),
            severity = DangerSeverity.CRITICAL,
            reason = "Destructive disk or system shred command"
        ),

        // Detached Persistent Background Daemon
        Rule(
            regex = Regex("""\bnohup\s+.*&\s*disown"""),
            severity = DangerSeverity.HIGH,
            reason = "Detached persistent background daemon execution"
        )
    )

    /**
     * Evaluates the provided command string. Checks individual chained sub-commands
     * (split on ';', '&&', '||', and newlines) as well as the full string.
     */
    fun evaluate(command: String): DangerousCommandResult {
        if (command.isBlank()) return DangerousCommandResult.safe()

        val normalized = command.trim()

        // Check full command first
        for (rule in rules) {
            val match = rule.regex.find(normalized)
            if (match != null) {
                return DangerousCommandResult(
                    isDangerous = true,
                    severity = rule.severity,
                    reason = rule.reason,
                    matchedPattern = match.value
                )
            }
        }

        // Split sub-commands across pipeline and conjunction operators
        val subCommands = normalized.split(Regex("""[;&|\n]+"""))
        for (sub in subCommands) {
            val subTrimmed = sub.trim()
            if (subTrimmed.isEmpty()) continue
            for (rule in rules) {
                val match = rule.regex.find(subTrimmed)
                if (match != null) {
                    return DangerousCommandResult(
                        isDangerous = true,
                        severity = rule.severity,
                        reason = rule.reason,
                        matchedPattern = match.value
                    )
                }
            }
        }

        return DangerousCommandResult.safe()
    }
}
