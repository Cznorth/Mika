plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.wangzan101.mika.routing"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.wangzan101.mika.routing"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.srcDir("protocol")
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation(libs.junit)
}
