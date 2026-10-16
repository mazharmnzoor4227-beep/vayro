package com.vayro.app.auth.repository

import com.vayro.app.auth.model.AppUser
import com.vayro.app.auth.model.AuthProviderType
import com.vayro.app.auth.model.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OfflineAuthRepository : AuthRepository {
    private val localUser = AppUser(
        uid = "offline-local-user",
        email = "developer@offline.local",
        displayName = "Local Developer",
        photoUrl = null,
        emailVerified = true,
        provider = AuthProviderType.GUEST,
        createdAt = System.currentTimeMillis(),
        lastSignInAt = System.currentTimeMillis(),
    )

    private val _authState = MutableStateFlow<AuthState>(AuthState.Authenticated(localUser))
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override val currentUser: AppUser?
        get() = _authState.value.currentUser

    override suspend fun signInWithEmail(email: String, password: String): Result<AppUser> {
        return Result.success(localUser)
    }

    override suspend fun signUpWithEmail(name: String, email: String, password: String): Result<AppUser> {
        return Result.success(localUser)
    }

    override suspend fun signInWithGoogle(idToken: String): Result<AppUser> {
        return Result.success(localUser)
    }

    override suspend fun signInWithGitHub(activity: android.app.Activity): Result<AppUser> {
        return Result.failure(Exception("GitHub sign-in is not available in offline mode"))
    }

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun sendEmailVerification(): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun reloadUser(): Result<AppUser?> {
        return Result.success(localUser)
    }

    override suspend fun updateDisplayName(displayName: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun reauthenticate(password: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun deleteAccount(password: String?): Result<Unit> {
        _authState.value = AuthState.Unauthenticated
        return Result.success(Unit)
    }

    override suspend fun signOut(): Result<Unit> {
        _authState.value = AuthState.Unauthenticated
        return Result.success(Unit)
    }
}
