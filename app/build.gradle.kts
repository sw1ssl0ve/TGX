import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class ValidateTgxReleaseSigningTask @Inject constructor(
    private val providerFactory: ProviderFactory
) : DefaultTask() {
    @get:Internal
    abstract val rootProjectDirectory: DirectoryProperty

    @get:Internal
    abstract val moduleDirectory: DirectoryProperty

    @TaskAction
    fun validateSigningInputs() {
        fun releaseSecret(name: String): String? {
            val env = providerFactory.environmentVariable(name).orNull
            if (!env.isNullOrBlank()) return env

            val system = providerFactory.systemProperty(name).orNull
            if (!system.isNullOrBlank()) return system

            val gradle = providerFactory.gradleProperty(name).orNull
            if (!gradle.isNullOrBlank()) return gradle

            val localProperties = providerFactory.fileContents(
                moduleDirectory.get().file("local.properties")
            ).asText.orNull ?: return null
            val properties = Properties().apply { load(localProperties.reader()) }
            return properties.getProperty(name)?.takeIf { it.isNotBlank() }
        }

        val storeFileValue = releaseSecret("TGX_RELEASE_STORE_FILE")
        val storePasswordValue = releaseSecret("TGX_RELEASE_STORE_PASSWORD")
        val keyAliasValue = releaseSecret("TGX_RELEASE_KEY_ALIAS")
        val keyPasswordValue = releaseSecret("TGX_RELEASE_KEY_PASSWORD")

        val missing = mutableListOf<String>()
        if (storeFileValue.isNullOrBlank()) missing.add("TGX_RELEASE_STORE_FILE")
        if (storePasswordValue.isNullOrBlank()) missing.add("TGX_RELEASE_STORE_PASSWORD")
        if (keyAliasValue.isNullOrBlank()) missing.add("TGX_RELEASE_KEY_ALIAS")
        if (keyPasswordValue.isNullOrBlank()) missing.add("TGX_RELEASE_KEY_PASSWORD")

        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing configuration is incomplete. " +
                    "Missing or empty parameter(s): ${missing.joinToString(", ")}. " +
                    "All four parameters (TGX_RELEASE_STORE_FILE, TGX_RELEASE_STORE_PASSWORD, " +
                    "TGX_RELEASE_KEY_ALIAS, and TGX_RELEASE_KEY_PASSWORD) are required to build Release."
            )
        }

        val configuredFile = File(storeFileValue!!)
        val keystoreFile = if (configuredFile.isAbsolute) {
            configuredFile
        } else {
            rootProjectDirectory.get().asFile.resolve(configuredFile)
        }
        if (!keystoreFile.exists()) {
            throw GradleException(
                "Release keystore file not found at: ${keystoreFile.absolutePath}. " +
                    "Provide TGX_RELEASE_STORE_FILE pointing to an existing keystore file."
            )
        }
        if (!keystoreFile.isFile) {
            throw GradleException(
                "Release keystore path is not a file: ${keystoreFile.absolutePath}. " +
                    "TGX_RELEASE_STORE_FILE must point to a regular file, not a directory."
            )
        }
    }
}

val localPropertiesFile = layout.projectDirectory.file("local.properties")
val localPropertiesProvider = providers.fileContents(localPropertiesFile).asText

/**
 * Returns the value for the given secret name with the following priority:
 * 1. Environment variables (TGX_RELEASE_*)
 * 2. System properties (-DTGX_RELEASE_*)
 * 3. User Gradle properties (~/.gradle/gradle.properties)
 * 4. Project local.properties (fallback for backwards compatibility)
 */
fun releaseSecret(name: String): String? {
    val env = providers.environmentVariable(name).orNull
    if (!env.isNullOrBlank()) return env

    val sys = providers.systemProperty(name).orNull
    if (!sys.isNullOrBlank()) return sys

    val grad = providers.gradleProperty(name).orNull
    if (!grad.isNullOrBlank()) return grad

    val content = localPropertiesProvider.orNull ?: return null
    val props = Properties().apply { load(content.reader()) }
    return props.getProperty(name)?.takeIf { it.isNotBlank() }
}

val storeFileVar = releaseSecret("TGX_RELEASE_STORE_FILE")
val storePassVar = releaseSecret("TGX_RELEASE_STORE_PASSWORD")
val keyAliasVar = releaseSecret("TGX_RELEASE_KEY_ALIAS")
val keyPassVar = releaseSecret("TGX_RELEASE_KEY_PASSWORD")

val missingParams = mutableListOf<String>().apply {
    if (storeFileVar.isNullOrBlank()) add("TGX_RELEASE_STORE_FILE")
    if (storePassVar.isNullOrBlank()) add("TGX_RELEASE_STORE_PASSWORD")
    if (keyAliasVar.isNullOrBlank()) add("TGX_RELEASE_KEY_ALIAS")
    if (keyPassVar.isNullOrBlank()) add("TGX_RELEASE_KEY_PASSWORD")
}

val isValidCredentials = missingParams.isEmpty()
val keystoreFile = storeFileVar?.let { rootProject.file(it) }
val isKeystoreValidFile = keystoreFile != null && keystoreFile.exists() && keystoreFile.isFile

android {
    namespace = "io.github.tgx.android"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.tgx.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (isValidCredentials && isKeystoreValidFile) {
                storeFile = keystoreFile
                storePassword = storePassVar
                keyAlias = keyAliasVar
                keyPassword = keyPassVar
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig != null && releaseConfig.storeFile != null) {
                signingConfig = releaseConfig
            }
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
    }
}

/**
 * Defense-in-depth validation for Release signing gate. The task type keeps
 * validation out of packaging task actions so it remains Configuration Cache safe.
 */
val releaseSigningValidation = tasks.register<ValidateTgxReleaseSigningTask>(
    "validateTgxReleaseSigning"
) {
    rootProjectDirectory.set(rootProject.layout.projectDirectory)
    moduleDirectory.set(layout.projectDirectory)
}

tasks.matching { task ->
    val name = task.name
    name == "validateSigningRelease" ||
        name == "packageRelease" ||
        name == "packageReleaseBundle" ||
        name == "signReleaseBundle"
}.configureEach {
    dependsOn(releaseSigningValidation)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // TGX WebSocket transport
    implementation(libs.okhttp)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
