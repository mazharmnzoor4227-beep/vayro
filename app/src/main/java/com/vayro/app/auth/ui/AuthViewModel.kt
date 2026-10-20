package com.vayro.app.auth.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayro.app.AppCrashLogger
import com.vayro.app.auth.AuthRepositoryProvider
import com.vayro.app.auth.model.AppUser
import com.vayro.app.auth.model.AuthState
import com.vayro.app.auth.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

data class AuthUiState(
    val isLoading: Boolean = false,
    val isGoogleLoading: Boolean = false,
    val isGitHubLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isSignUpMode: Boolean = false,
    val showForgotPasswordDialog: Boolean = false,
    val showEditNameDialog: Boolean = false,
    val showChangePasswordDialog: Boolean = false,
    val showDeleteAccountDialog: Boolean = false,
)

class AuthViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: AuthRepository = AuthRepositoryProvider.get(application),
) : AndroidViewModel(application) {

    val authState: StateFlow<AuthState> = repository.authState
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)

    val currentUser: AppUser?
        get() = repository.currentUser

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()


    fun setSignUpMode(signUp: Boolean) {
        _uiState.update { it.copy(isSignUpMode = signUp, errorMessage = null, successMessage = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun setErrorMessage(message: String?) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun dismissSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    fun showForgotPassword(show: Boolean) {
        _uiState.update { it.copy(showForgotPasswordDialog = show, errorMessage = null) }
    }

    fun showEditName(show: Boolean) {
        _uiState.update { it.copy(showEditNameDialog = show, errorMessage = null) }
    }

    fun showChangePassword(show: Boolean) {
        _uiState.update { it.copy(showChangePasswordDialog = show, errorMessage = null) }
    }

    fun showDeleteAccount(show: Boolean) {
        _uiState.update { it.copy(showDeleteAccountDialog = show, errorMessage = null) }
    }

    fun signInWithEmail(email: String, pass: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid email address.") }
            return
        }
        if (pass.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your password.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.signInWithEmail(trimmedEmail, pass)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, errorMessage = null) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Sign-in failed") }
                }
        }
    }

    fun signUpWithEmail(name: String, email: String, pass: String, confirmPass: String) {
        val trimmedName = name.trim()
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid email address.") }
            return
        }
        if (pass.length < 6) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 6 characters.") }
            return
        }
        if (pass != confirmPass) {
            _uiState.update { it.copy(errorMessage = "Passwords do not match.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.signUpWithEmail(trimmedName, trimmedEmail, pass)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = null,
                            successMessage = "Account created! A verification email has been sent.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Account creation failed") }
                }
        }
    }

    fun signInWithGoogleToken(idToken: String) {
        _uiState.update { it.copy(isGoogleLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.signInWithGoogle(idToken)
                .onSuccess {
                    _uiState.update { it.copy(isGoogleLoading = false, errorMessage = null) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isGoogleLoading = false, errorMessage = error.message ?: "Google sign-in failed") }
                }
        }
    }

    fun setGoogleLoading(loading: Boolean) {
        _uiState.update { it.copy(isGoogleLoading = loading) }
    }

    fun signInWithGitHub(activity: android.app.Activity) {
        _uiState.update { it.copy(isGitHubLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.signInWithGitHub(activity)
                .onSuccess {
                    _uiState.update { it.copy(isGitHubLoading = false, errorMessage = null) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isGitHubLoading = false, errorMessage = error.message ?: "GitHub sign-in failed") }
                }
        }
    }


    fun sendPasswordReset(email: String) {
        val trimmed = email.trim()
        if (trimmed.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid email address.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.sendPasswordResetEmail(trimmed)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showForgotPasswordDialog = false,
                            successMessage = "Password reset email sent to $trimmed.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not send reset email") }
                }
        }
    }

    fun resendEmailVerification() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.sendEmailVerification()
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = "Verification email resent! Please check your inbox.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not resend email") }
                }
        }
    }

    fun refreshVerificationStatus() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.reloadUser()
                .onSuccess { user ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = if (user?.emailVerified == true) "Email verified successfully!" else "Email not verified yet. Please check your inbox.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not refresh status") }
                }
        }
    }

    fun updateDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Name cannot be empty.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.updateDisplayName(trimmed)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showEditNameDialog = false,
                            successMessage = "Display name updated successfully.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not update name") }
                }
        }
    }

    fun changePassword(currentPass: String, newPass: String, confirmNewPass: String) {
        if (currentPass.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your current password.") }
            return
        }
        if (newPass.length < 6) {
            _uiState.update { it.copy(errorMessage = "New password must be at least 6 characters.") }
            return
        }
        if (newPass != confirmNewPass) {
            _uiState.update { it.copy(errorMessage = "New passwords do not match.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.changePassword(currentPass, newPass)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showChangePasswordDialog = false,
                            successMessage = "Password changed successfully.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not change password") }
                }
        }
    }

    fun deleteAccount(password: String?) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.deleteAccount(password)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showDeleteAccountDialog = false,
                            successMessage = "Account deleted successfully.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Account deletion failed") }
                }
        }
    }

    fun signOut() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            repository.signOut()
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}
