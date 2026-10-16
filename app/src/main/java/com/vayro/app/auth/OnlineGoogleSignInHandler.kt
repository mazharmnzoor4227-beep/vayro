package com.vayro.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.vayro.app.AppCrashLogger

class OnlineGoogleSignInHandler : GoogleSignInHandler {
    override val isAvailable: Boolean = true

    override suspend fun requestGoogleIdToken(context: Context): Result<String> {
        return runCatching {
            val pkgResId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            val namespaceResId = if (pkgResId == 0) {
                context.resources.getIdentifier("default_web_client_id", "string", "com.vayro.app")
            } else 0
            val resId = if (pkgResId != 0) pkgResId else namespaceResId
            val serverClientId = if (resId != 0) {
                context.getString(resId)
            } else {
                "249079131159-kjeg6nr3unpfnbt3gn9pm28vv97cdb83.apps.googleusercontent.com"
            }

            val credentialManager = CredentialManager.create(context)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context = context, request = request)
            val googleIdToken = GoogleIdTokenCredential.createFrom(result.credential.data)
            googleIdToken.idToken
        }.recoverCatching { throwable ->
            if (throwable is GetCredentialCancellationException) {
                throw GoogleSignInCancelledException()
            }
            AppCrashLogger.log("GoogleSignIn failed: ${throwable.message}")
            val message = throwable.message.orEmpty()
            if (message.contains("10") || message.contains("Developer error", ignoreCase = true) || message.contains("DEVELOPER_ERROR", ignoreCase = true)) {
                throw Exception("Google Sign-In configuration error (Developer Error 10): App's SHA-1 fingerprint is missing in Firebase Console for package 'com.vayro.app' under project 'codeair-apps'. Add your SHA-1 fingerprint under Firebase Console → Project Settings → Android apps.")
            } else if (message.contains("16") || message.contains("Cannot find a matching credential", ignoreCase = true) || message.contains("No credential", ignoreCase = true)) {
                throw Exception("No Google account selected or credentials unavailable on this device.")
            }
            throw throwable
        }
    }
}
