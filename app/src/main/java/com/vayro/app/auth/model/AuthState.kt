package com.vayro.app.auth.model

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val user: AppUser) : AuthState
    data class EmailVerificationRequired(val user: AppUser) : AuthState
    data class Error(val message: String, val user: AppUser? = null) : AuthState

    val currentUser: AppUser?
        get() = when (this) {
            is Authenticated -> user
            is EmailVerificationRequired -> user
            is Error -> user
            Loading, Unauthenticated -> null
        }

    val isAuthenticated: Boolean
        get() = this is Authenticated

    val isEmailVerificationRequired: Boolean
        get() = this is EmailVerificationRequired
}
