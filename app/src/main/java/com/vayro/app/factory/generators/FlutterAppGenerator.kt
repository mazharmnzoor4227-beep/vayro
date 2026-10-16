package com.vayro.app.factory.generators

import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.DataEntitySpec
import com.vayro.app.factory.model.ScreenSpec
import java.io.File

/**
 * Scaffolds production-ready Flutter cross-platform projects with Dart, Material 3, and Dio API services.
 */
object FlutterAppGenerator {

    fun generateProject(targetDir: File, blueprint: AppBlueprint): List<File> {
        val createdFiles = mutableListOf<File>()
        targetDir.mkdirs()

        // 1. pubspec.yaml
        createdFiles += writeFile(targetDir, "pubspec.yaml", generatePubspecYaml(blueprint))
        createdFiles += writeFile(targetDir, "analysis_options.yaml", generateAnalysisOptions())

        // 2. lib folder
        val libDir = File(targetDir, "lib").apply { mkdirs() }
        val modelsDir = File(libDir, "models").apply { mkdirs() }
        val servicesDir = File(libDir, "services").apply { mkdirs() }
        val screensDir = File(libDir, "screens").apply { mkdirs() }

        // Models
        for (entity in blueprint.entities) {
            createdFiles += writeFile(modelsDir, "${entity.name.lowercase()}.dart", generateDartModel(entity))
        }

        // Service
        createdFiles += writeFile(servicesDir, "api_service.dart", generateDioService(blueprint))

        // Screens
        for (screen in blueprint.screens) {
            createdFiles += writeFile(screensDir, "${screen.route.replace(Regex("[^a-zA-Z0-9]"), "_")}_screen.dart", generateDartScreen(screen))
        }

        // main.dart
        createdFiles += writeFile(libDir, "main.dart", generateMainDart(blueprint))

        return createdFiles
    }

    private fun writeFile(dir: File, name: String, content: String): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeText(content.trimIndent())
        return file
    }

    private fun generatePubspecYaml(blueprint: AppBlueprint): String = """
        name: ${blueprint.name.lowercase().replace(Regex("[^a-z0-9]"), "_")}
        description: "${blueprint.description}"
        publish_to: "none"
        version: ${blueprint.buildConfig.versionName}+${blueprint.buildConfig.versionCode}

        environment:
          sdk: ">=3.0.0 <4.0.0"

        dependencies:
          flutter:
            sdk: flutter
          dio: ^5.4.3
          shared_preferences: ^2.2.3
          provider: ^6.1.2
          cupertino_icons: ^1.0.6

        dev_dependencies:
          flutter_test:
            sdk: flutter
          flutter_lints: ^3.0.0

        flutter:
          uses-material-design: true
    """

    private fun generateAnalysisOptions(): String = """
        include: package:flutter_lints/flutter.yaml
        linter:
          rules:
            prefer_const_constructors: true
            avoid_print: false
    """

    private fun generateDartModel(entity: DataEntitySpec): String {
        val fields = entity.fields.joinToString("\n  ") { f ->
            val dartType = when (f.type) {
                "Int" -> "int"
                "Long" -> "int"
                "Double" -> "double"
                "Boolean" -> "bool"
                else -> "String"
            }
            "final $dartType ${f.name};"
        }

        val ctorParams = entity.fields.joinToString(", ") { "required this.${it.name}" }

        val fromJsonFields = entity.fields.joinToString(",\n      ") { f ->
            val dartType = when (f.type) {
                "Int" -> "int"
                "Long" -> "int"
                "Double" -> "double"
                "Boolean" -> "bool"
                else -> "String"
            }
            "${f.name}: json['${f.name}'] as $dartType"
        }

        val toJsonMap = entity.fields.joinToString(",\n      ") { "'${it.name}': ${it.name}" }

        return """
            class ${entity.name} {
              $fields

              ${entity.name}({$ctorParams});

              factory ${entity.name}.fromJson(Map<String, dynamic> json) {
                return ${entity.name}(
                  $fromJsonFields
                );
              }

              Map<String, dynamic> toJson() => {
                  $toJsonMap
              };
            }
        """
    }

    private fun generateDioService(blueprint: AppBlueprint): String {
        return """
            import 'package:dio/dio.dart';

            class ApiService {
              final Dio _dio = Dio(
                BaseOptions(
                  baseUrl: 'https://api.example.com',
                  connectTimeout: const Duration(seconds: 10),
                  receiveTimeout: const Duration(seconds: 10),
                ),
              );

              Future<Response> fetchResource(String path) async {
                try {
                  return await _dio.get(path);
                } catch (e) {
                  rethrow;
                }
              }

              Future<Response> postResource(String path, dynamic data) async {
                try {
                  return await _dio.post(path, data: data);
                } catch (e) {
                  rethrow;
                }
              }
            }
        """
    }

    private fun generateDartScreen(screen: ScreenSpec): String {
        val widgets = screen.components.joinToString("\n            ") { comp ->
            """
            Card(
              margin: const EdgeInsets.symmetric(vertical: 6.0),
              child: ListTile(
                title: Text('$comp', style: const TextStyle(fontWeight: FontWeight.bold)),
                subtitle: const Text('Configured component ready for user interaction.'),
                leading: const Icon(Icons.check_circle_outline),
              ),
            ),
            """.trimIndent()
        }

        val className = screen.name.replace(Regex("[^a-zA-Z0-9]"), "")

        return """
            import 'package:flutter/material.dart';

            class $className extends StatelessWidget {
              const $className({super.key});

              @override
              Widget build(BuildContext context) {
                return Scaffold(
                  appBar: AppBar(
                    title: Text('${screen.title}'),
                  ),
                  body: ListView(
                    padding: const EdgeInsets.all(16.0),
                    children: [
                      Text(
                        '${screen.description}',
                        style: Theme.of(context).textTheme.bodyLarge,
                      ),
                      const SizedBox(height: 16),
                      $widgets
                    ],
                  ),
                );
              }
            }
        """
    }

    private fun generateMainDart(blueprint: AppBlueprint): String {
        val firstScreen = blueprint.screens.firstOrNull()?.name?.replace(Regex("[^a-zA-Z0-9]"), "") ?: "HomeScreen"
        val imports = blueprint.screens.joinToString("\n") { s ->
            "import 'screens/${s.route.replace(Regex("[^a-zA-Z0-9]"), "_")}_screen.dart';"
        }

        return """
            import 'package:flutter/material.dart';
            $imports

            void main() {
              runApp(const PocketApp());
            }

            class PocketApp extends StatelessWidget {
              const PocketApp({super.key});

              @override
              Widget build(BuildContext context) {
                return MaterialApp(
                  title: '${blueprint.name}',
                  debugShowCheckedModeBanner: false,
                  theme: ThemeData(
                    colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
                    useMaterial3: true,
                  ),
                  home: const $firstScreen(),
                );
              }
            }
        """
    }
}
