package com.vayro.app.factory.generators

import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.DataEntitySpec
import com.vayro.app.factory.model.ScreenSpec
import java.io.File

/**
 * Scaffolds production-grade native Android Jetpack Compose projects.
 */
object AndroidComposeGenerator {

    fun generateProject(targetDir: File, blueprint: AppBlueprint): List<File> {
        val createdFiles = mutableListOf<File>()
        targetDir.mkdirs()

        // 1. Root Gradle Files
        createdFiles += writeFile(targetDir, "settings.gradle.kts", generateSettingsGradle(blueprint))
        createdFiles += writeFile(targetDir, "build.gradle.kts", generateRootBuildGradle())
        createdFiles += writeFile(targetDir, "gradle.properties", generateGradleProperties())

        // 2. Gradle Wrapper
        val wrapperDir = File(targetDir, "gradle/wrapper").apply { mkdirs() }
        createdFiles += writeFile(wrapperDir, "gradle-wrapper.properties", generateGradleWrapperProperties())
        createdFiles += writeFile(targetDir, "gradlew", "#!/bin/sh\nexec gradle \"$@\"\n").apply { setExecutable(true) }

        // 3. App Module Build & Manifest
        val appDir = File(targetDir, "app").apply { mkdirs() }
        createdFiles += writeFile(appDir, "build.gradle.kts", generateAppBuildGradle(blueprint))

        val mainDir = File(appDir, "src/main").apply { mkdirs() }
        createdFiles += writeFile(mainDir, "AndroidManifest.xml", generateAndroidManifest(blueprint))

        // 4. Resources
        val resValuesDir = File(mainDir, "res/values").apply { mkdirs() }
        createdFiles += writeFile(resValuesDir, "strings.xml", generateStringsXml(blueprint))
        createdFiles += writeFile(resValuesDir, "colors.xml", generateColorsXml())
        createdFiles += writeFile(resValuesDir, "themes.xml", generateThemesXml())

        // 5. Kotlin Sources
        val packagePath = blueprint.packageName.replace('.', '/')
        val srcDir = File(mainDir, "java/$packagePath").apply { mkdirs() }
        val themeDir = File(srcDir, "ui/theme").apply { mkdirs() }
        val dataLocalDir = File(srcDir, "data/local").apply { mkdirs() }
        val dataRemoteDir = File(srcDir, "data/remote").apply { mkdirs() }
        val screensDir = File(srcDir, "ui/screens").apply { mkdirs() }

        // Theme files
        createdFiles += writeFile(themeDir, "Color.kt", generateColorKt(blueprint.packageName))
        createdFiles += writeFile(themeDir, "Type.kt", generateTypeKt(blueprint.packageName))
        createdFiles += writeFile(themeDir, "Theme.kt", generateThemeKt(blueprint.packageName))

        // Data / Room Database
        for (entity in blueprint.entities) {
            createdFiles += writeFile(dataLocalDir, "${entity.name}Entity.kt", generateEntityKt(blueprint.packageName, entity))
            createdFiles += writeFile(dataLocalDir, "${entity.name}Dao.kt", generateDaoKt(blueprint.packageName, entity))
        }
        createdFiles += writeFile(dataLocalDir, "AppDatabase.kt", generateDatabaseKt(blueprint.packageName, blueprint.entities))

        // Remote API
        createdFiles += writeFile(dataRemoteDir, "ApiService.kt", generateApiServiceKt(blueprint))

        // Screens & ViewModels
        for (screen in blueprint.screens) {
            createdFiles += writeFile(screensDir, "${screen.name}.kt", generateScreenKt(blueprint.packageName, screen, blueprint))
        }

        // MainActivity
        createdFiles += writeFile(srcDir, "MainActivity.kt", generateMainActivityKt(blueprint))

        return createdFiles
    }

