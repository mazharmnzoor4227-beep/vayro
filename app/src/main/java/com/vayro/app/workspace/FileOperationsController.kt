package com.vayro.app.workspace

import java.io.File

/**
 * Controller for safe, interactive workspace file tree operations (create, rename, delete, duplicate, move)
 * with directory traversal protection and atomic crash-safe writes.
 */
class FileOperationsController {

    sealed class FileOpError(message: String) : Exception(message) {
        class SecurityViolation(msg: String) : FileOpError(msg)
        class TargetAlreadyExists(msg: String) : FileOpError(msg)
        class SourceNotFound(msg: String) : FileOpError(msg)
        class InvalidPath(msg: String) : FileOpError(msg)
        class OperationFailed(msg: String) : FileOpError(msg)
    }

    /**
     * Resolves and verifies that [relativePath] stays strictly within [workspaceRoot].
     * Throws [FileOpError.SecurityViolation] or [FileOpError.InvalidPath] on illegal access.
     */
    fun resolveWithinWorkspace(workspaceRoot: File, relativePath: String): Result<File> = runCatching {
        val cleanRel = relativePath.trim().replace('\\', '/').trimStart('/')
        if (cleanRel.isBlank()) {
            throw FileOpError.InvalidPath("Relative path cannot be empty")
        }
        val canonicalRoot = workspaceRoot.canonicalFile
        val candidate = File(canonicalRoot, cleanRel).canonicalFile
        if (!candidate.toPath().startsWith(canonicalRoot.toPath())) {
            throw FileOpError.SecurityViolation("Path traversal attempt detected: $relativePath")
        }
        candidate
    }

    /**
     * Safely creates a new file at [relativePath] with optional [initialContent].
     */
    fun createFile(
        workspaceRoot: File,
        relativePath: String,
        initialContent: String = "",
    ): Result<File> = runCatching {
        val file = resolveWithinWorkspace(workspaceRoot, relativePath).getOrThrow()
        if (file.exists()) {
            throw FileOpError.TargetAlreadyExists("File or directory already exists: $relativePath")
        }
        val parent = file.parentFile ?: throw FileOpError.OperationFailed("Cannot determine parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw FileOpError.OperationFailed("Failed to create parent directories for: $relativePath")
        }
        val success = SafeFileOps.atomicWriteText(file, initialContent)
        if (!success) {
            throw FileOpError.OperationFailed("Failed to atomically write new file: $relativePath")
        }
        file
    }

    /**
     * Safely creates a new directory at [relativePath].
     */
    fun createDirectory(workspaceRoot: File, relativePath: String): Result<File> = runCatching {
        val dir = resolveWithinWorkspace(workspaceRoot, relativePath).getOrThrow()
        if (dir.exists()) {
            throw FileOpError.TargetAlreadyExists("Directory already exists: $relativePath")
        }
        if (!dir.mkdirs()) {
            throw FileOpError.OperationFailed("Failed to create directory: $relativePath")
        }
        dir
    }

    /**
     * Safely renames [oldRelativePath] to [newRelativePath].
     */
    fun renameEntry(
        workspaceRoot: File,
        oldRelativePath: String,
        newRelativePath: String,
    ): Result<File> = runCatching {
        val source = resolveWithinWorkspace(workspaceRoot, oldRelativePath).getOrThrow()
        if (!source.exists()) {
            throw FileOpError.SourceNotFound("Source file or folder not found: $oldRelativePath")
        }
        val destination = resolveWithinWorkspace(workspaceRoot, newRelativePath).getOrThrow()
        if (destination.exists()) {
            throw FileOpError.TargetAlreadyExists("Destination already exists: $newRelativePath")
        }
        val destParent = destination.parentFile
        if (destParent != null && !destParent.exists() && !destParent.mkdirs()) {
            throw FileOpError.OperationFailed("Failed to create parent folder for destination")
        }

        val renamed = source.renameTo(destination)
        if (!renamed) {
            // Fall back to copy and delete if across mount points
            if (source.isDirectory) {
                source.copyRecursively(destination, overwrite = false)
                source.deleteRecursively()
            } else {
                source.copyTo(destination, overwrite = false)
                source.delete()
            }
        }
        destination
    }

    /**
     * Safely deletes [relativePath]. Refuses to delete the workspace root itself.
     */
    fun deleteEntry(workspaceRoot: File, relativePath: String): Result<Boolean> = runCatching {
        val target = resolveWithinWorkspace(workspaceRoot, relativePath).getOrThrow()
        val canonicalRoot = workspaceRoot.canonicalFile
        if (target == canonicalRoot) {
            throw FileOpError.SecurityViolation("Cannot delete the workspace root directory")
        }
        if (!target.exists()) {
            throw FileOpError.SourceNotFound("File or directory not found: $relativePath")
        }
        if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
    }

