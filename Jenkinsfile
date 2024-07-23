#!groovy
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

def initializeEnvironment() {
  env.DRIVER_DISPLAY_NAME = 'CassandraⓇ Java Driver'
  env.DRIVER_METRIC_TYPE = 'oss'
  if (env.GIT_URL.contains('riptano/java-driver')) {
    env.DRIVER_DISPLAY_NAME = 'private ' + env.DRIVER_DISPLAY_NAME
    env.DRIVER_METRIC_TYPE = 'oss-private'
  } else if (env.GIT_URL.contains('java-dse-driver')) {
    env.DRIVER_DISPLAY_NAME = 'DSE Java Driver'
    env.DRIVER_METRIC_TYPE = 'dse'
  }

  env.GIT_SHA = "${env.GIT_COMMIT.take(7)}"
  env.GITHUB_PROJECT_URL = "https://${GIT_URL.replaceFirst(/(git@|http:\/\/|https:\/\/)/, '').replace(':', '/').replace('.git', '')}"
  env.GITHUB_BRANCH_URL = "${GITHUB_PROJECT_URL}/tree/${env.BRANCH_NAME}"
  env.GITHUB_COMMIT_URL = "${GITHUB_PROJECT_URL}/commit/${env.GIT_COMMIT}"

  env.MAVEN_HOME = "${env.HOME}/.mvn/apache-maven-3.3.9"
  env.PATH = "${env.MAVEN_HOME}/bin:${env.PATH}"

  /*
  * As of JAVA-3042 JAVA_HOME is always set to JDK8 and this is currently necessary for mvn compile and DSE Search/Graph.
  * To facilitate testing with JDK11/17 we feed the appropriate JAVA_HOME into the maven build via commandline.
  *
  * Maven command-line flags:
  * - -DtestJavaHome=/path/to/java/home: overrides JAVA_HOME for surefire/failsafe tests, defaults to environment JAVA_HOME.
  * - -Ptest-jdk-N: enables profile for running tests with a specific JDK version (substitute N for 8/11/17).
  *
  * Note test-jdk-N is also automatically loaded based off JAVA_HOME SDK version so testing with an older SDK is not supported.
  *
  * Environment variables:
  * - JAVA_HOME: Path to JDK used for mvn (all steps except surefire/failsafe), Cassandra, DSE.
  * - JAVA8_HOME: Path to JDK8 used for Cassandra/DSE if ccm determines JAVA_HOME is not compatible with the chosen backend.
  * - TEST_JAVA_HOME: PATH to JDK used for surefire/failsafe testing.
  * - TEST_JAVA_VERSION: TEST_JAVA_HOME SDK version number [8/11/17], used to configure test-jdk-N profile in maven (see above)
  */

  env.JAVA_HOME = sh(label: 'Get JAVA_HOME',script: '''#!/bin/bash -le
    . ${JABBA_SHELL}
    jabba which ${JABBA_VERSION}''', returnStdout: true).trim()
  env.JAVA8_HOME = sh(label: 'Get JAVA8_HOME',script: '''#!/bin/bash -le
    . ${JABBA_SHELL}
    jabba which 1.8''', returnStdout: true).trim()

  sh label: 'Download Apache CassandraⓇ or DataStax Enterprise',script: '''#!/bin/bash -le
    . ${JABBA_SHELL}
    jabba use 1.8
    . ${CCM_ENVIRONMENT_SHELL} ${SERVER_VERSION}
  '''

  if (env.SERVER_VERSION.split('-')[0] == 'dse') {
    env.DSE_FIXED_VERSION = env.SERVER_VERSION.split('-')[1]
    sh label: 'Update environment for DataStax Enterprise', script: '''#!/bin/bash -le
        cat >> ${HOME}/environment.txt << ENVIRONMENT_EOF
CCM_CASSANDRA_VERSION=${DSE_FIXED_VERSION} # maintain for backwards compatibility
CCM_VERSION=${DSE_FIXED_VERSION}
CCM_SERVER_TYPE=dse
DSE_VERSION=${DSE_FIXED_VERSION}
CCM_IS_DSE=true
CCM_BRANCH=${DSE_FIXED_VERSION}
DSE_BRANCH=${DSE_FIXED_VERSION}
ENVIRONMENT_EOF
      '''
  }

  sh label: 'Display Java and environment information',script: '''#!/bin/bash -le
    # Load CCM environment variables
    set -o allexport
    . ${HOME}/environment.txt
    set +o allexport

    . ${JABBA_SHELL}
    jabba use 1.8

    java -version
    mvn -v
    printenv | sort
  '''
}

def buildDriver(jabbaVersion) {
  withEnv(["BUILD_JABBA_VERSION=${jabbaVersion}"]) {
    sh label: 'Build driver', script: '''#!/bin/bash -le
      . ${JABBA_SHELL}
      jabba use ${BUILD_JABBA_VERSION}

      mvn -B -V install -DskipTests -Dmaven.javadoc.skip=true
    '''
  }
}

