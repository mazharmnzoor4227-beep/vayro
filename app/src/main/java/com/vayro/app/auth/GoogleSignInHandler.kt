package com.vayro.app.auth

import android.content.Context

class GoogleSignInCancelledException : Exception("Google sign-in was cancelled by the user")

interface GoogleSignInHandler {
    val isAvailable: Boolean
    suspend fun requestGoogleIdToken(context: Context): Result<String>
}

class DefaultGoogleSignInHandler : GoogleSignInHandler {
    override val isAvailable: Boolean = false
    override suspend fun requestGoogleIdToken(context: Context): Result<String> {
        return Result.failure(UnsupportedOperationException("Google Sign-In is not supported in this offline build."))
    }
}

object GoogleSignInProvider {
    @Volatile
    var handler: GoogleSignInHandler = DefaultGoogleSignInHandler()
}
