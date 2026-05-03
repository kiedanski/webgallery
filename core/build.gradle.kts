plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    // XmlPullParser implementation; on Android the runtime provides one,
    // on the JVM (tests) we ship kxml2 to satisfy XmlPullParserFactory.newInstance().
    implementation(libs.kxml2)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