    private fun writeFile(dir: File, name: String, content: String): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeText(content.trimIndent())
        return file
    }

    private fun generateSettingsGradle(blueprint: AppBlueprint): String = """
        pluginManagement {
            repositories {
                google()
                mavenCentral()
                gradlePluginPortal()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }
        rootProject.name = "${blueprint.name}"
        include(":app")
    """

    private fun generateRootBuildGradle(): String = """
        plugins {
            id("com.android.application") version "8.5.0" apply false
            id("org.jetbrains.kotlin.android") version "1.9.24" apply false
            id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
        }
    """

    private fun generateGradleProperties(): String = """
        org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
        android.useAndroidX=true
        android.nonTransitiveRClass=true
        kotlin.code.style=official
    """

    private fun generateGradleWrapperProperties(): String = """
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
        zipStoreBase=GRADLE_USER_HOME
        zipStorePath=wrapper/dists
    """

    private fun generateAppBuildGradle(blueprint: AppBlueprint): String = """
        plugins {
            id("com.android.application")
            id("org.jetbrains.kotlin.android")
            id("com.google.devtools.ksp")
        }

        android {
            namespace = "${blueprint.packageName}"
            compileSdk = ${blueprint.buildConfig.compileSdk}

            defaultConfig {
                applicationId = "${blueprint.buildConfig.applicationId}"
                minSdk = ${blueprint.buildConfig.minSdk}
                targetSdk = ${blueprint.buildConfig.targetSdk}
                versionCode = ${blueprint.buildConfig.versionCode}
                versionName = "${blueprint.buildConfig.versionName}"

                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                vectorDrawables {
                    useSupportLibrary = true
                }
            }

            buildTypes {
                release {
                    isMinifyEnabled = false
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro"
                    )
                }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            kotlinOptions {
                jvmTarget = "17"
            }
            buildFeatures {
                compose = true
            }
            composeOptions {
                kotlinCompilerExtensionVersion = "1.5.14"
            }
            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }
        }

        dependencies {
            implementation("androidx.core:core-ktx:1.13.1")
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
            implementation("androidx.activity:activity-compose:1.9.0")
            implementation(platform("androidx.compose:compose-bom:2024.06.00"))
            implementation("androidx.compose.ui:ui")
            implementation("androidx.compose.ui:ui-graphics")
            implementation("androidx.compose.ui:ui-tooling-preview")
            implementation("androidx.compose.material3:material3")
            implementation("androidx.compose.material:material-icons-extended")
            implementation("androidx.navigation:navigation-compose:2.7.7")
            implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

            // Room Database
            implementation("androidx.room:room-runtime:2.6.1")
            implementation("androidx.room:room-ktx:2.6.1")
            ksp("androidx.room:room-compiler:2.6.1")

            // Networking
            implementation("com.squareup.retrofit2:retrofit:2.11.0")
            implementation("com.squareup.retrofit2:converter-gson:2.11.0")
            implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

            testImplementation("junit:junit:4.13.2")
        }
    """

    private fun generateAndroidManifest(blueprint: AppBlueprint): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">

            <uses-permission android:name="android.permission.INTERNET" />
            <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

            <application
                android:allowBackup="true"
                android:icon="@android:drawable/sym_def_app_icon"
                android:label="@string/app_name"
                android:roundIcon="@android:drawable/sym_def_app_icon"
                android:supportsRtl="true"
                android:theme="@style/Theme.${blueprint.name.replace(" ", "")}">
                <activity
                    android:name=".MainActivity"
                    android:exported="true"
                    android:theme="@style/Theme.${blueprint.name.replace(" ", "")}">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>

        </manifest>
    """

    private fun generateStringsXml(blueprint: AppBlueprint): String = """
        <resources>
            <string name="app_name">${blueprint.name}</string>
        </resources>
    """

    private fun generateColorsXml(): String = """
        <resources>
            <color name="purple_200">#FFBB86FC</color>
            <color name="purple_500">#FF6200EE</color>
            <color name="purple_700">#FF3700B3</color>
            <color name="teal_200">#FF03DAC5</color>
            <color name="teal_700">#FF018786</color>
            <color name="black">#FF000000</color>
            <color name="white">#FFFFFFFF</color>
        </resources>
    """

    private fun generateThemesXml(): String = """
        <resources>
            <style name="Theme.App" parent="android:Theme.Material.Light.NoActionBar" />
        </resources>
    """

    private fun generateColorKt(pkg: String): String = """
        package $pkg.ui.theme

        import androidx.compose.ui.graphics.Color

        val Purple80 = Color(0xFFD0BCFF)
        val PurpleGrey80 = Color(0xFFCCC2DC)
        val Pink80 = Color(0xFFEFB8C8)

        val Purple40 = Color(0xFF6650a4)
        val PurpleGrey40 = Color(0xFF625b71)
        val Pink40 = Color(0xFF7D5260)
    """

    private fun generateTypeKt(pkg: String): String = """
        package $pkg.ui.theme

        import androidx.compose.material3.Typography
        import androidx.compose.ui.text.TextStyle
        import androidx.compose.ui.text.font.FontFamily
        import androidx.compose.ui.text.font.FontWeight
        import androidx.compose.ui.unit.sp

        val Typography = Typography(
            bodyLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp
            )
        )
    """

    private fun generateThemeKt(pkg: String): String = """
        package $pkg.ui.theme

        import androidx.compose.foundation.isSystemInDarkTheme
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.darkColorScheme
        import androidx.compose.material3.lightColorScheme
        import androidx.compose.runtime.Composable

        private val DarkColorScheme = darkColorScheme(
            primary = Purple80,
            secondary = PurpleGrey80,
            tertiary = Pink80
        )

        private val LightColorScheme = lightColorScheme(
            primary = Purple40,
            secondary = PurpleGrey40,
            tertiary = Pink40
        )

        @Composable
        fun AppTheme(
            darkTheme: Boolean = isSystemInDarkTheme(),
            content: @Composable () -> Unit
        ) {
            val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography,
                content = content
            )
        }
    """

    private fun generateEntityKt(pkg: String, entity: DataEntitySpec): String {
        val fields = entity.fields.joinToString(",\n        ") { f ->
            val pk = if (f.primaryKey) "@PrimaryKey\n        " else ""
            val type = when (f.type) {
                "Int" -> "Int"
                "Long" -> "Long"
                "Double" -> "Double"
                "Boolean" -> "Boolean"
                else -> "String"
            }
            val default = if (f.defaultValue != null) " = ${f.defaultValue}" else if (f.nullable) " = null" else ""
            "${pk}val ${f.name}: $type$default"
        }
        return """
            package $pkg.data.local

            import androidx.room.Entity
            import androidx.room.PrimaryKey

            @Entity(tableName = "${entity.tableName}")
            data class ${entity.name}Entity(
                $fields
            )
        """
    }

    private fun generateDaoKt(pkg: String, entity: DataEntitySpec): String = """
        package $pkg.data.local

        import androidx.room.Dao
        import androidx.room.Insert
        import androidx.room.OnConflictStrategy
        import androidx.room.Query
        import androidx.room.Update
        import androidx.room.Delete
        import kotlinx.coroutines.flow.Flow

        @Dao
        interface ${entity.name}Dao {
            @Query("SELECT * FROM ${entity.tableName}")
            fun getAll(): Flow<List<${entity.name}Entity>>

            @Insert(onConflict = OnConflictStrategy.REPLACE)
            suspend fun insert(item: ${entity.name}Entity)

            @Insert(onConflict = OnConflictStrategy.REPLACE)
            suspend fun insertAll(items: List<${entity.name}Entity>)

            @Update
            suspend fun update(item: ${entity.name}Entity)

            @Delete
            suspend fun delete(item: ${entity.name}Entity)
        }
    """

    private fun generateDatabaseKt(pkg: String, entities: List<DataEntitySpec>): String {
        val entityClasses = entities.joinToString(", ") { "${it.name}Entity::class" }
        val daoMethods = entities.joinToString("\n    ") { "abstract fun ${it.name.lowercase()}Dao(): ${it.name}Dao" }
        return """
            package $pkg.data.local

            import android.content.Context
            import androidx.room.Database
            import androidx.room.Room
            import androidx.room.RoomDatabase

            @Database(entities = [$entityClasses], version = 1, exportSchema = false)
            abstract class AppDatabase : RoomDatabase() {
                $daoMethods

                companion object {
                    @Volatile
                    private var INSTANCE: AppDatabase? = null

                    fun getDatabase(context: Context): AppDatabase {
                        return INSTANCE ?: synchronized(this) {
                            val instance = Room.databaseBuilder(
                                context.applicationContext,
                                AppDatabase::class.java,
                                "app_database"
                            ).fallbackToDestructiveMigration().build()
                            INSTANCE = instance
                            instance
                        }
                    }
                }
            }
        """
    }

    private fun generateApiServiceKt(blueprint: AppBlueprint): String {
        val methods = blueprint.endpoints.joinToString("\n    ") { ep ->
            val httpMethod = ep.method.uppercase()
            val cleanPath = ep.path.removePrefix("/api/")
            val funName = when (httpMethod) {
                "GET" -> "get${cleanPath.replace(Regex("[^a-zA-Z0-9]"), "").replaceFirstChar { it.uppercase() }}"
                "POST" -> "post${cleanPath.replace(Regex("[^a-zA-Z0-9]"), "").replaceFirstChar { it.uppercase() }}"
                "PUT" -> "update${cleanPath.replace(Regex("[^a-zA-Z0-9]"), "").replaceFirstChar { it.uppercase() }}"
                "DELETE" -> "delete${cleanPath.replace(Regex("[^a-zA-Z0-9]"), "").replaceFirstChar { it.uppercase() }}"
                else -> "call${cleanPath.replace(Regex("[^a-zA-Z0-9]"), "")}"
            }
            "// ${ep.summary}\n    @$httpMethod(\"${ep.path}\")\n    suspend fun $funName(): retrofit2.Response<Any>"
        }

        return """
            package ${blueprint.packageName}.data.remote

            import retrofit2.Retrofit
            import retrofit2.converter.gson.GsonConverterFactory
            import retrofit2.http.GET
            import retrofit2.http.POST
            import retrofit2.http.PUT
            import retrofit2.http.DELETE

            interface ApiService {
                $methods

                companion object {
                    private const val BASE_URL = "https://api.example.com/"

                    fun create(): ApiService {
                        return Retrofit.Builder()
                            .baseUrl(BASE_URL)
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()
                            .create(ApiService::class.java)
                    }
                }
            }
        """
    }

    private fun generateScreenKt(pkg: String, screen: ScreenSpec, blueprint: AppBlueprint): String {
        val componentsUi = screen.components.joinToString("\n            ") { comp ->
            """
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "$comp", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(text = "Interactive component ready for domain binding.", style = MaterialTheme.typography.bodySmall)
                }
            }
            """.trimIndent()
        }

        return """
            package $pkg.ui.screens

            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.PaddingValues
            import androidx.compose.foundation.layout.Spacer
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.foundation.layout.fillMaxWidth
            import androidx.compose.foundation.layout.height
            import androidx.compose.foundation.layout.padding
            import androidx.compose.foundation.lazy.LazyColumn
            import androidx.compose.material3.Card
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Scaffold
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp

            @Composable
            fun ${screen.name}(
                paddingValues: PaddingValues = PaddingValues(0.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "${screen.title}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            text = "${screen.description}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    item {
                        $componentsUi
                    }
                }
            }
        """
    }

    private fun generateMainActivityKt(blueprint: AppBlueprint): String {
        val navItems = blueprint.screens.take(4).mapIndexed { idx, s ->
            """
            NavigationBarItem(
                selected = currentRoute == "${s.route}",
                onClick = { currentRoute = "${s.route}" },
                icon = { Icon(Icons.Default.Star, contentDescription = "${s.title}") },
                label = { Text("${s.title}") }
            )
            """.trimIndent()
        }.joinToString("\n                        ")

        val screenWhenClauses = blueprint.screens.joinToString("\n                    ") { s ->
            "\"${s.route}\" -> ${s.name}(paddingValues = innerPadding)"
        }
        val firstRoute = blueprint.screens.firstOrNull()?.route ?: "home"

        return """
            package ${blueprint.packageName}

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material.icons.Icons
            import androidx.compose.material.icons.filled.Star
            import androidx.compose.material3.ExperimentalMaterial3Api
            import androidx.compose.material3.Icon
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.NavigationBar
            import androidx.compose.material3.NavigationBarItem
            import androidx.compose.material3.Scaffold
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.material3.TopAppBar
            import androidx.compose.material3.TopAppBarDefaults
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue
            import androidx.compose.ui.Modifier
            import ${blueprint.packageName}.ui.screens.*
            import ${blueprint.packageName}.ui.theme.AppTheme

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        AppTheme {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                MainAppNavigation()
                            }
                        }
                    }
                }
            }

            @OptIn(ExperimentalMaterial3Api::class)
            @Composable
            fun MainAppNavigation() {
                var currentRoute by remember { mutableStateOf("$firstRoute") }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("${blueprint.name}") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            $navItems
                        }
                    }
                ) { innerPadding ->
                    when (currentRoute) {
                        $screenWhenClauses
                        else -> ${blueprint.screens.first().name}(paddingValues = innerPadding)
                    }
                }
            }
        """
    }
}