def executeTests() {
  def testJavaHome = sh(label: 'Get TEST_JAVA_HOME',script: '''#!/bin/bash -le
    . ${JABBA_SHELL}
    jabba which ${JABBA_VERSION}''', returnStdout: true).trim()
  def testJavaVersion = (JABBA_VERSION =~ /.*\.(\d+)/)[0][1]

  def executeTestScript = '''#!/bin/bash -le
    # Load CCM environment variables
    set -o allexport
    . ${HOME}/environment.txt
    set +o allexport

    . ${JABBA_SHELL}
    jabba use 1.8

    if [ "${JABBA_VERSION}" != "1.8" ]; then
      SKIP_JAVADOCS=true
    else
      SKIP_JAVADOCS=false
    fi

    INTEGRATION_TESTS_FILTER_ARGUMENT=""
    if [ ! -z "${INTEGRATION_TESTS_FILTER}" ]; then
      INTEGRATION_TESTS_FILTER_ARGUMENT="-Dit.test=${INTEGRATION_TESTS_FILTER}"
    fi
    printenv | sort

    mvn -B -V ${INTEGRATION_TESTS_FILTER_ARGUMENT} -T 1 verify \
      -Ptest-jdk-'''+testJavaVersion+''' \
      -DtestJavaHome='''+testJavaHome+''' \
      -DfailIfNoTests=false \
      -Dmaven.test.failure.ignore=true \
      -Dmaven.javadoc.skip=${SKIP_JAVADOCS} \
      -Dccm.version=${CCM_CASSANDRA_VERSION} \
      -Dccm.dse=${CCM_IS_DSE} \
      -Dproxy.path=${HOME}/proxy \
      ${SERIAL_ITS_ARGUMENT} \
      ${ISOLATED_ITS_ARGUMENT} \
      ${PARALLELIZABLE_ITS_ARGUMENT}
  '''
  echo "Invoking Maven with parameters test-jdk-${testJavaVersion} and testJavaHome = ${testJavaHome}"
  sh label: 'Execute tests', script: executeTestScript
}

def executeCodeCoverage() {
  jacoco(
    execPattern: '**/target/jacoco.exec',
    classPattern: '**/classes',
    sourcePattern: '**/src/main/java'
  )
}

def notifySlack(status = 'started') {
  // Notify Slack channel for every build except adhoc executions
  if (params.ADHOC_BUILD_TYPE != 'BUILD-AND-EXECUTE-TESTS') {
    // Set the global pipeline scoped environment (this is above each matrix)
    env.BUILD_STATED_SLACK_NOTIFIED = 'true'

    def buildType = 'Commit'
    if (params.CI_SCHEDULE != 'DO-NOT-CHANGE-THIS-SELECTION') {
      buildType = "${params.CI_SCHEDULE.toLowerCase().capitalize()}"
    }

    def color = 'good' // Green
    if (status.equalsIgnoreCase('aborted')) {
      color = '808080' // Grey
    } else if (status.equalsIgnoreCase('unstable')) {
      color = 'warning' // Orange
    } else if (status.equalsIgnoreCase('failed')) {
      color = 'danger' // Red
    }

    def message = """Build ${status} for ${env.DRIVER_DISPLAY_NAME} [${buildType}]
<${env.GITHUB_BRANCH_URL}|${env.BRANCH_NAME}> - <${env.RUN_DISPLAY_URL}|#${env.BUILD_NUMBER}> - <${env.GITHUB_COMMIT_URL}|${env.GIT_SHA}>"""
    if (!status.equalsIgnoreCase('Started')) {
      message += """
${status} after ${currentBuild.durationString - ' and counting'}"""
    }

    slackSend color: "${color}",
              channel: "#java-driver-dev-bots",
              message: "${message}"
  }
}

def describePerCommitStage() {
  script {
    currentBuild.displayName = "Per-Commit build"
    currentBuild.description = 'Per-Commit build and testing of development Apache CassandraⓇ and current DataStax Enterprise against Oracle JDK 8'
  }
}

def describeAdhocAndScheduledTestingStage() {
  script {
    if (params.CI_SCHEDULE == 'DO-NOT-CHANGE-THIS-SELECTION') {
      // Ad-hoc build
      currentBuild.displayName = "Adhoc testing"
      currentBuild.description = "Testing ${params.ADHOC_BUILD_AND_EXECUTE_TESTS_SERVER_VERSION} against JDK version ${params.ADHOC_BUILD_AND_EXECUTE_TESTS_JABBA_VERSION}"
    } else {
      // Scheduled build
      currentBuild.displayName = "${params.CI_SCHEDULE.toLowerCase().replaceAll('_', ' ').capitalize()} schedule"
      currentBuild.description = "Testing server versions [${params.CI_SCHEDULE_SERVER_VERSIONS}] against JDK version ${params.CI_SCHEDULE_JABBA_VERSION}"
    }
  }
}

// branch pattern for cron
// should match 3.x, 4.x, 4.5.x, etc
def branchPatternCron() {
  ~"((\\d+(\\.[\\dx]+)+))"
}

pipeline {
  agent {
    docker {
      image 'janehe/cassandra-java-driver-dev-env'
    }
    label 'cassandra-medium'
  }

  // Global pipeline timeout
  options {
    timeout(time: 10, unit: 'HOURS')
    buildDiscarder(logRotator(artifactNumToKeepStr: '10', // Keep only the last 10 artifacts
                              numToKeepStr: '50'))        // Keep only the last 50 build records
  }

  stages {
    stage('Tests'){
        steps {
            script {
              sh label: 'Unit tests', script: '''
                cd cassandra-java-driver
                mvn -B -V verify
              '''
            }
        }
    }
  }
}
