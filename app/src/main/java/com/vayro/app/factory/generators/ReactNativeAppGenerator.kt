package com.vayro.app.factory.generators

import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.DataEntitySpec
import com.vayro.app.factory.model.ScreenSpec
import java.io.File

/**
 * Scaffolds production-ready React Native projects with TypeScript, Axios API service, and modular screens.
 */
object ReactNativeAppGenerator {

    fun generateProject(targetDir: File, blueprint: AppBlueprint): List<File> {
        val createdFiles = mutableListOf<File>()
        targetDir.mkdirs()

        // 1. Root configs
        createdFiles += writeFile(targetDir, "package.json", generatePackageJson(blueprint))
        createdFiles += writeFile(targetDir, "tsconfig.json", generateTsConfig())
        createdFiles += writeFile(targetDir, "App.tsx", generateAppTsx(blueprint))

        // 2. src structure
        val srcDir = File(targetDir, "src").apply { mkdirs() }
        val typesDir = File(srcDir, "types").apply { mkdirs() }
        val servicesDir = File(srcDir, "services").apply { mkdirs() }
        val screensDir = File(srcDir, "screens").apply { mkdirs() }

        // Types
        for (entity in blueprint.entities) {
            createdFiles += writeFile(typesDir, "${entity.name}.ts", generateTsType(entity))
        }

        // Services
        createdFiles += writeFile(servicesDir, "api.ts", generateAxiosService(blueprint))

        // Screens
        for (screen in blueprint.screens) {
            createdFiles += writeFile(screensDir, "${screen.name}.tsx", generateScreenTsx(screen))
        }

        return createdFiles
    }

    private fun writeFile(dir: File, name: String, content: String): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeText(content.trimIndent())
        return file
    }

    private fun generatePackageJson(blueprint: AppBlueprint): String = """
        {
          "name": "${blueprint.name.lowercase().replace(Regex("[^a-z0-9]"), "-")}",
          "version": "${blueprint.buildConfig.versionName}",
          "private": true,
          "scripts": {
            "android": "react-native run-android",
            "ios": "react-native run-ios",
            "start": "react-native start",
            "lint": "eslint ."
          },
          "dependencies": {
            "react": "18.2.0",
            "react-native": "0.74.2",
            "axios": "^1.7.2",
            "@react-navigation/native": "^6.1.17",
            "@react-navigation/bottom-tabs": "^6.5.20",
            "react-native-safe-area-context": "^4.10.1",
            "react-native-screens": "^3.31.1"
          },
          "devDependencies": {
            "@types/react": "^18.2.6",
            "typescript": "^5.0.4"
          }
        }
    """

    private fun generateTsConfig(): String = """
        {
          "compilerOptions": {
            "target": "esnext",
            "module": "commonjs",
            "lib": ["es2017"],
            "allowJs": true,
            "jsx": "react-native",
            "noEmit": true,
            "isolatedModules": true,
            "strict": true,
            "moduleResolution": "node",
            "resolveJsonModule": true,
            "esModuleInterop": true,
            "skipLibCheck": true
          }
        }
    """

    private fun generateTsType(entity: DataEntitySpec): String {
        val fields = entity.fields.joinToString("\n  ") { f ->
            val tsType = when (f.type) {
                "Int", "Long", "Double" -> "number"
                "Boolean" -> "boolean"
                else -> "string"
            }
            val opt = if (f.nullable) "?" else ""
            "${f.name}$opt: $tsType;"
        }
        return """
            export interface ${entity.name} {
              $fields
            }
        """
    }

    private fun generateAxiosService(blueprint: AppBlueprint): String {
        return """
            import axios from 'axios';

            export const apiClient = axios.create({
              baseURL: 'https://api.example.com',
              timeout: 10000,
              headers: {
                'Content-Type': 'application/json',
              },
            });
        """
    }

    private fun generateScreenTsx(screen: ScreenSpec): String {
        val items = screen.components.joinToString("\n        ") { comp ->
            """
            <View style={styles.card}>
              <Text style={styles.cardTitle}>$comp</Text>
              <Text style={styles.cardSubtitle}>Ready for business logic and API binding.</Text>
            </View>
            """.trimIndent()
        }

        return """
            import React from 'react';
            import { StyleSheet, Text, View, ScrollView } from 'react-native';

            export const ${screen.name}: React.FC = () => {
              return (
                <ScrollView contentContainerStyle={styles.container}>
                  <Text style={styles.title}>${screen.title}</Text>
                  <Text style={styles.description}>${screen.description}</Text>
                  $items
                </ScrollView>
              );
            };

            const styles = StyleSheet.create({
              container: {
                padding: 16,
                backgroundColor: '#f8f9fa',
              },
              title: {
                fontSize: 24,
                fontWeight: 'bold',
                color: '#1a1a1a',
                marginBottom: 4,
              },
              description: {
                fontSize: 14,
                color: '#6c757d',
                marginBottom: 16,
              },
              card: {
                backgroundColor: '#ffffff',
                borderRadius: 12,
                padding: 16,
                marginBottom: 12,
                shadowColor: '#000',
                shadowOpacity: 0.05,
                shadowRadius: 4,
                elevation: 2,
              },
              cardTitle: {
                fontSize: 16,
                fontWeight: '600',
                color: '#212529',
              },
              cardSubtitle: {
                fontSize: 12,
                color: '#adb5bd',
                marginTop: 4,
              },
            });
        """
    }

    private fun generateAppTsx(blueprint: AppBlueprint): String {
        val imports = blueprint.screens.joinToString("\n") { s ->
            "import { ${s.name} } from './src/screens/${s.name}';"
        }
        val firstScreen = blueprint.screens.firstOrNull()?.name ?: "HomeScreen"

        return """
            import React from 'react';
            import { SafeAreaView, StatusBar, StyleSheet } from 'react-native';
            $imports

            export default function App() {
              return (
                <SafeAreaView style={styles.root}>
                  <StatusBar barStyle="dark-content" />
                  <$firstScreen />
                </SafeAreaView>
              );
            }

            const styles = StyleSheet.create({
              root: {
                flex: 1,
                backgroundColor: '#ffffff',
              },
            });
        """
    }
}
