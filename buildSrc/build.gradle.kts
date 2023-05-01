/*
 * Copyright 2016-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */

import org.gradle.kotlin.dsl.support.expectedKotlinDslPluginsVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("org.gradle.kotlin:gradle-kotlin-dsl-plugins:$expectedKotlinDslPluginsVersion") {
        because("allows applying the `kotlin-dsl` / `embedded-kotlin` plugins in buildSrc convention plugins")
    }
    implementation(libs.gradlePlugin.kotlin)
    implementation(libs.gradlePlugin.pluginPublishing)
    implementation(libs.gradlePlugin.bcv)
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        allWarningsAsErrors = true
    }
}
