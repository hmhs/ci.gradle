/**
 * (C) Copyright IBM Corporation 2014, 2015.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.wasdev.wlp.gradle.plugins.tasks

import net.wasdev.wlp.ant.DeployTask
import net.wasdev.wlp.gradle.plugins.extensions.DeployExtension
import org.gradle.api.GradleException
import org.gradle.api.GradleScriptException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.War
import org.gradle.plugins.ear.Ear
import org.gradle.plugins.ear.EarPlugin

import static net.wasdev.wlp.gradle.plugins.Liberty.LIBERTY_DEPLOY_CONFIGURATION
import static net.wasdev.wlp.gradle.plugins.Liberty.TASK_CORE_EAR
import static net.wasdev.wlp.gradle.plugins.Liberty.TASK_CORE_EAR

/**
 * InstallAppsArchiveConfigTask
 * InstallAppsArchiveLibertyBlockTask
 * InstallAppsArchiveLocalTask
 * InstallAppsLooseConfigTask
 * InstallAppsLooseLibertyBlockTask
 * InstallAppsLooseLocalTask
 */
trait DeployBase {
    def initAntTask(Project project){

        project.ant.taskdef(name: 'deploy',
            classname: DeployTask.name,
            classpath: project.rootProject.buildscript.configurations.classpath.asPath)
    }
}

class DeployConfigTask extends AbstractInstallAppsTask implements DeployBase {
    @TaskAction
    void deploy() {
        initAntTask(project)

        // Deploys from the subproject configuration
        def deployConf = project.configurations.findByName(LIBERTY_DEPLOY_CONFIGURATION)

        def deployableProjects = []
        deployConf.allDependencies.each {
            println ("Deploy Conf: " + it)
            if (it instanceof DefaultProjectDependency) {
                Project dependencyProject = it.dependencyProject
                if ((dependencyProject.plugins.hasPlugin(WarPlugin)) || (dependencyProject.plugins.hasPlugin(EarPlugin))){
                    deployableProjects << dependencyProject
                } else {
                    throw new GradleException("Project ${it} set to deploy to Liberty without applying either a WAR or EAR plugin")
                }
            }
        }

        println("deployconfig projects: " + deployableProjects)

        for (Project proj in deployableProjects){
            println("inside deployableprojects")
            boolean skipWar = false

            for (Task targetTask in proj.tasks.withType(Ear)) {
                skipWar = true
                deployTask(targetTask)
            }

            if (!skipWar) {
                for (Task targetTask in proj.tasks.withType(War)) {
                    deployTask(targetTask)
                }
            }
        }
    }

    def deployTask(Task targetTask){
        if (targetTask != null){
            targetTask.outputs.files.each {
                def params = buildLibertyMap(project)
                params.put('file', it.absolutePath)
                println("DeployConfig parameters: " + params)
                project.ant.deploy(params)
            }
        }
    }
}

//class DeployConfigTask extends AbstractInstallAppsTask implements DeployBase {
//    @TaskAction
//    void deploy() {
//        initAntTask(project)
//
//        // Deploys from the subproject configuration
//        def deployConf = project.configurations.findByName(LIBERTY_DEPLOY_CONFIGURATION)
//
//        def deployableProjects = []
//        deployConf.allDependencies.each {
//            println ("Deploy Conf: " + it)
//            if (it instanceof DefaultProjectDependency) {
//                Project dependencyProject = it.dependencyProject
//                if ((dependencyProject.plugins.hasPlugin(WarPlugin)) || (dependencyProject.plugins.hasPlugin(EarPlugin))){
//                    deployableProjects << dependencyProject
//                }
//            }
//        }
//
//        println("deployconfig projects: " + deployableProjects)
//
//        for (Project proj in deployableProjects){
//            def archives = proj.configurations.getByName("archives")
//
//            println("deployconfig archives: " + archives)
//
////            def deployArtifacts = deployConf.incoming.resolutionResult.allDependencies as List
////            +        def artifacts = deployConf.resolvedConfiguration.resolvedArtifacts as List
////            +        artifacts.each {
////                +            def params = buildLibertyMap(project)
////                +            params.put('file', it.file.absolutePath)
////                +            project.ant.deploy(params)
////                +        }
//
//            def unused = archives.incoming.resolutionResult.allDependencies as List
//            def artifacts = deployConf.resolvedConfiguration.resolvedArtifacts as List
//            for (def archive in artifacts){
//                println("deployconfig archive: " + archive)
//
//                def params = buildLibertyMap(project)
//                params.put('file', archive.file.absolutePath)
//                println("DeployConfig parameters: " + params)
//            }
//        }
//    }
//}

class DeployLibertyBlockTask extends AbstractInstallAppsTask implements DeployBase {
    @TaskAction
    void deploy() {
        initAntTask(project)

        // deploys the list of deploy closures
        for (DeployExtension deployable :  server.deploys) {
            def params = buildLibertyMap(project)

            def fileToDeploy = deployable.file
            println ("Deploying file: ${fileToDeploy}")

            if (fileToDeploy != null) {
                params.put('file', fileToDeploy)
                project.ant.deploy(params)
            } else {
                println("2")
                def deployDir = deployable.dir
                def include = deployable.include
                def exclude = deployable.exclude

                if (deployDir != null) {
                    println("3")
                    project.ant.deploy(params) {
                        fileset(dir:deployDir, includes: include, excludes: exclude)
                    }
                }
            }
        }
    }
}

class DeployLocalTask extends AbstractInstallAppsTask implements DeployBase {

    @Input
    String getPackagingType() throws Exception{
        if (project.plugins.hasPlugin(WarPlugin) || !project.tasks.withType(War).isEmpty()) {
            return TASK_CORE_WAR
        }
        else if (project.plugins.hasPlugin(EarPlugin) || !project.tasks.withType(Ear).isEmpty()) {
            return TASK_CORE_EAR
        }
        else {
            throw new GradleException("Archive path not found. Supported formats are jar, war, and ear.")
        }
    }

    @TaskAction
    void deploy() {
        def params = buildLibertyMap(project)
        def doInstall = false

        // Deploys war or ear from current project
        switch (packagingType) {
            case TASK_CORE_WAR:
                def warFile = project.war.archivePath
                if (warFile.exists()) {
                    doInstall = true
                    params.put('file', warFile)
                }

                break
            case TASK_CORE_EAR:
                def earFile = project.ear.archivePath
                if (earFile.exists()) {
                    params.put('file', earFile)
                    doInstall = true
                }
                break

        }

        if (doInstall) {
            initAntTask(project)
            project.ant.deploy(params)
        }
    }
}
