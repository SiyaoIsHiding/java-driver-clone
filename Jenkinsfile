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

def executeTests(){
      sh '''
      export JAVA_HOME=$(jabba which zulu@1.8)
      export PATH=$JAVA_HOME/bin:$PATH
      mvn -B -V verify
      '''
}

pipeline {
  agent {
    docker {
      image 'janehe/cassandra-java-driver-dev-env'
      label 'cassandra-amd64-large'
    }
  }


  stages {
    stage('Tests'){
      steps {
				script {
					executeTests()
					junit testResults: '**/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
					junit testResults: '**/target/failsafe-reports/TEST-*.xml', allowEmptyResults: true
				}
	    }
	  }
	}
}
