plugins {
    alias(libs.plugins.harmony.jvm.library)
}

dependencies {
    // Android ships org.json in the platform; the Windows app bundles it. Not packaged from here,
    // so the APK never carries a second copy of platform classes.
    compileOnly("org.json:json:20240303")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
