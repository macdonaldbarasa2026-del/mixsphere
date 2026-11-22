plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mixsphere"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mixsphere"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments(
                    "-DMIXXX_ROOT=${project.projectDir.parentFile.parentFile.absolutePath}",
                    "-DCMAKE_PREFIX_PATH=${System.getenv("QT_ANDROID_CMAKE_PREFIX_PATH") ?: ""}",
                    "-DANDROID_STL=c++_shared"
                )
                abiFilters("arm64-v8a")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
