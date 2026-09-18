// Top-level build file - Kotlin 2.x migration
plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
    // FIX: Hilt 2.60.1 требует AGP 9.0+, у тебя 8.8.2 -> используем 2.56.2 совместимую с AGP 8.8 + Kotlin 2.2
    id("com.google.dagger.hilt.android") version "2.56.2" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
}
