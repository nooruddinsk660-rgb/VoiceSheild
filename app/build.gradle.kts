plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unstop.aivoicedetector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.unstop.aivoicedetector"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // FIX 1: Force consistent multidex — needed when iText7 + TFLite both pull
        // in large dependency trees that exceed the 64k method limit
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // FIX 2: Upgrade to Java 11 — itext7-core 7.2.5 requires Java 11+.
        // Java 1.8 causes the "Cannot mutate dependencies after resolved" error
        // because iText's resource processor plugin fires AFTER Gradle resolves the
        // compile classpath, which is illegal in Gradle 9.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            // FIX 3: iText7 + TFLite both bundle these files — exclude duplicates
            // to prevent resource merge conflicts at processDebugResources
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/versions/**"
            excludes += "/*.properties"
            excludes += "LICENSE"
            excludes += "NOTICE"
        }
    }

}

dependencies {
    // Explicitly add Kotlin standard library dependencies to prevent Android Gradle Plugin 8.2+ 
    // from dynamically mutating configuration constraints and crashing during processDebugResources.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    // Explicit Compose Versions (Bypassing Compose BOM dependency resolution mutation bug in AGP 8.2)
    implementation("androidx.compose.ui:ui:1.6.1")
    implementation("androidx.compose.ui:ui-graphics:1.6.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.1")
    implementation("androidx.compose.material3:material3:1.2.0")

    // Multidex support
    implementation("androidx.multidex:multidex:2.0.1")

    // Signal processing — TarsosDSP from local libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // FIX 5: Use full tensorflow-lite SDK strictly to expose Interpreter APIs like getOutputTensor
    // along with tensorflow-lite-task-audio
    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.13.0")
    implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4")

    // FIX 6: Replace itext7-core (9MB, Java 11 conflict trigger) with the
    // Android-safe iTextG (iText for Android/Google). Same API, no Java 11
    // resource processor plugin, dramatically smaller APK.
    // itext7-core is a desktop library — it was never designed for Android.
    implementation("com.itextpdf:itextg:5.5.10")

    // UI Charts
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.6.1")
    implementation("androidx.compose.ui:ui-tooling:1.6.1")
    implementation("androidx.compose.ui:ui-test-manifest:1.6.1")
}
