package com.petwal

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class SpotlessConventionPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {

        project.pluginManager.apply("com.diffplug.spotless")

        project.tasks.named('build').configure { it.dependsOn('spotlessApply') }

        project.extensions.configure(SpotlessExtension) { SpotlessExtension spotless ->

            spotless.java {
                googleJavaFormat()
                target 'src/**/*.java'
            }

            spotless.format('misc') {
                target '*.gradle', '*.md', '.gitignore'
                trimTrailingWhitespace()
                indentWithSpaces()
                endWithNewline()
            }
        }
    }
}