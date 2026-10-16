package com.vayro.app.auth.ui

import android.app.Application
import org.junit.Assert.assertNotNull
import org.junit.Test

class AuthViewModelConstructorTest {

    @Test
    fun authViewModelHasSingleArgumentApplicationConstructorForReflection() {
        val constructor = AuthViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull("AuthViewModel must have (Application) constructor for AndroidViewModelFactory", constructor)
    }
}
