package com.vayro.app.auth

import android.content.Context
import com.vayro.app.auth.repository.AuthRepository
import com.vayro.app.auth.repository.FirebaseAuthRepository

object AuthRepositoryProvider {
    @Volatile
    private var instance: AuthRepository? = null

    fun get(context: Context): AuthRepository {
        GoogleSignInProvider.handler = OnlineGoogleSignInHandler()
        return instance ?: synchronized(this) {
            instance ?: FirebaseAuthRepository(context.applicationContext).also { instance = it }
        }
    }
}
