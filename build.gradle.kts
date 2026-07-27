plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-histopia"
    group = "org.oncologylab"
    version = "0.3.29"
    description = "Project-driven Histopia registration and semantic atlas workflows"
    automaticModule = "org.oncologylab.qupath.extension.histopia"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
