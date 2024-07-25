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

def addJavaPath() {
	sh '''
  export JAVA8_HOME=$(jabba which zulu@1.8)
  export JAVA11_HOME=$(jabba which zulu@1.11.0)
	export JAVA17_HOME=$(jabba which amazon-corretto@1.17.0-0.35.1)
  export JAVA_HOME=$JAVA8_HOME
  export PATH=$JAVA_HOME8/bin:$JAVA11_HOME/bin:$JAVA17_HOME/bin:$PATH
  '''
	if (env.SERVER_VERSION.split('-')[0] == 'dse') {
		sh 'export CCM_IS_DSE=true'
	} else {
		sh 'export CCM_IS_DSE=false'
	}
}

def executeTests() {
	def testJavaHome = sh(label: 'Get TEST_JAVA_HOME',script: "jabba which ${TEST_JAVA_VERSION}", returnStdout: true).trim()
  def testJavaVersion = (TEST_JAVA_VERSION =~ /.*\.(\d+)/)[0][1]
  sh "mvn - B - V verify -Ptest-jdk-" + testJavaVersion +
      " -DtestJavaHome="+testJavaHome+
			" -Dccm.version=${SERVER_VERSION} -Dccm.dse=$CCM_IS_DSE"
}

pipeline {
  agent {
    docker {
      image 'janehe/cassandra-java-driver-dev-env'
      label 'cassandra-amd64-large'
    }
  }


	stages {
		stage('Matrix') {
				matrix {
					axes {
						axis {
								name 'TEST_JAVA_VERSION'
								values 'zulu@1.8', 'openjdk@1.11.0', 'openjdk@1.17.0'
						}
						axis {
								name 'SERVER_VERSION'
								values '3.11',      // Latest stable Apache CassandraⓇ
											 '4.1',       // Development Apache CassandraⓇ
											 'dse-6.8.30', // Current DataStax Enterprise
											 '5.0-beta1' // Beta Apache CassandraⓇ
						}
			    }
					stages {
						stage('Tests') {
							steps {
				        script {
					  			addJavaPath()
				          executeTests()
				          junit testResults: '**/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
				          junit testResults: '**/target/failsafe-reports/TEST-*.xml', allowEmptyResults: true
				        }
				      }
						}
					}
	    }
	  }
	}
}
