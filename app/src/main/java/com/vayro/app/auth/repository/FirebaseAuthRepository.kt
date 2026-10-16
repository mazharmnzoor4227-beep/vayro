package com.vayro.app.auth.repository

import android.app.Activity
import android.content.Context
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.vayro.app.AppCrashLogger
import com.vayro.app.auth.model.AppUser
import com.vayro.app.auth.model.AuthProviderType
import com.vayro.app.auth.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FirebaseAuthRepository(
    private val context: Context,
) : AuthRepository {

    // Firebase can be unavailable when its bundled config is missing or does
    // not match this build (e.g. a placeholder google-services.json). Resolve
    // it defensively: a Firebase failure must degrade the app to signed-out
    // (guest) mode instead of crashing the process during first composition.
    private val authOrNull: FirebaseAuth? by lazy {
        runCatching { FirebaseAuth.getInstance() }
            .onFailure { AppCrashLogger.logThrowable("FirebaseAuth unavailable; continuing signed-out", it) }
            .getOrNull()
    }

    // Operation sites run inside runCatching and surface this as a normal
    // sign-in error; only the init path below must never throw.
    private val auth: FirebaseAuth
        get() = authOrNull
            ?: throw IllegalStateException("Sign-in is unavailable right now. Please continue as guest.")

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override val currentUser: AppUser?
        get() = _authState.value.currentUser

    init {
        val resolvedAuth = authOrNull
        if (resolvedAuth == null) {
            _authState.value = AuthState.Unauthenticated
        } else {
            resolvedAuth.addAuthStateListener { firebaseAuth ->
            val firebaseUser = firebaseAuth.currentUser
            if (firebaseUser == null) {
                _authState.value = AuthState.Unauthenticated
            } else {
                val appUser = mapFirebaseUser(firebaseUser)
                _authState.value = if (!appUser.emailVerified && appUser.provider == AuthProviderType.EMAIL) {
                    AuthState.EmailVerificationRequired(appUser)
                } else {
                    AuthState.Authenticated(appUser)
                }
            }
            }
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<AppUser> = withContext(Dispatchers.IO) {
        runCatching {
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: error("Sign in succeeded but user was null")
            mapFirebaseUser(user)
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun signUpWithEmail(name: String, email: String, password: String): Result<AppUser> = withContext(Dispatchers.IO) {
        runCatching {
            val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: error("Account creation succeeded but user was null")
            if (name.isNotBlank()) {
                val profileUpdate = UserProfileChangeRequest.Builder()
                    .setDisplayName(name.trim())
                    .build()
                runCatching { user.updateProfile(profileUpdate).await() }
            }
            // QA Medium: a failed verification send used to be swallowed,
            // stranding the user at "check your email" with no email ever
            // sent and no retry. The account exists at this point, so the
            // failure is reported with the resend path instead of a bare
            // success.
            runCatching { user.sendEmailVerification().await() }
                .onFailure { error ->
                    throw Exception(
                        "Account created, but the verification email could not be sent (${error.message ?: "unknown error"}). " +
                            "Use 'Resend verification email' in Settings to try again."
                    )
                }
            user.reload().await()
            mapFirebaseUser(user)
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun signInWithGoogle(idToken: String): Result<AppUser> = withContext(Dispatchers.IO) {
        runCatching {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user ?: error("Google sign in succeeded but user was null")
            mapFirebaseUser(user)
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun signInWithGitHub(activity: Activity): Result<AppUser> {
        return runCatching {
            val provider = OAuthProvider.newBuilder("github.com")
                .addCustomParameter("allow_signup", "true")
                .setScopes(listOf("user:email"))
                .build()
            // Must run on Main: starts the OAuth activity for result.
            val result = withContext(Dispatchers.Main) {
                auth.startActivityForSignInWithProvider(activity, provider).await()
            }
            val user = result.user ?: error("GitHub sign in succeeded but user was null")
            withContext(Dispatchers.IO) { mapFirebaseUser(user) }
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            auth.sendPasswordResetEmail(email.trim()).await()
            Unit
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun sendEmailVerification(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val user = auth.currentUser ?: error("No authenticated user")
            user.sendEmailVerification().await()
            Unit
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun reloadUser(): Result<AppUser?> = withContext(Dispatchers.IO) {
        runCatching {
            val user = auth.currentUser ?: return@runCatching null
            user.reload().await()
            val updated = mapFirebaseUser(user)
            withContext(Dispatchers.Main) {
                if (updated.emailVerified || updated.provider != AuthProviderType.EMAIL) {
                    _authState.value = AuthState.Authenticated(updated)
                } else {
                    _authState.value = AuthState.EmailVerificationRequired(updated)
                }
            }
            updated
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun updateDisplayName(displayName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val user = auth.currentUser ?: error("No authenticated user")
            val profileUpdate = UserProfileChangeRequest.Builder()
                .setDisplayName(displayName.trim())
                .build()
            user.updateProfile(profileUpdate).await()
            user.reload().await()
            val updated = mapFirebaseUser(user)
            withContext(Dispatchers.Main) {
                _authState.value = AuthState.Authenticated(updated)
            }
            Unit
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val user = auth.currentUser ?: error("No authenticated user")
            val email = user.email ?: error("No email associated with account")
            val credential = EmailAuthProvider.getCredential(email, currentPassword)
            user.reauthenticate(credential).await()
            user.updatePassword(newPassword).await()
            Unit
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun reauthenticate(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val user = auth.currentUser ?: error("No authenticated user")
            val email = user.email ?: error("No email associated with account")
            val credential = EmailAuthProvider.getCredential(email, password)
            user.reauthenticate(credential).await()
            Unit
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun deleteAccount(password: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val user = auth.currentUser ?: error("No authenticated user")
            val email = user.email
            if (!password.isNullOrBlank() && email != null) {
                val credential = EmailAuthProvider.getCredential(email, password)
                user.reauthenticate(credential).await()
            }
            user.delete().await()
            withContext(Dispatchers.Main) {
                _authState.value = AuthState.Unauthenticated
            }
        }.recoverCatching { throwable ->
            throw Exception(mapFirebaseError(throwable))
        }
    }

    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            auth.signOut()
            withContext(Dispatchers.Main) {
                _authState.value = AuthState.Unauthenticated
            }
        }
    }

    private fun mapFirebaseUser(user: FirebaseUser): AppUser {
        val providerId = user.providerData.firstOrNull { it.providerId != "firebase" }?.providerId
            ?: user.providerId
        return AppUser(
            uid = user.uid,
            email = user.email,
            displayName = user.displayName?.takeIf(String::isNotBlank),
            photoUrl = user.photoUrl?.toString(),
            emailVerified = user.isEmailVerified,
            provider = AuthProviderType.fromFirebaseProviderId(providerId),
            createdAt = user.metadata?.creationTimestamp,
            lastSignInAt = user.metadata?.lastSignInTimestamp,
        )
    }

    private fun mapFirebaseError(throwable: Throwable): String {
        AppCrashLogger.log("FirebaseAuth error: ${throwable.javaClass.simpleName} - ${throwable.message}")
        val errorCode = (throwable as? FirebaseAuthException)?.errorCode.orEmpty()
        val message = throwable.message.orEmpty()
        return when {
            errorCode == "ERROR_OPERATION_NOT_ALLOWED" ||
                errorCode == "OPERATION_NOT_ALLOWED" ||
                message.contains("operation is not allowed", ignoreCase = true) ||
                message.contains("provider is disabled", ignoreCase = true) ||
                message.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                "This sign-in method is not available right now. Please continue as guest or try again later."
            throwable is FirebaseAuthInvalidCredentialsException -> {
                if (message.contains("badly formatted", ignoreCase = true)) {
                    "The email address is badly formatted."
                } else if (message.contains("credential", ignoreCase = true) && (message.contains("Google", ignoreCase = true) || message.contains("token", ignoreCase = true))) {
                    "Google sign-in credential was rejected by Firebase. Ensure Google provider is enabled and your SHA-1 is added in Firebase Console."
                } else {
                    "Invalid email or password. Please verify your credentials."
                }
            }
            throwable is FirebaseAuthUserCollisionException -> "An account with this email address already exists. Try signing in instead."
            throwable is FirebaseAuthWeakPasswordException -> "Password is too weak. Please use at least 6 characters including numbers or symbols."
            throwable is FirebaseAuthInvalidUserException -> "Account not found or has been disabled in Firebase."
            throwable is FirebaseAuthRecentLoginRequiredException -> "For security, please sign in again before performing this sensitive operation."
            throwable is FirebaseNetworkException -> "Network error. Please check your internet connection and try again."
            else -> throwable.message?.takeIf(String::isNotBlank) ?: "An unexpected authentication error occurred."
        }
    }
}
