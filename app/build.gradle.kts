plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.wjz.nekocrypt"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.wjz.nekocrypt"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0-alpha2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        setProperty("archivesBaseName", "NekoCrypt-v$versionName")
    }

    buildTypes {
        release {
            isMinifyEnabled = true  //开启代码压缩、混淆、优化
            isShrinkResources = true    //删除代码中没有用到的资源
            // ✨ 指定混淆规则文件
            // proguard-android-optimize.txt 是安卓SDK自带的默认规则
            // proguard-rules.pro 是你项目里自己的规则文件，你可以添加不想被混淆的类
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // 用于 viewModel() 委托
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1") // 包含 ViewTreeLifecycleOwner
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1") // 包含 ViewTreeViewModelStoreOwner
    implementation("androidx.savedstate:savedstate-ktx:1.2.0") // 包含 ViewTreeSavedStateRegistryOwner

    implementation("com.alibaba.fastjson2:fastjson2:2.0.57") //json解析
    implementation(libs.androidx.compiler)//安装preferences datastore 插件

    implementation("androidx.navigation:navigation-compose:2.9.1") // 导航

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}