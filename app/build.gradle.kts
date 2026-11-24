import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.bighelp.droidhelp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bighelp.droidhelp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        buildConfig = true
    }

    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        checkReleaseBuilds = true
        checkDependencies = false
        warningsAsErrors = false
        htmlReport = true
        xmlReport = true
        textReport = true
    }

    composeOptions {
        // Required by task specification
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.ui.tooling.core)
    implementation(libs.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    
    debugImplementation(libs.ui.tooling.core)
    
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit.vintage.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("$projectDir/detekt-baseline.xml")
    buildUponDefaultConfig = true
    ignoreFailures = false
    autoCorrect = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(2))
    systemProperty("kotlinx.coroutines.test.default_timeout", "10s")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        stackTraceFilters("entry_point")
    }
}
