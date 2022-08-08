package _Self.buildTypes

import jetbrains.buildServer.configs.kotlin.v2019_2.*

object BuildDockerImageGrails : BuildType({
    templates(AbsoluteId("DockerBuildGrailswV2"))
    name = "Build Docker Image Grails"

    params {
        text("env.BUILD_START_TIME", "", display = ParameterDisplay.HIDDEN, allowEmpty = true)
        param("appname", "urm-editor")
        param("giturl", "ssh://git@bitbucket.webbfontaine.com:7999/wvl/urm-editor.git")
        param("applicationcontext", "urm-editor")
        param("appsourcecodebranch", "devel")
        param("javaversion", "8.0.191-oracle")``
    }

    vcs {
        root(AbsoluteId("AppSourceCode"))
    }
    steps {
        script {
            name = "Set grails gradle java version"
            id = "RUNNER_203"
            scriptContent = """
                source ~/.bash_profile
                grails_version=
                
                if [ -f gradle.properties ]; then
                grails_version=${'$'}(grep grailsVersion gradle.properties | sed 's/grailsVersion=//')
                else
                grails_version=${'$'}(grep app.grails.version application.properties | sed 's/app.grails.version=//')
                fi
                
                set-grails.sh ${'$'}grails_version
                
                if [ -f gradle.properties ]; then
                gradle_version=${'$'}(grep gradleWrapperVersion gradle.properties | sed 's/gradleWrapperVersion=//')
                set-gradle.sh ${'$'}gradle_version
                chmod 755 ./gradlew
                fi
                
                set-java.sh %javaversion%
            """.trimIndent()
        }
        script {
            name = "Clean Arifacts and generate Artifact version"
            id = "RUNNER_204"
            scriptContent = """
                rm -rf build/libs/*
                rm -f *.war
                mkdir -p build/libs
                
                if [ -f "build.gradle" ] && grep -q 'version "' build.gradle; then
                version=${'$'}(grep 'version "' build.gradle | sed 's/version "//' | sed 's/\"//g' | sed 's/^ *//g')
                version=${'$'}(echo ${'$'}version | xargs)
                elif [ -f "build.gradle" ] && grep -q "version '" build.gradle; then
                version=${'$'}(grep "version '" build.gradle | sed "s/version '//" | sed "s/'//g")
                version=${'$'}(echo ${'$'}version | xargs)
                elif [ -f "application.properties" ] && grep -q 'app.version=' application.properties; then
                version=${'$'}(grep 'app.version=' application.properties | sed 's/app.version=//')
                version=${'$'}(echo ${'$'}version | xargs)
                elif [ -f "gradle.properties" ] && grep -q 'applicationCoreVersion=' gradle.properties; then
                coreVersion=${'$'}(grep 'applicationCoreVersion=' gradle.properties | sed 's/applicationCoreVersion=//')
                countryVersion=${'$'}(grep '%country%Version=' gradle.properties | sed 's/%country%Version=//')
                version=${'$'}coreVersion-%country%-${'$'}countryVersion
                else 
                version='unknown'
                fi
                
                echo "##teamcity[setParameter name='env.ARTIFACT_VERSION' value='${'$'}version']"
                echo "##teamcity[setParameter name='env.ARTIFACT_VERSION-BUILD_NUMBER' value='${'$'}version-%env.BUILD_NUMBER%']"
                echo "##teamcity[setParameter name='env.BUILD_START_TIME' value='${'$'}(date --iso-8601=seconds)Z']"
            """.trimIndent()
        }
        script {
            name = "Generate war file"
            id = "RUNNER_103"
            scriptContent = """
                source ~/.bash_profile
                
                #teamcity-agent-grails --args=%grails.args% --buildNumber=%system.build.number% war
                
                COUNTRY=${'$'}(echo %country% |sed 's/.*/\L&/')
                echo "COUTRY_CODE: ${'$'}COUNTRY"
                
                if [[ -f gradle.properties ]]; then
                    if [[ -d app ]]; then
                        echo "Creating war from ~/app"
                        cd app
                        gradle-current -DbuildNumber=%build.number% -Dcountry=%country% --stacktrace war
                        cd ..
                    else
                        echo "Creating war from ~/"
                        ./gradlew -DbuildNumber=%build.number% -Dcountry=%country% --stacktrace war
                    fi
                else
                    grails --stacktrace prod -DbuildNumber=%build.number% war build/libs/%applicationcontext%-%env.ARTIFACT_VERSION%.war
                fi
                
                
                if [ -f build/libs/*.war ]; then
                    echo %applicationcontext%-%env.ARTIFACT_VERSION%.war
                    mv build/libs/*.war %applicationcontext%-%env.ARTIFACT_VERSION%.war
                else
                    mv app/build/libs/*.war %applicationcontext%-%env.ARTIFACT_VERSION%.war
                fi
            """.trimIndent()
        }
        dockerCommand {
            name = "Build Docker Image"
            id = "RUNNER_104"
            commandType = build {
                source = content {
                    content = """
                        ARG IMAGE_NAME=%grailscontainerimagebasefullname%
                        ARG REGISTRY=dockerhub.wfgmb.com:5000
                        
                        FROM ${'$'}REGISTRY/${'$'}IMAGE_NAME
                        
                        ARG APP_NAME
                        ENV TOMCAT_VERSION ${'$'}TOMCAT_VERSION
                        ENV AUDIT_TOTAL_SIZE_CAP 3GB
                        
                        LABEL maintainer="DevOps <devops-ph@webbfontaine.com>" \
                        app.version="%env.ARTIFACT_VERSION%" \ 
                        base.image="%containerregistry%/%grailscontainerimagebasefullname%" \
                        maintainer="DevOps Team <devops-ph@webbfontaine.com>" \ 
                        org.label-schema.name="%product%-%country%/%appname%" \ 
                        org.label-schema.vcs-url="%giturl%" \ 
                        org.label-schema.vcs-ref="%build.vcs.number%" \ 
                        org.label-schema.build-date="%env.BUILD_START_TIME%" \ 
                        org.label-schema.version="%env.ARTIFACT_VERSION%-%system.build.number%"
                        
                        USER root
                        
                        COPY ./%applicationcontext%*.war ${'$'}CATALINA_HOME/webapps/%applicationcontext%.war
                        
                        EXPOSE 8080 8009
                        
                        USER ${'$'}TOMCAT_USER
                        
                        CMD ["/bin/sh", "-c", "catalina.sh run"]
                    """.trimIndent()
                }
                namesAndTags = "%containerregistry%/%product%-%country%/%appname%:%env.ARTIFACT_VERSION-BUILD_NUMBER%"
                commandArgs = "--pull"
            }
            param("dockerImage.platform", "linux")
        }
        script {
            name = "Security scanning"
            id = "RUNNER_640"
            scriptContent = """
                #!/bin/bash            
                set -e       
                trivy client --remote %trivy.host% --ignore-unfixed --exit-code 0 --vuln-type library -o /tmp/trivy-report.html --severity HIGH,CRITICAL %containerregistry%/%product%-%country%/%appname%:%env.ARTIFACT_VERSION%-%system.build.number%
            """.trimIndent()
        }
        script {
            name = "Copy artifact to minio bucket"
            id = "RUNNER_641"
            scriptContent = """
                #!/bin/bash
                
                set -e
                
                minioclient cp /tmp/trivy-report.html wfs3/security-scanning/%deploy.env.name%-%application.name%-%image.tag%-${'$'}(date "+%%Y-%%m-%%d")
            """.trimIndent()
        }
        // dockerCommand {
        //     name = "Push Docker Image to PH Registry"
        //     id = "RUNNER_105"
        //     commandType = push {
        //         namesAndTags = "%containerregistry%/%product%-%country%/%appname%:%env.ARTIFACT_VERSION%-%system.build.number%"
        //         removeImageAfterPush = false
        //     }
        // }
    }
    disableSettings("RUNNER_137", "RUNNER_138", "RUNNER_205")
})