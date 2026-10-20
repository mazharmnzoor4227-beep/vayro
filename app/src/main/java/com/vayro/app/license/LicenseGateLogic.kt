package com.vayro.app.license

/**
 * Pure gate decisions for the license flow (v3.2.46, audit H1b + M2).
 *
 * Kept free of Android/network types so the whole decision matrix is
 * unit-testable; [LicenseGate] gathers the inputs (a fresh mode fetch,
 * the stored state, one license check) and applies the verdicts.
 */
object LicenseGateLogic {

    /** What the gate should do before any stored license is consulted. */
    enum class Step {
        /** Enforcement is off — the app opens for everyone. */
        OPEN_FREE,

        /**
         * This install has NEVER had a successful license-server contact
         * and the server is unreachable right now, so the enforcement
         * mode is unknown. The app must stay closed: a never-confirmed
         * install must not fall back to the stored default ("off").
         */
        NEEDS_SERVER_CONTACT,

        /** Enforcement is on and this phone holds no license token. */
        NEEDS_ACTIVATION,

        /** Enforcement is on and a stored token exists — verify it. */
        VERIFY_LICENSE,
    }

    /**
     * @param freshMode mode just fetched from the server, or null when the
     *   server could not be reached during this pass.
     * @param storedMode last mode stored on the phone.
     * @param modeConfirmed true once ANY successful server contact has
     *   resolved the mode on this install (a fetched mode, an activation,
     *   or a successful license check). Until then [storedMode] is only
     *   the factory default and proves nothing.
     */
    fun step(freshMode: String?, storedMode: String, modeConfirmed: Boolean, hasToken: Boolean): Step {
        val mode = when {
            freshMode != null -> freshMode
            modeConfirmed -> storedMode
            else -> return Step.NEEDS_SERVER_CONTACT
        }
        if (mode != "on") return Step.OPEN_FREE
        return if (hasToken) Step.VERIFY_LICENSE else Step.NEEDS_ACTIVATION
    }

    /** Verdict after the stored license has been checked with the server. */
    enum class PostCheck {
        /** Server says the license is active. */
        OPEN_ACTIVE,

        /** Server unreachable, but the offline grace window still covers us. */
        OPEN_GRACE,

        /** Server unreachable and no grace left — connection screen. */
        LOCKED_OFFLINE,

        /** Server says the license time is over. */
        LOCKED_EXPIRED,

        /** Dead verdict inside the short post-activation settle window. */
        LOCKED_SETTLE,

        /** Server says the license is gone (revoked/deleted/reset). */
        LOCKED_REVOKED,
    }

    /**
     * Order matters and mirrors the long-standing behaviour: an
     * unreachable server is judged by grace alone (a dead verdict is
     * never inferred from silence); "expired" outranks the settle window;
     * the settle window only softens revoked-style verdicts.
     */
    fun postCheck(
        reachable: Boolean,
        ok: Boolean,
        status: String?,
        graceOpen: Boolean,
        withinSettle: Boolean,
    ): PostCheck = when {
        !reachable -> if (graceOpen) PostCheck.OPEN_GRACE else PostCheck.LOCKED_OFFLINE
        ok && status == "active" -> PostCheck.OPEN_ACTIVE
        status == "expired" -> PostCheck.LOCKED_EXPIRED
        withinSettle -> PostCheck.LOCKED_SETTLE
        else -> PostCheck.LOCKED_REVOKED
    }

    /**
     * Milliseconds of offline grace left, anchored to BOTH clocks
     * (audit M2): the wall clock ([lastOkWallMs] → [nowWallMs]) and the
     * monotonic clock ([lastOkMonoMs] → [nowMonoMs], both
     * `SystemClock.elapsedRealtime()` readings). The remaining window is
     * the SMALLER of the two measures, so setting the phone clock back
     * can no longer stretch a timed license: the monotonic measure keeps
     * counting honestly.
     *
     * A [lastOkMonoMs] of 0 means no monotonic anchor was ever stored
     * (state written before v3.2.46); the wall measure alone decides
     * until the next successful check stores both anchors. After a
     * reboot the stored monotonic anchor is ahead of the fresh boot's
     * clock, which inflates the monotonic measure — the min() then
     * simply falls back to the wall measure, never to more grace than
     * the wall clock allows.
     *
     * Returns a negative value when no grace remains (including "never
     * confirmed", [lastOkWallMs] <= 0).
     */
    fun graceRemainingMs(
        lastOkWallMs: Long,
        lastOkMonoMs: Long,
        nowWallMs: Long,
        nowMonoMs: Long,
        graceMs: Long,
    ): Long {
        if (lastOkWallMs <= 0L) return -1L
        val wallRemaining = graceMs - (nowWallMs - lastOkWallMs)
        if (lastOkMonoMs <= 0L) return wallRemaining
        val monoRemaining = graceMs - (nowMonoMs - lastOkMonoMs)
        return minOf(wallRemaining, monoRemaining)
    }
}
