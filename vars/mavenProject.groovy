// vars/mavenProject.groovy
def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
    //default to 'clean install'
    def mvnBuildCmd = config.mavenBuildCommand ?: 'clean install'
    //default to jdk 8
    def jdkVersion = config.jdk ?: 8
    def mavenVersion = config.maven ?: '3.3.3'
    echo "building with JDK ${jdkVersion}"
    def rebuildBuildImage = config.rebuildBuildImage ?: false
    properties([[$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '5', daysToKeepStr: '', numToKeepStr: '5']]])
    stage 'update protected branches'
    if(config.protectedBranches!=null && !config.protectedBranches.empty){
        //set up GitHub protected branches for specified branches
        def apiUrl = 'https://github.beescloud.com/api/v3'
        def credentialsId = '3ebff2f8-1013-42ff-a1e4-6d74e99f4ca1'
        githubProtectBranch(config.protectedBranches, apiUrl, credentialsId, config.org, config.repo)
    } else {
        echo 'no branches set to protect'
    }
    stage 'set up build image'
    node('docker-cloud') {
        def buildImage
        try {
            buildImage = docker.image("kmadel/${config.repo}-build").pull()
            echo "buildImage already built for ${config.repo}"
            if(rebuildBuildImage){
                echo "rebuild of buildImage ${config.repo}-build requested"
                error "rebuild of buildImage ${config.repo}-build requested"
            }
        } catch (e) {
            echo "buildImage needs to be built and pushed for ${config.repo}"
            def workspaceDir = pwd()
            checkout scm
            //using specific maven repo directory '/maven-repo' to cache dependencies for later builds
            sh "docker run --name maven-build -v ${workspaceDir}:${workspaceDir} -w ${workspaceDir} kmadel/maven:${mavenVersion}-jdk-${jdkVersion} mvn -Dmaven.repo.local=/maven-repo ${mvnBuildCmd}"
            //create a repo specific build image based on previous run
            sh "docker commit maven-build kmadel/${config.repo}-build"
            sh "docker rm -f maven-build"
            //sign in to registry
            withDockerRegistry(registry: [credentialsId: 'docker-registry-login']) { 
                //push repo specific image to Docker registry (DockerHub in this case)
                sh "docker push kmadel/${config.repo}-build"
            }
        }
    }
    stage 'build'
    // now build, based on the configuration provided
    node('docker-cloud') {
        try {
            checkout scm
            //build with repo specific build image
            docker.image("kmadel/${config.repo}-build").inside(){
                sh "mvn -Dmaven.repo.local=/maven-repo ${mvnBuildCmd}"
            }
            echo 'stashing target directory'
            stash name: "target-stash", includes: "target/*"'
            currentBuild.result = "success"
            hipchatSend color: 'GREEN', textFormat: true, message: "(super) Pipeline for ${config.org}/${config.repo} complete - Job Name: ${env.JOB_NAME} Build Number: ${env.BUILD_NUMBER} status: ${currentBuild.result} ${env.BUILD_URL}", room: config.hipChatRoom, server: 'cloudbees.hipchat.com', token: 'A6YX8LxNc4wuNiWUn6qHacfO1bBSGXQ6E1lELi1z', v2enabled: true
        } catch (e) {
            currentBuild.result = "failure"
            hipchatSend color: 'RED', textFormat: true, message: "(angry) Pipeline for ${config.org}/${config.repo} complete - Job Name: ${env.JOB_NAME} Build Number: ${env.BUILD_NUMBER} status: ${currentBuild.result} ${env.BUILD_URL}", room: config.hipChatRoom, server: 'cloudbees.hipchat.com', token: 'A6YX8LxNc4wuNiWUn6qHacfO1bBSGXQ6E1lELi1z', v2enabled: true
        }
    }
    if(env.BRANCH_NAME=="master"){
        stage name: 'Deploy to Prod', concurrency: 1
        echo "steps for deployment..."
    }
}
