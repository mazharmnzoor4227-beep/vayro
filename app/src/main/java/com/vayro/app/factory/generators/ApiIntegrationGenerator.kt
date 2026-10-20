package com.vayro.app.factory.generators

import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.AppFramework
import java.io.File

/**
 * Generates production-ready, typed API clients for Kotlin (Retrofit), Flutter (Dio), or React Native (Axios).
 */
object ApiIntegrationGenerator {

    fun generateApiClientBundle(targetDir: File, blueprint: AppBlueprint): List<File> {
        val createdFiles = mutableListOf<File>()
        val apiDir = File(targetDir, "api_client").apply { mkdirs() }

        when (blueprint.framework) {
            AppFramework.ANDROID_COMPOSE -> {
                createdFiles += writeFile(apiDir, "ApiClient.kt", generateKotlinRetrofitClient(blueprint))
                createdFiles += writeFile(apiDir, "NetworkResult.kt", generateKotlinNetworkResult(blueprint))
            }
            AppFramework.FLUTTER -> {
                createdFiles += writeFile(apiDir, "api_client.dart", generateFlutterDioClient(blueprint))
            }
            AppFramework.REACT_NATIVE -> {
                createdFiles += writeFile(apiDir, "apiClient.ts", generateReactNativeAxiosClient(blueprint))
            }
            AppFramework.FULLSTACK_BACKEND -> {
                // Generate OpenAPI spec
                createdFiles += writeFile(apiDir, "openapi.json", generateOpenApiSpec(blueprint))
            }
        }

        return createdFiles
    }

    private fun generateKotlinRetrofitClient(blueprint: AppBlueprint): String {
        return """
            package ${blueprint.packageName}.data.remote

            import okhttp3.OkHttpClient
            import okhttp3.logging.HttpLoggingInterceptor
            import retrofit2.Retrofit
            import retrofit2.converter.gson.GsonConverterFactory
            import java.util.concurrent.TimeUnit

            object ApiClient {
                private const val BASE_URL = "https://api.example.com/"

                private val okHttpClient: OkHttpClient by lazy {
                    OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .addInterceptor(HttpLoggingInterceptor().apply {
                            // Audit L4: request/response bodies can carry
                            // user credentials, so BODY logging ships in
                            // DEBUG builds of the generated app only.
                            level = if (${blueprint.packageName}.BuildConfig.DEBUG) {
                                HttpLoggingInterceptor.Level.BODY
                            } else {
                                HttpLoggingInterceptor.Level.NONE
                            }
                        })
                        .build()
                }

                val retrofit: Retrofit by lazy {
                    Retrofit.Builder()
                        .baseUrl(BASE_URL)
                        .client(okHttpClient)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                }

                val service: ApiService by lazy {
                    retrofit.create(ApiService::class.java)
                }
            }
        """.trimIndent()
    }

    private fun generateKotlinNetworkResult(blueprint: AppBlueprint): String = """
        package ${blueprint.packageName}.data.remote

        sealed class NetworkResult<out T> {
            data class Success<out T>(val data: T) : NetworkResult<T>()
            data class Error(val code: Int, val message: String) : NetworkResult<Nothing>()
            data class Exception(val throwable: Throwable) : NetworkResult<Nothing>()
            object Loading : NetworkResult<Nothing>()
        }
    """.trimIndent()

    private fun generateFlutterDioClient(blueprint: AppBlueprint): String = """
        import 'package:dio/dio.dart';

        class MobileApiClient {
          static final MobileApiClient _instance = MobileApiClient._internal();
          factory MobileApiClient() => _instance;

          late final Dio dio;

          MobileApiClient._internal() {
            dio = Dio(
              BaseOptions(
                baseUrl: 'https://api.example.com',
                connectTimeout: const Duration(seconds: 20),
                receiveTimeout: const Duration(seconds: 20),
                headers: {
                  'Content-Type': 'application/json',
                  'Accept': 'application/json',
                },
              ),
            );

            dio.interceptors.add(
              LogInterceptor(requestBody: true, responseBody: true),
            );
          }
        }
    """.trimIndent()

    private fun generateReactNativeAxiosClient(blueprint: AppBlueprint): String = """
        import axios from 'axios';

        export const api = axios.create({
          baseURL: 'https://api.example.com',
          timeout: 20000,
          headers: {
            'Content-Type': 'application/json',
          },
        });

        api.interceptors.request.use((config) => {
          // Injected auth tokens or telemetry headers
          return config;
        });

        api.interceptors.response.use(
          (response) => response,
          (error) => {
            console.error('API Error:', error?.response?.data || error.message);
            return Promise.reject(error);
          }
        );
    """.trimIndent()

    private fun generateOpenApiSpec(blueprint: AppBlueprint): String {
        return """
            {
              "openapi": "3.0.0",
              "info": {
                "title": "${blueprint.name} API",
                "version": "${blueprint.buildConfig.versionName}",
                "description": "${blueprint.description}"
              },
              "paths": {
                ${blueprint.endpoints.joinToString(",\n    ") { ep ->
                    "\"${ep.path}\": { \"${ep.method.lowercase()}\": { \"summary\": \"${ep.summary}\" } }"
                }}
              }
            }
        """.trimIndent()
    }

    private fun writeFile(dir: File, name: String, content: String): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeText(content.trimIndent())
        return file
    }
}
