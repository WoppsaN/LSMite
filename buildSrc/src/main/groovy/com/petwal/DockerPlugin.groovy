package com.petwal

import org.gradle.api.Plugin
import org.gradle.api.Project

class DockerPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {

        project.tasks.register("dockerBuild") {

            dependsOn("jar")

            doLast {

                def imageName = "${project.name}:${project.version}"

                println "Building docker image ${imageName}"

                project.exec {
                    commandLine "docker", "build", "-t", imageName, "."
                }
            }
        }
    }
}