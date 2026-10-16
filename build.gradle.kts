plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
}

if (System.getenv("GITHUB_ACTIONS") == "true") {
    runCatching {
        val hasSudo = java.io.File("/usr/bin/sudo").exists() || java.io.File("/bin/sudo").exists()
        for (shPath in listOf("/usr/local/bin/sh", "/usr/bin/sh", "/bin/sh")) {
            val cmd = if (hasSudo) {
                arrayOf("sudo", "-n", "ln", "-sf", "/bin/bash", shPath)
            } else {
                arrayOf("ln", "-sf", "/bin/bash", shPath)
            }
            ProcessBuilder(*cmd).inheritIO().start().waitFor()
        }
        println("CI environment: configured shell symlinks (/usr/local/bin/sh, /usr/bin/sh, /bin/sh -> /bin/bash) for emulator runner compatibility")
    }
}


