import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

abstract class BuildRustServiceTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Internal
    abstract val rustDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustInputs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val cargoNdk: Property<String>

    @get:Input
    abstract val ndkVersion: Property<String>

    @TaskAction
    fun build() {
        val rustpushDirectory = rustDirectory.get().asFile.resolve("../rustpush")
        val legacyFairplay = rustpushDirectory.resolve("certs/legacy-fairplay")
        val fairplayDirectory = rustpushDirectory.resolve("certs/fairplay")
        val fairplayNames = listOf(
            "4056631661436364584235346952193",
            "4056631661436364584235346952194",
            "4056631661436364584235346952195",
            "4056631661436364584235346952196",
            "4056631661436364584235346952197",
            "4056631661436364584235346952198",
            "4056631661436364584235346952199",
            "4056631661436364584235346952200",
            "4056631661436364584235346952201",
            "4056631661436364584235346952208",
        )
        val legacyCertificate = legacyFairplay.resolve("fairplay.crt")
        val legacyKey = legacyFairplay.resolve("fairplay.pem")
        if (!legacyCertificate.isFile || !legacyKey.isFile) {
            throw GradleException("rustpush FairPlay fixtures are missing at $legacyFairplay")
        }
        fairplayDirectory.mkdirs()
        fairplayNames.forEach { name ->
            legacyCertificate.copyTo(fairplayDirectory.resolve("$name.crt"), overwrite = true)
            legacyKey.copyTo(fairplayDirectory.resolve("$name.pem"), overwrite = true)
        }


        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val sdkCandidates = mutableListOf<File>()
        listOf(System.getenv("ANDROID_SDK_ROOT"), System.getenv("ANDROID_HOME"))
            .filterNotNull()
            .mapTo(sdkCandidates) { File(it) }
        val localProperties = rustDirectory.get().asFile.resolve("../local.properties")
        if (localProperties.isFile) {
            localProperties.readLines()
                .firstOrNull { it.startsWith("sdk.dir=") }
                ?.substringAfter("=")
                ?.replace("\\:", ":")
                ?.let { sdkCandidates.add(File(it)) }
        }
        val sdkDirectory = sdkCandidates.firstOrNull { it.isDirectory }
            ?: throw GradleException("Android SDK not found; run scripts/bootstrap-dev-env-wizard.sh")
        val ndkDirectory = sdkDirectory.resolve("ndk/${ndkVersion.get()}")
        if (!ndkDirectory.isDirectory) {
            throw GradleException(
                "Android NDK ${ndkVersion.get()} not found at $ndkDirectory; " +
                    "run scripts/bootstrap-dev-env-wizard.sh"
            )
        }

        execOperations.exec {
            workingDir(rustDirectory.get().asFile)
            environment("ANDROID_SDK_ROOT", sdkDirectory.absolutePath)
            environment("ANDROID_NDK_HOME", ndkDirectory.absolutePath)
            environment("ANDROID_NDK_ROOT", ndkDirectory.absolutePath)
            commandLine(cargoNdk.get(), "ndk", "-t", "arm64-v8a", "-o", output.absolutePath, "build", "--release")
        }
        val library = output.resolve("arm64-v8a/librustpush_service.so")
        if (!library.isFile) {
            throw GradleException("cargo ndk completed without producing $library")
        }
    }
}

tasks.register<BuildRustServiceTask>("buildRustService") {
    group = "build"
    description = "Cross-compiles rustpush-service for Android arm64-v8a with cargo-ndk."
    rustDirectory.set(layout.projectDirectory)
    rustInputs.from(layout.projectDirectory.file("Cargo.toml"), layout.projectDirectory.file("Cargo.lock"), layout.projectDirectory.dir("src"), layout.projectDirectory.dir("../rustpush"))
    outputDirectory.set(layout.buildDirectory.dir("rust/jniLibs"))
    cargoNdk.set(providers.gradleProperty("cargoNdk").orElse("cargo"))
    ndkVersion.set("25.2.9519653")
}

android {
    namespace = "com.thelightphone.lightimessage.nativeservice"
    compileSdk = rootProject.ext["compileSdk"] as Int
    ndkVersion = "25.2.9519653"
    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        ndk { abiFilters += setOf("arm64-v8a") }
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rust/jniLibs"))
}

tasks.named("preBuild") {
    dependsOn("buildRustService")
}
