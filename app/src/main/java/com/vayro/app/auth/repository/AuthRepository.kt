package com.vayro.app.auth.repository

import android.app.Activity
import com.vayro.app.auth.model.AppUser
import com.vayro.app.auth.model.AuthState
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val authState: StateFlow<AuthState>
    val currentUser: AppUser?

    suspend fun signInWithEmail(email: String, password: String): Result<AppUser>
    suspend fun signUpWithEmail(name: String, email: String, password: String): Result<AppUser>
    suspend fun signInWithGoogle(idToken: String): Result<AppUser>
    suspend fun signInWithGitHub(activity: Activity): Result<AppUser>
    suspend fun sendPasswordResetEmail(email: String): Result<Unit>
    suspend fun sendEmailVerification(): Result<Unit>
    suspend fun reloadUser(): Result<AppUser?>
    suspend fun updateDisplayName(displayName: String): Result<Unit>
    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit>
    suspend fun reauthenticate(password: String): Result<Unit>
    suspend fun deleteAccount(password: String? = null): Result<Unit>
    suspend fun signOut(): Result<Unit>
}
