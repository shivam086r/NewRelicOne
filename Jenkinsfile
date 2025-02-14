def tags=[:]
tags["file-type"]="jar"

def ex(value, message){
    currentBuild.result = 'ABORTED'
    error(message + '\nValue passed: ' + value)
}

// Function to create a unique tag (defined outside the declarative pipeline block)
def createUniqueTag(baseTag) {
    def newTag = baseTag
    def tagExists = sh(script: "git rev-parse ${newTag} 2>/dev/null", returnStatus: true)
    def counter = 1

    // Loop to add a number suffix until the tag does not exist
    while (tagExists == 0) {
        newTag = "${baseTag}.${counter}"
        tagExists = sh(script: "git rev-parse ${newTag} 2>/dev/null", returnStatus: true)
        counter++
    }

    return newTag
}

pipeline {
    agent {
        label "workers"
    }
    environment {
        SQ_TOKEN_FILE='sonarqube_token.txt'
    }
    parameters {
        string description: 'Application build version in YYYY.MM.DD format', name: 'build_version', trim: true
        choice choices: ['customer-service', 'merchant-service', 'notification-service', 'order-service', 'product-service', 'transaction-service', 'user-service', 'utility-service'], description: 'Application name', name: 'service_name'
        string description: 'Branch name', name: 'branch', trim: true
        booleanParam description: 'Require SonarQube code analysis?', defaultValue: false, name: 'sq_code_analysis_reqd'
    }
    stages {
        stage('Pre-Flight checks for Parameters') {
            steps {
                script {
                    if (!params.build_version?.trim()) {
                        ex(params.build_version, "Parameter build_version used for application build version is empty. Exiting!")
                    }
                    boolean isMain = params.branch ==~ /^main$/
                    boolean isMaster = params.branch ==~ /^master$/
                    if (isMain || isMaster) {
                        boolean isMatched = params.build_version ==~ /^\d{4}\.\d{2}\.\d{2}$/
                        if (!isMatched) {
                            ex(params.build_version, "Parameter build_version used for application build version does not match the pattern YYYY.MM.XX. Exiting!")
                        }
                    }
                    else {
                        boolean isMatched = params.build_version ==~ /^\d{4}\.\d{2}\.\d{2}-SNAPSHOT$/
                        if (!isMatched) {
                            ex(params.build_version, "Branch name is not one of 'main' or 'master'. Hence, the Parameter build_version should have the -SNAPSHOT suffix. Exiting!")
                        }
                    }
                    println "Pre-Flight checks for parameters - Complete!"
                }
            }
        }
        stage('Java and Maven version') {
            steps {
                sh 'echo "### Java version ###"; java -version; echo "### Maven version ###"; mvn -v'
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
                sh 'sed -i -E "s#(<version>).*SNAPSHOT.*(</version>)#\\1${build_version}\\2#" pom.xml'
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
                withCredentials([usernamePassword(credentialsId: 'bitbucket-credentials', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                    script {
                        // Create a unique tag based on the base tag (build_version)
                        build_version = createUniqueTag(build_version)

                        // Create and push the tag
                        sh "git tag -a ${build_version} -m 'Build'"
                        sh 'git push "https://prasun-paybyte:${GIT_PASSWORD}@bitbucket.org/paybyte/paybyte-${service_name}.git" --tags'
                    }
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
