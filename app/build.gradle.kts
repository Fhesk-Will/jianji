import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---- 发布版本号：单一来源 gradle.properties；CI / 发布脚本可用 -P 覆盖 ----
val releaseVersionDate: String = providers.gradleProperty("releaseVersionDate").orNull
    ?: throw GradleException("缺少 releaseVersionDate：请在 gradle.properties 设置 YYYY.MM.DD 格式的日期")
if (!Regex("""\d{4}\.\d{2}\.\d{2}""").matches(releaseVersionDate)) {
    throw GradleException(
        "releaseVersionDate=\"$releaseVersionDate\" 非法：必须为 YYYY.MM.DD（月/日补零，如 2026.11.05）",
    )
}
val releaseVersionSeq: Int = providers.gradleProperty("releaseVersionSeq").orNull?.toIntOrNull()
    ?: throw GradleException("releaseVersionSeq 缺失或不是整数：请在 gradle.properties 设置 1..99")
if (releaseVersionSeq !in 1..99) {
    throw GradleException("releaseVersionSeq=$releaseVersionSeq 非法：必须为 1..99（同日多版依次递增）")
}
val derivedVersionName = "$releaseVersionDate.$releaseVersionSeq"
val derivedVersionCode = releaseVersionDate.replace(".", "").toInt() * 100 + releaseVersionSeq

// ---- 签名：release 必须提供完整签名材料，缺失直接失败，不再静默回退 debug ----
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
val keystoreProblem: String? = run {
    if (!keystorePropsFile.exists()) {
        return@run "未找到 ${keystorePropsFile.absolutePath}"
    }
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .filter { keystoreProps.getProperty(it).isNullOrBlank() }
    if (missing.isEmpty()) null else "keystore.properties 缺少字段：${missing.joinToString(", ")}"
}

android {
    namespace = "com.jianji.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jianji.app"
        minSdk = 26          // java.time 免脱糖
        targetSdk = 34
        versionCode = derivedVersionCode
        versionName = derivedVersionName
    }

    signingConfigs {
        if (keystoreProblem == null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8 代码裁剪
            isShrinkResources = true        // 资源裁剪
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 不再回退 debug：缺少签名材料时由下方 taskGraph 校验直接失败
            signingConfig = signingConfigs.findByName("release")
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
        buildConfig = true    // 设置页读取 BuildConfig.VERSION_NAME
    }

    lint {
        checkReleaseBuilds = false   // 个人项目：跳过 release lint，出包提速
    }
}

// APK 输出命名：同日多版不覆盖
androidComponents {
    onVariants { variant ->
        variant.outputs.forEachIndexed { index, output ->
            val suffix = if (variant.outputs.size > 1) "-${index + 1}" else ""
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName
                .set("jianji-v$derivedVersionName-$derivedVersionCode$suffix.apk")
        }
    }
}

// release 构建缺少签名材料时立即失败，避免产出 debug 签名的“正式包”
gradle.taskGraph.whenReady {
    val releaseTasks = allTasks.map { it.name }.filter {
        it == "assembleRelease" || it == "bundleRelease" || it == "installRelease" || it == "packageRelease"
    }
    if (releaseTasks.isNotEmpty() && keystoreProblem != null) {
        throw GradleException(
            "release 构建需要完整签名材料，但 $keystoreProblem。\n" +
                "请在仓库根目录放置 jianji-release.jks 并创建 keystore.properties" +
                "（storeFile / storePassword / keyAlias / keyPassword）；release 不再回退 debug 签名。",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
