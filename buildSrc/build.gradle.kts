plugins { `java-library` }
repositories { mavenCentral() }
dependencies {
    implementation(gradleApi())
    implementation("org.ow2.asm:asm:9.9.1")
    testImplementation("junit:junit:4.13.2")
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
