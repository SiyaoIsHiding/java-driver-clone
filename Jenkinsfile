

pipeline {
  agent {
    docker {
      image 'node:7-alpine'
      label 'cassandra-medium'
    }
  }


  stages {
    stage('Ls'){
        steps {
            script {
              sh label: 'Ls', script: '''
                ls /home/jenkins/agent/workspace
                pwd
              '''
            }
        }
    }

  }
}
