plugins {
    id("com.android.application")
}

android {
    namespace = "com.k4811.launcher"
    // compileSdk só define quais APIs o compilador conhece; a compatibilidade vem de minSdk/targetSdk.
    compileSdk = 36

    defaultConfig {
        // applicationId: definido na tabela de packages do build.gradle.kts da raiz
        minSdk = 17     // Android 4.2, igual ao apps originais da NWD
        targetSdk = 19  // Android 4.4, igual ao apps originais da NWD
        versionCode = 20270016  // maior que o de todos os originais (o com.nwd.audioset está em 20260915)
        versionName = "2.0.16"

        // Rastreamento (CarInfo): servidor, token e nome do aparelho vêm do AMBIENTE na hora do build, nunca do repositório.
        //   CARINFO_URL     ex.: https://servidor.exemplo   (vazio = rastreamento parado)
        //   CARINFO_TOKEN   token do servidor               (vazio = nada é enviado)
        //   CARINFO_DEVICE  identificador no servidor       (vazio = gera modelo + sufixo aleatório)
        buildConfigField("String", "DEFAULT_URL", "\"" + (System.getenv("CARINFO_URL") ?: "") + "\"")
        buildConfigField("String", "DEFAULT_TOKEN", "\"" + (System.getenv("CARINFO_TOKEN") ?: "") + "\"")
        buildConfigField("String", "DEFAULT_DEVICE", "\"" + (System.getenv("CARINFO_DEVICE") ?: "") + "\"")
    }

    buildFeatures {
        aidl = false
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Assinatura: definida no build.gradle.kts da raiz (chave da NWD ou, sem ela, a de debug).
        }
    }

    lint {
        // App instalado direto na multimídia (fora da Play Store): target antigo é intencional.
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(project(":common"))
}
