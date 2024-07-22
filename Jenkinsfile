

pipeline {
  agent {
    label 'cassandra-medium'
  }

  // Global pipeline timeout
  options {
    timeout(time: 10, unit: 'HOURS')
    buildDiscarder(logRotator(artifactNumToKeepStr: '10', // Keep only the last 10 artifacts
                              numToKeepStr: '50'))        // Keep only the last 50 build records
  }

  stages {
    stage('Ls'){
        steps {
            script {
              sh label: 'Ls', script: '''#!/bin/bash -le
                ls /home/jenkins/agent/workspace
                pwd
              '''
            }
        }
    }
    stage('Unit Tests'){
      agent {
        docker {
          image 'janehe/cassandra-java-driver-dev-env'
          label 'cassandra-medium'
        }
      }
        steps {
            script {
              sh label: 'Unit tests', script: '''#!/bin/bash -le
                cd ~/cassandra-java-driver
                mvn -B -V test
              '''
            }
        }
    }

    stage ('Integration Tests'){
        steps {
            script {
              sh label: 'Integration tests', script: '''#!/bin/bash -le
                cd ~/cassandra-java-driver
                mvn -B -V -Dit.test="com.datastax.oss.**" verify
              '''
            }
        }
    }
  }
}
