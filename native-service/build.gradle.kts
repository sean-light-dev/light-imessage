// rustpush Android wrapper for the iMessage tool (M3 deliverable, ADR-005).
//
// This module cross-compiles the rustpush IPC service (see Cargo.toml) for
// Android arm64 via cargo-ndk and bundles the resulting .so for packaging
// into the tool APK. It exists as a Gradle module so the APK build stays
// hermetic; the Light SDK plugin gates NDK settings (ndkVersion, abiFilters,
// externalNativeBuild) to modules named in NATIVE_MODULES.
//
// NOTE: the actual cargo cross-compile + .so bundling is not wired yet —
// this module currently carries the Rust sources and the shared lockfile
// so `cargo build` works from here. See docs/imessage-migration.md.

plugins {
    alias(libs.plugins.android.library)
}

android {
    // Libraries require a namespace; this module ships no Kotlin, so any unique id works.
    namespace = "com.thelightphone.lightimessage.nativeservice"
    compileSdk = rootProject.ext["compileSdk"] as Int
    ndkVersion = "25.2.9519653"

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        ndk { abiFilters += setOf("arm64-v8a") }
    }
}
