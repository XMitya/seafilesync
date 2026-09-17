plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.xmitya.seafilesync"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.xmitya.seafilesync"
        minSdk = 34
        targetSdk = 37
        // Release builds get these from the git tag (see .github/workflows/release.yml); a plain
        // local build keeps the defaults.
        versionCode = providers.gradleProperty("versionCode").map { it.toInt() }.getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("1.0-dev")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key is never on disk in the repository. CI decodes it from a secret and points
    // these variables at it; without them a release build is simply left unsigned.
    val keystorePath = providers.environmentVariable("SIGNING_KEYSTORE").orNull
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("SIGNING_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME goes into the User-Agent the server records per device.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Export Room schemas so migrations can be diffed and tested.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    kspTest(libs.androidx.room.compiler)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Nothing imports Espresso -- the instrumented tests drive Compose -- but it is what pins
    // androidx.concurrent:concurrent-futures to the 1.1.0 the androidTest classpath is
    // constrained to. Drop it and androidx.test.ext:junit pulls 1.2.0 and resolution fails.
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlin.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

kover {
    reports {
        filters {
            excludes {
                // Generated: nothing here was written by hand, so covering it measures nothing.
                classes(
                    "*.BuildConfig",
                    "*.R",
                    "*.R$*",
                    "*_Impl",
                    "*_Impl$*",
                    "*ComposableSingletons*",
                    "*$\$serializer",
                )
                // Composables are exercised by the instrumented tests, which CI does not run,
                // and a JVM test cannot enter them at all. Counting them would just park a
                // permanent ~800 uncoverable lines in the denominator.
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }

        // A ratchet, not an aspiration. It sits just under where the suite actually is, so
        // coverage cannot quietly fall, and it is meant to be raised as tests land -- 70% is
        // the realistic target once MainViewModel and the sync engine are covered.
        //
        // Kover 0.9's rules take no filters of their own, so this cannot also hold the crypto
        // and protocol packages (already 80-88%) to a higher bar in the same block; that would
        // need a separate report variant.
        // Kover wires koverVerify into `check` on its own, so `gradlew check` and CI fail on a
        // regression rather than only reporting one. ktlint wires its check task up the same way.
        verify {
            rule("Overall line coverage") {
                minBound(45)
            }
        }
    }
}
