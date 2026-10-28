plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.bionicdesktop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bionicdesktop"
        minSdk = 24
        // W^X 回避のキモ: targetSdk 28 だと filesDir 内の実行ファイルを exec() できる。
        // 29 以上にすると SELinux/W^X で filesDir からの実行が禁止される(Termux と同じ理由で 28)。
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        // 同梱する native バイナリ(ld.so, runner 等)の ABI。実機に合わせて絞る。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 同梱 native 実行ファイルは nativeLibraryDir 経由でも実行できるよう extractNativeLibs を有効化。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