    /**
     * Duplicates a file, e.g. `foo.kt` -> `foo_copy.kt` or `foo_copy_2.kt`.
     */
    fun duplicateEntry(workspaceRoot: File, relativePath: String): Result<File> = runCatching {
        val source = resolveWithinWorkspace(workspaceRoot, relativePath).getOrThrow()
        if (!source.isFile) {
            throw FileOpError.SourceNotFound("Source file not found or is a directory: $relativePath")
        }
        val parent = source.parentFile ?: throw FileOpError.OperationFailed("Cannot determine parent directory")
        val baseName = source.nameWithoutExtension
        val ext = source.extension.let { if (it.isNotBlank()) ".$it" else "" }

        var copyName = "${baseName}_copy$ext"
        var candidate = File(parent, copyName)
        var counter = 2
        while (candidate.exists()) {
            copyName = "${baseName}_copy_$counter$ext"
            candidate = File(parent, copyName)
            counter++
        }

        val content = source.readBytes()
        val written = SafeFileOps.atomicWriteBytes(candidate, content)
        if (!written) {
            throw FileOpError.OperationFailed("Failed to write duplicate file: ${candidate.name}")
        }
        candidate
    }

    /**
     * Moves [sourceRelativePath] into directory [targetDirectoryRelativePath].
     */
    fun moveEntry(
        workspaceRoot: File,
        sourceRelativePath: String,
        targetDirectoryRelativePath: String,
    ): Result<File> = runCatching {
        val source = resolveWithinWorkspace(workspaceRoot, sourceRelativePath).getOrThrow()
        if (!source.exists()) {
            throw FileOpError.SourceNotFound("Source entry not found: $sourceRelativePath")
        }
        val targetDir = if (targetDirectoryRelativePath.isBlank() || targetDirectoryRelativePath == ".") {
            workspaceRoot.canonicalFile
        } else {
            resolveWithinWorkspace(workspaceRoot, targetDirectoryRelativePath).getOrThrow()
        }
        if (!targetDir.isDirectory) {
            throw FileOpError.InvalidPath("Target is not a directory: $targetDirectoryRelativePath")
        }

        val destination = File(targetDir, source.name)
        if (destination.exists()) {
            throw FileOpError.TargetAlreadyExists("An item named ${source.name} already exists in ${targetDir.name}")
        }

        val moved = source.renameTo(destination)
        if (!moved) {
            if (source.isDirectory) {
                source.copyRecursively(destination, overwrite = false)
                source.deleteRecursively()
            } else {
                source.copyTo(destination, overwrite = false)
                source.delete()
            }
        }
        destination
    }

    /**
     * Scaffolds starter project files for a selected template (Phase 9 - App Factory).
     */
    fun scaffoldTemplate(workspaceRoot: File, template: ProjectTemplate): Result<Unit> = runCatching {
        if (template == ProjectTemplate.NONE) return@runCatching
        workspaceRoot.mkdirs()

        when (template) {
            ProjectTemplate.NONE -> {}
            ProjectTemplate.ANDROID_COMPOSE -> {
                val manifest = File(workspaceRoot, "app/src/main/AndroidManifest.xml")
                manifest.parentFile?.mkdirs()
                SafeFileOps.atomicWriteText(
                    manifest,
                    """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="My App"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".trimIndent()
                )
                val mainActivity = File(workspaceRoot, "app/src/main/java/com/example/app/MainActivity.kt")
                mainActivity.parentFile?.mkdirs()
                SafeFileOps.atomicWriteText(
                    mainActivity,
                    """package com.example.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Text("Hello from Vayro Android App!")
            }
        }
    }
}
""".trimIndent()
                )
            }
            ProjectTemplate.NODEJS_API -> {
                val pkg = File(workspaceRoot, "package.json")
                SafeFileOps.atomicWriteText(
                    pkg,
                    """{
  "name": "my-api",
  "version": "1.0.0",
  "description": "Scaffolded with Vayro App Factory",
  "main": "index.js",
  "scripts": {
    "start": "node index.js",
    "dev": "node --watch index.js"
  },
  "dependencies": {
    "express": "^4.19.2"
  }
}
""".trimIndent()
                )
                val server = File(workspaceRoot, "index.js")
                SafeFileOps.atomicWriteText(
                    server,
                    """const express = require('express');
const app = express();
const port = process.env.PORT || 3000;

app.use(express.json());

app.get('/', (req, res) => {
  res.json({ message: 'Hello from Vayro API!', timestamp: new Date() });
});

app.listen(port, () => {
  console.log(`API server listening on http://localhost:${'$'}{port}`);
});
""".trimIndent()
                )
            }
            ProjectTemplate.REACT_WEB -> {
                val pkg = File(workspaceRoot, "package.json")
                SafeFileOps.atomicWriteText(
                    pkg,
                    """{
  "name": "my-react-app",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@vitejs/plugin-react": "^4.3.1",
    "vite": "^5.3.4"
  }
}
""".trimIndent()
                )
                val indexHtml = File(workspaceRoot, "index.html")
                SafeFileOps.atomicWriteText(
                    indexHtml,
                    """<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Vayro React App</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.jsx"></script>
  </body>
