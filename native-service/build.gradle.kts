import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

abstract class BuildRustServiceTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val cargoNdk: Property<String>

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        execOperations.exec {
            workingDir(rustDirectory.get().asFile)
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
    manifest.set(layout.projectDirectory.file("Cargo.toml"))
    outputDirectory.set(layout.buildDirectory.dir("rust/jniLibs"))
    cargoNdk.set(providers.gradleProperty("cargoNdk").orElse("cargo"))
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
