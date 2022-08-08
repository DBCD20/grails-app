package _Self.buildTypes

import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.buildSteps.script

object BaseK8sBuildDeployJib : Template({
    name = "base-k8s_Build-Deploy_Jib"

    publishArtifacts = PublishMode.SUCCESSFUL

    params {
        param("jib.container.user", "nobody")
        param("deploy.timezone", "")
        param("trivy.host", "https://trivy.webbfontaine.am")
        param("reverse.dep.*.git.branch.default", "")
        param("image.tag", "%application.version%-%git.branch.default%-%build.number%")
        param("deploy.java.opts", "")
        param("bitbucket.repo", "%application.name%")
        param("deploy.spring.cloud.config.label", "")
        param("dockerhub.url", "dockerhub.webbfontaine.com")
        param("deploy.spring.profiles.active", "")
        param("jib.container.jvmFlags", "-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom")
        param("build.args", "--stacktrace")
        param("git.branch.specification", "")
        param("application.version", "")
        param("deploy.spring.cloud.zipkin.enabled", "false")
        param("permitted.branches", """^devel|^release(\/.+)*|^master(\/.+)*""")
        param("jib.container.args", "--logging.config=file:/app/logback.xml")
        param("docker.image", "%dockerhub.url%/%project.name%/%application.name%:%image.tag%")
        param("git.branch.default.devops", "devel")
        param("jib.allowInsecureRegistries", "true")
        param("jib.container.creationTime", "%teamcity.agent.jvm.user.timezone%")
    }

    vcs {
        root(Generic)
        root(DevOps, "+:. =>  %bitbucket.repo.devops%")
    }
    steps {
        script {
            name = "Check version"
            id = "RUNNER_648"
            scriptContent = """
                #!/bin/bash
                
                set -e
                
                ./gradlew properties | grep '^version:' | sed 's/.*\s//' > version.txt
                cat version.txt
                while IFS= read -r line; do
                    if [[ ${'$'}line =~ .*"unspecified".* ]]; then
                        python3 /root/local/scripts/checkversion/check_version.py --path=./build.gradle
                    else
                        echo "unmatched"
                        break
                    fi
                done < version.txt
                echo "##teamcity[setParameter name='application.version' value='${'$'}(cat version.txt)']"
            """.trimIndent()
        }
        script {
            name = """Replace branch name from "/" to "-""""
            id = "RUNNER_616"
            scriptContent = """
                #!/bin/bash
                
                set -e
                
                echo "##teamcity[setParameter name='image.tag' value='${'$'}( tr "/" "-" <<< %image.tag% )']"
            """.trimIndent()
        }
        script {
            name = "Create Docker Image"
            id = "RUNNER_123"

            conditions {
                matches("git.branch.default", "%permitted.branches%")
            }
            scriptContent = """
                #!/bin/bash
                
                set -e
                
                source ${'$'}HOME/.sdkman/bin/sdkman-init.sh
                
                eval ${'$'}(teamcity-agent-sdk --type=java --sub=%jdk.vendor% --version=%jdk.version%)
                
                echo "******"
                java -version
                echo "******"
                
                ./gradlew jib \
                -Djib.allowInsecureRegistries=%jib.allowInsecureRegistries% \
                -Djib.from.image=%base.image% \
                -Djib.to.image=%docker.image% \
                -Djib.to.tags=%image.tag% \
                -Djib.container.creationTime="USE_CURRENT_TIMESTAMP" \
                -Djib.container.user=%jib.container.user% \
                -Djib.container.jvmFlags=%jib.container.jvmFlags% \
                -Djib.container.args=%jib.container.args% \
                -PbuildProfile=%build.profile% %build.args%
            """.trimIndent()
        }
        script {
            name = "Security scanning"
            id = "RUNNER_640"
            scriptContent = """
                #!/bin/bash
                
                set -e
                
                trivy client --remote %trivy.host% --ignore-unfixed --exit-code 0 --vuln-type library -o /tmp/trivy-report.html --severity HIGH,CRITICAL %docker.image%
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
        script {
            name = "K8S authentication"
            id = "RUNNER_617"
            scriptContent = """
                #!/bin/bash
                
                set -e
                
                kubectl config use-context %deploy.kubernetes.cluster%
            """.trimIndent()
        }
        script {
            name = "Deploy"
            id = "RUNNER_631"
            workingDir = "%bitbucket.repo.devops%"
            scriptContent = """
                #!/bin/bash
                
                ###Replace Variables function to replace variables in yaml file with teamcity parameters
                replace_variables () {
                    escaped_java_opts=${'$'}(sed -e 's/[&\\/]/\\&/g; s/${'$'}/\\/' -e '${'$'}s/\\${'$'}//' <<<"%deploy.java.opts%")
                    escaped_docker_image=${'$'}(sed -e 's/[&\\/]/\\&/g; s/${'$'}/\\/' -e '${'$'}s/\\${'$'}//' <<<"%docker.image%")
                    sed -i -e 's/${'$'}{SPRING_CLOUD_CONFIG_LABEL}/%deploy.spring.cloud.config.label%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{SPRING_PROFILES_ACTIVE}/%deploy.spring.profiles.active%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{SPRING_CLOUD_ZIPKIN_ENABLED}/%deploy.spring.cloud.zipkin.enabled%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{APP_NAME}/%application.name%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{ENV_NAME}/%deploy.env.name%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{TZ}/%deploy.timezone%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{DOMAIN}/%deploy.domain%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{NAMESPACE}/%deploy.kubernetes.namespace%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{MEMORY_LIMIT}/%deploy.limit.memory-mb%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{MEMORY_REQUEST}/%deploy.request.memory-mb%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{CPU_LIMIT}/%deploy.limit.cpu-milicores%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{CPU_REQUEST}/%deploy.request.cpu-milicores%/g' "${'$'}1"
                    sed -i -e 's/${'$'}{JAVA_OPTS}/'"${'$'}escaped_java_opts"'/g' "${'$'}1"
                    sed -i -e 's/${'$'}{DOCKER_IMAGE}/'"${'$'}escaped_docker_image"'/g' "${'$'}1"
                    }
                    
                ### Change directory to application's directory
                cd ${'$'}(find ./ -type d -name %application.name% | head -1)
                
                ### Check if deployment file for the app has been changed in last commit
                rollout=${'$'}(git diff --name-only ./ | grep "deployment.yaml")
                
                ### Call Replace Variables function for all files in the folder in yaml files with teamcity parameters
                for f in ${'$'}(find ${'$'}(pwd) -type f); do
                    echo "${'$'}f"
                    replace_variables "${'$'}f"
                done
                
                ### Check if there is config directory for application for specific namespace and create configmap from those configs
                if [[ -d ./%deploy.kubernetes.namespace%-configs ]]
                then
                    kubectl create configmap -n %deploy.kubernetes.namespace% %application.name% --from-file=./%deploy.kubernetes.namespace%-configs/ --dry-run=client -o yaml | kubectl apply -f -
                fi
                
                if [[ -d ./%deploy.kubernetes.namespace%-secrets/ ]]
                then
                    kubectl -n %deploy.kubernetes.namespace% apply -f ./%deploy.kubernetes.namespace%-secrets/
                fi
                
                ### Apply all Kubernetes manifest files in the folder
                kubectl apply -n %deploy.kubernetes.namespace% -f ./
                
                ### Rollout restart deployment
                if [[ -z ${'$'}rollout ]]
                then
                    kubectl -n %deploy.kubernetes.namespace% rollout restart deployment %deploy.env.name%-%application.name%
                fi
            """.trimIndent()
        }
        script {
            name = "Healthcheck"
            id = "RUNNER_633"
            scriptContent = """
                #!/bin/bash
                
                set -e
                
                kubectl rollout status --timeout=5m deployment/%deploy.env.name%-%application.name% -n %deploy.kubernetes.namespace%
            """.trimIndent()
        }
    }
    features {
        dockerSupport {
            id = "DockerSupport"
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_15,PROJECT_EXT_17"
            }
        }
    }
})
