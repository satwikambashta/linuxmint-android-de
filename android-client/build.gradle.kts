plugins {
    id("com.android.application") version "8.2.1" apply false
    kotlin("android") version "1.9.20" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
