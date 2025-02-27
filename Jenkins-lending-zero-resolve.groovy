def tags=[:]
tags["file-type"]="jar"

def ex(value, message){
    currentBuild.result = 'ABORTED'
    error(message + '\nValue passed: ' + value)
}

def getBuildVersion(serviceName, branch) {
    def bucket = "paybyte-artifacts"
    def prefix = "${serviceName}/paybyte-${serviceName}-"
    def currentYearMonth = new Date().format("yyyy.MM")
    
    // Get the next build version
    return getNextBuildVersion(serviceName, branch, bucket, prefix, currentYearMonth)
}

def getNextBuildVersion(serviceName, branch, bucket, prefix, currentYearMonth) {
    // Fetch all JAR files for the current month
    def latestBuild = sh(
        script: """aws s3api list-objects-v2 --bucket ${bucket} --prefix "${serviceName}/" --query 'Contents[].Key' --output json | 
        jq -r '.[] | select(test("${prefix}${currentYearMonth}\\\\.\\\\d{2}(-SNAPSHOT)?\\\\.jar"))' | 
        sort -V | 
        tail -n 1""",
        returnStdout: true
    ).trim()

    if (latestBuild) {
        def match = latestBuild =~ /${prefix}${currentYearMonth}\.(\d{2})/
        if (match) {
            def lastSerial = match[0][1].toInteger()
            def newSerial = String.format("%02d", lastSerial + 1)
        }
    }

    def buildVersion = "${currentYearMonth}.${newSerial}"

    // Append -SNAPSHOT for non-master/main branches
    if (branch != "main" && branch != "master") {
        buildVersion += "-SNAPSHOT"
    }

    println "Generated build version: ${buildVersion}"
    return buildVersion
}

pipeline {
    agent {
        label "workers"
    }
    environment {
        SQ_TOKEN_FILE = "sonarqube_token.txt"
    }
    parameters {
        choice choices: ['customer-service', 'merchant-service', 'notification-service', 'order-service', 'product-service', 'transaction-service', 'user-service', 'utility-service'], description: 'Application name', name: 'service_name'
        string description: 'Branch name', name: 'branch', trim: true
        booleanParam description: 'Require SonarQube code analysis?', defaultValue: false, name: 'sq_code_analysis_reqd'
    }
    stages {
        stage('Fetch Latest Build Version') {
            steps {
                script {
                   env.BUILD_VERSION = getBuildVersion(params.service_name, params.branch)
                }
            }
        }
        stage('Pre-Flight checks for Parameters') {
            steps {
                script {
                    if (!env.BUILD_VERSION?.trim()) {
                        error("Build version could not be determined. Exiting!")
                    }
                    def isMatched = env.BUILD_VERSION ==~ /^\d{4}\.\d{2}\.\d{2}(-SNAPSHOT)?$/
                    if (!isMatched) {
                        error("Build version does not match YYYY.MM.SN or YYYY.MM.SN-SNAPSHOT pattern. Exiting!")
                    }
                    println "Pre-Flight checks - Complete!"
                }
            }
        }
        stage('Java and Maven version') {
            steps {
                sh '''echo "### Java version ###"; java -version; echo "### Maven version ###"; mvn -v'''
            }
        }
        stage('Clone') {
            steps {
                echo "Pulling from paybyte-${service_name} repo ${branch} branch.."
                git branch: "${branch}", changelog: false, credentialsId: 'bitbucket-credentials', poll: false, url: "https://prasun-paybyte@bitbucket.org/paybyte/paybyte-${service_name}.git"
            }
        }
        stage('Update Release version') {
            steps {
                sh '''sed -i -E "s#(<version>).*SNAPSHOT.*(</version>)#\\1${BUILD_VERSION}\\2#" pom.xml'''
            }
        }
        stage('Maven clean package') {
            steps {
                sh 'mvn -B -DskipTests clean package'
            }
        }
        stage('Generate SQ token') {
            steps {
                withCredentials([usernameColonPassword(credentialsId: 'sonarqube-credentials', variable: 'USERPASS')]) {
                    sh 'curl -X POST -H "Content-Type: application/x-www-form-urlencoded" -d "name=jenkins" -u "$USERPASS" http://sonarqube.paybyte.company/api/user_tokens/generate | jq -r .token > "${SQ_TOKEN_FILE}"'
                }
            }
            when { equals expected: true, actual: params.sq_code_analysis_reqd }
        }
        stage('Code Analysis') {
            steps {
                sh ''' token=$(cat "\${SQ_TOKEN_FILE}") && \
                sonar-scanner -Dsonar.host.url="http://sonarqube.paybyte.company/" \
                -Dsonar.login="${token}" \
                -Dsonar.projectName="${service_name}" \
                -Dsonar.java.binaries=. \
                -Dsonar.projectKey="${service_name}" \
                -Dsonar.verbose=true \
                -Dsonar.qualitygate.wait=true \
                -Dsonar.qualitygate.timeout=600
                '''
            }
            when { equals expected: true, actual: params.sq_code_analysis_reqd }
        }
        stage('Revoke SQ token') {
            steps {
                withCredentials([usernameColonPassword(credentialsId: 'sonarqube-credentials', variable: 'USERPASS')]) {
                    sh 'curl -X POST -H "Content-Type: application/x-www-form-urlencoded" -d "name=jenkins" -u "$USERPASS" http://sonarqube.paybyte.company/api/user_tokens/revoke'
                }
            }
            when { equals expected: true, actual: params.sq_code_analysis_reqd }
        }
        stage('Upload to S3'){
            steps{
                withAWS(region: 'ap-south-1', credentials: 'aws-credentials'){
                    s3Upload(file: "target/paybyte-${service_name}-${build_version}.jar", bucket: 'paybyte-artifacts', path: "${service_name}/", tags: tags.toString())
                }    
            }
        }
        stage('Tagging commit') {
            steps {
                echo "Tagging paybyte-${service_name} repo commit from ${branch} branch.."
                script {
                    if (!env.BUILD_VERSION?.trim()) {
                        error("Build version is empty! Cannot tag commit.")
                    }
                }
                withCredentials([usernamePassword(credentialsId: 'bitbucket-credentials', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                    // Delete the tag if it already exists locally (only the specified tag)
                    sh 'git tag -d "${BUILD_VERSION}" || true'  // The || true ensures that the command doesn't fail if the tag doesn't exist

                    // Create and push the tag
                    sh 'git tag -a "${BUILD_VERSION}" -m "Build"'
                    sh 'git push "https://prasun-paybyte:${GIT_PASSWORD}@bitbucket.org/paybyte/paybyte-${service_name}.git" --tags --force' // --force to push the overridden tag
                }
            }
        }
    }
    post {
        always {
            sh 'echo "Always clean up pipeline workspace"'
            sh 'rm -rf /home/ec2-user/workspace/build-java-artifacts-and-upload-to-s3/*'
            sh 'rm -rf /home/ec2-user/.m2*'
        }
    }
}