</html>
""".trimIndent()
                )
                val mainJsx = File(workspaceRoot, "src/main.jsx")
                mainJsx.parentFile?.mkdirs()
                SafeFileOps.atomicWriteText(
                    mainJsx,
                    """import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.jsx';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
""".trimIndent()
                )
                val appJsx = File(workspaceRoot, "src/App.jsx")
                SafeFileOps.atomicWriteText(
                    appJsx,
                    """import React from 'react';

export default function App() {
  return (
    <div style={{ fontFamily: 'system-ui, sans-serif', padding: 32, textAlign: 'center' }}>
      <h1>⚡ React Web App</h1>
      <p>Scaffolded on Android with Vayro App Factory.</p>
    </div>
  );
}
""".trimIndent()
                )
            }
            ProjectTemplate.PYTHON_FASTAPI -> {
                val mainPy = File(workspaceRoot, "main.py")
                SafeFileOps.atomicWriteText(
                    mainPy,
                    """from fastapi import FastAPI

app = FastAPI(title="Vayro FastAPI")

@app.get("/")
def read_root():
    return {"message": "Hello from Python FastAPI on Android!", "status": "ok"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
""".trimIndent()
                )
                val reqs = File(workspaceRoot, "requirements.txt")
                SafeFileOps.atomicWriteText(
                    reqs,
                    """fastapi>=0.111.0
uvicorn>=0.30.0
pydantic>=2.7.0
""".trimIndent()
                )
            }
            ProjectTemplate.FLUTTER_STARTER -> {
                val pubspec = File(workspaceRoot, "pubspec.yaml")
                SafeFileOps.atomicWriteText(
                    pubspec,
                    """name: mobile_harness_flutter
description: A new Flutter project scaffolded by Vayro App Factory.
version: 1.0.0+1
environment:
  sdk: '>=3.0.0 <4.0.0'
dependencies:
  flutter:
    sdk: flutter
  cupertino_icons: ^1.0.6
dev_dependencies:
  flutter_test:
    sdk: flutter
flutter:
  uses-material-design: true
""".trimIndent()
                )
                val mainDart = File(workspaceRoot, "lib/main.dart")
                mainDart.parentFile?.mkdirs()
                SafeFileOps.atomicWriteText(
                    mainDart,
                    """import 'package:flutter/material.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Vayro Flutter',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: Scaffold(
        appBar: AppBar(title: const Text('Vayro Flutter')),
        body: const Center(
          child: Text('Scaffolded with Vayro App Factory!'),
        ),
      ),
    );
  }
}
""".trimIndent()
                )
            }
            ProjectTemplate.REACT_NATIVE -> {
                val pkg = File(workspaceRoot, "package.json")
                SafeFileOps.atomicWriteText(
                    pkg,
                    """{
  "name": "mobile-harness-react-native",
  "version": "1.0.0",
  "main": "node_modules/expo/AppEntry.js",
  "scripts": {
    "start": "expo start",
    "android": "expo start --android"
  },
  "dependencies": {
    "expo": "~51.0.0",
    "expo-status-bar": "~1.12.1",
    "react": "18.2.0",
    "react-native": "0.74.5"
  }
}
""".trimIndent()
                )
                val appJs = File(workspaceRoot, "App.js")
                SafeFileOps.atomicWriteText(
                    appJs,
                    """import { StatusBar } from 'expo-status-bar';
import { StyleSheet, Text, View } from 'react-native';

export default function App() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>Vayro React Native</Text>
      <Text>Scaffolded on Android with Vayro App Factory.</Text>
      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 8,
  },
});
""".trimIndent()
                )
                val appJson = File(workspaceRoot, "app.json")
                SafeFileOps.atomicWriteText(
                    appJson,
                    """{
  "expo": {
    "name": "MobileHarnessRN",
    "slug": "mobile-harness-rn",
    "version": "1.0.0",
    "orientation": "portrait"
  }
}
""".trimIndent()
                )
            }
        }
    }
}

enum class ProjectTemplate(val displayName: String, val description: String) {
    NONE("Blank", "Empty workspace"),
    ANDROID_COMPOSE("Android (Compose)", "Jetpack Compose + Material 3 app"),
    NODEJS_API("Node.js (API)", "Express REST API server"),
    REACT_WEB("React (Web)", "React + Vite web app"),
    PYTHON_FASTAPI("Python (FastAPI)", "FastAPI async REST server"),
    FLUTTER_STARTER("Flutter", "Flutter cross-platform mobile starter"),
    REACT_NATIVE("React Native (Expo)", "React Native mobile starter with Expo")
}

