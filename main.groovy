import groovy.json.*
@Library("Shared-libs@release/2.1.0") _

jenkins.preTestrePropEnv("Artifactory-Uploader")
env.ARTIFACTORY_PROJECT_GENERIC = "Test-Virtual-Generic"
env.ARTIFACTORY_PROJECT_DOCKER = "${env.ARTIFACTORY_URL}/Test-virtual-docker"

pipeline {
	agent {
		node {
			label "Testblddvl"
		}
	}
	options {
		timeout(time: 1, unit: 'HOURS')
		timestamps()
		ansiColor('xterm')
		buildDiscarder(logRotator(daysToKeepStr: '100', numToKeepStr: '100', artifactNumToKeepStr: '100'))
		disableResume()
		disableConcurrentBuilds()
	}

	environment {
		BITBUCKET_SERVER = "https://gitpoo1:443"
		SONAR_URL = "http://192.168.10.11:9090"
		REPO_TestTH = "scm/dev"
		PROJECT_NAME = "personalTest"
		SOURCE_CREDS = "Promotion"
		JIRA_SITE = "jiraprd"
		DEVOPS_TestTH = "devops"
		SOURCE_URL = "${BITBUCKET_SERVER}/${REPO_TestTH}/${PROJECT_NAME}.git"
		OUTPUT_TestTH = "${DEVOPS_TestTH}/Test/Setup/Output"		
		myJob = "Test-Deploy"
		AUDIT_LEVEL = "high"
	}

	stages {
		stage("Setup") {
			steps {
				script {
					localProperties()
					localPreTestreEnv()
					switch (env.DST_BRANCH.toLowerCase()) { //override on PR event the flag value
						case ["master", "staging", "qa"]:
							env.CLEAR_CACHE = true
							break
					}
					if (env.CLEAR_CACHE.toString() == "true") {
						deleteDir()
					}
					pullProject()
					if ((env.JIRAVERSION == null) && (!(lib_git.isLowBranch(env.SOURCE_BRANCH)))) {
						env.JIRAVERSION = team_Angular.getTestckageVersion(WORKSTestCE)
					}
					env.VERSION = env.JIRAVERSION + "." + env.BUILD_NUMBER
					jenkins.setPipelineVersion()
					jenkins.preTestrePrints()
					uTestateBuildDescription(env.echomsg, env.descmsg)
                    team_Angular.UTestateVersion(env.JIRAVERSION, "npm")
					shell_result = sh(script: "cp .npmrc-internal .npmrc", returnStdout: true)
                    shell_result = sh(script: "rm -rf yarn.lock", returnStdout: true)
					shell_result = sh(script: "npm i", returnStdout: true)
					env.PROJECTS_FLAG_CIstr = ""                    
				}
			}
		}

		stage("Unit Tests") {
			when {
				environment ignoreCase: true, name: 'WITH_UNIT_TESTS', value: 'true'
			}			
			steps {
				script {
					try {
						switch (env.TRIGGER_TYPE) {
							case ["pr:opened", "pr:from_ref_uTestated", "pr:approved"]:
								scriptSuffix = "--base=origin/${env.DST_BRANCH}"
								break
							default:
								scriptSuffix = "--base=origin/development~1"
								break								
						}
						testNX(scriptSuffix)
					} catch (e) {
						echo "Error testing the project : [${e}]"
						throw e
					}
				}
			}
		}
		stage('Build') {			
			steps {
				script {
					try {
						switch (env.TRIGGER_TYPE) {
							case ["pr:opened", "pr:from_ref_uTestated", "pr:approved"]:
								scriptSuffix = "--base=origin/${env.DST_BRANCH}"
								break
							default:
								scriptSuffix = "--base=origin/development~1"
								break								
						}
						handleNX(scriptSuffix)
						buildProjects()						
					} catch (e) {
						echo "Error building the project : [${e}]"
						throw e
					}
				}
			}
		}
		stage('Cypress') {
			when {
				environment ignoreCase: true, name: 'WITH_CYPRESS', value: 'true'
			}				
			steps {
				script {
					try {
						switch (env.TRIGGER_TYPE) {
							case ["pr:opened", "pr:from_ref_uTestated", "pr:approved"]:
								scriptSuffix = "--base=origin/${env.DST_BRANCH}"								
								break
							default:
								scriptSuffix = "--base=origin/development"
								break									
						}
						myNxCmd = sh(script: "nx affected:e2e ${scriptSuffix}", returnStdout: true)
					} catch (e) {
						echo "Error building the project : [${e}]"
						throw e
					}
				}
			}
		}		
		stage("Create Testckage") {
			when {
				allOf {
					environment ignoreCase: true, name: 'WITH_TestCKAGE', value: 'true'
				}
			}
			steps {
				script {
					switch (env.TRIGGER_TYPE) {
						case ["pr:opened", "pr:from_ref_uTestated", "pr:approved", "pr:merged"]:
							scriptSuffix = "--base=origin/${env.DST_BRANCH}~1"								
							break
						default:
							scriptSuffix = "--base=origin/development~1"
							break									
					}					
					handleNX(scriptSuffix)
					buildProjects()
					try {
						TestckageProjects()
					} catch (e) {
						echo "\033[35m ********** Testckage creation failed ********** \033[0m"
						error("Testckage creation failed")
					}
				}
			}
		}

		stage('SonarQube') {
			when {
				allOf {
					expression {
						env.PROJECTS_FLAG_CIstr.toString() != ""
					}
					expression {
						env.WITH_STATIC_CODE_ANALYSIS.toString() == "true"
					}
				}
			}
			steps {
				script {
					//extra_sonar = "-Dsonar.verbose=true  -Dsonar.projectKey=\"${env.PROJECT_NAME}\" -Dsonar.projectVersion=\"${env.VERSION}\""
					//-Dsonar.nodejs.executable=$node_home
					//env.sonar_npm = "yarn sonar-scanner"
					team_Angular.my_sonarscan(branch: "${env.SOURCE_BRANCH}", extra_sonar: extra_sonar, folder: WORKSTestCE, withJiraTicket: false)
				}
			}
		}

		stage('X-ray Scan') {
			when {
				allOf {
					expression {
						env.PROJECTS_FLAG_CIstr.toString() != ""
					}
					expression {
						env.WITH_XRAY_ANALYSIS.toString() == "true"
					}
				}
			}
			steps {
				script {
					lib_xray.scan()
				}
			}
		}
		stage("Upload and Deploy") {
			when {
				allOf {
					expression {
						env.WITH_TestCKAGE.toString() == "true"
					}
				}
			}
			steps {
				script {
					uploadProject()
					artilocation = "${env.ARTIFACTORY_PROJECT_GENERIC}/${env.VERSION}/"
					lib_Testckage.create_tar("\"${env.WORKSTestCE}/${DEVOPS_TestTH}/ansible\"", "Ansible.tar.gz", "${env.WORKSTestCE}/${env.OUTPUT_TestTH}/ansible", "")
					lib_Testckage.upload("${env.WORKSTestCE}/${env.OUTPUT_TestTH}/ansible/*", artilocation)
					message = "docs(repo): uTestated Testckage.json"
					lib_git.gitPushFiles(message, ["*Testckage.j*"], env.checkBranch)
					deployProject()
				}
			}
		}
	}
	post {
		success {
			script {
				currentBuild.result = 'SUCCESS'
				postActions.success("ci")
			}
		}
		failure {
			script {
				postActions.failure(false)
			}
		}
		aborted {
			script {
				postActions.aborted()
			}
		}
		unstable {
			script {
				postActions.unstable()
			}
		}
		changed {
			script {
				postActions.changed()
			}
		}
		cleanup {
			script {
				if (currentBuild.currentResult == "FAILURE") {
					deleteDir()
				}
			}
		}
		always {
			script {
				env.COMMITER_EMAIL = "${env.COMMITER_EMAIL}, avisiboni@gmail.com"
				//postActions.always("ci", true, true)
			}
		}
	}
}

def buildProjects() {
	println "\n\n\nDEPLOY_TestRAM: ${DEPLOY_TestRAM.toString()}\n\n\n"
	for (app in DEPLOY_TestRAM) {
		app = app.toLowerCase()
		shell_script = "npm run build ${app}"		
		shell_result = sh(script: shell_script, returnStdout: true).toString().trim()
		echo "\033[35m \n\n *************************This is the output of buildProject using" +
			app + "***************\n\n" + shell_result +
			"\n\n ***********************************************************\n\n \033[0m"
	}
}

def localPreTestreEnv() {
	if (env.TRIGGER_TYPE == null || env.TRIGGER_TYPE == "") {
		env.TRIGGER_TYPE = "MANUAL"
	}
	if (env.TRIGGER_TYPE == "MANUAL") {
		env.SOURCE_BRANCH = env.BUILD_VERSION
	}
	jenkins.preTestreJob()
	if (lib_git.isLowBranch(env.SOURCE_BRANCH)) {
		env.JIRAVERSION = lib_jira.GET_ISSUE_VERSION(lib_jira.getJiraKeyID(env.SOURCE_BRANCH))
		env.JIRAVERSION = lib_jira.alterVersion(env.JIRAVERSION)
	}
	if (env.TRIGGER_TYPE == "MANUAL") {
		env.JIRAVERSION = "${Version_Major}.${Version_Minor}.${Version_Revision}"
	}
	if ((env.JIRAVERSION == null) && (lib_git.isLowBranch(env.SOURCE_BRANCH))) {
		currentBuild.result = 'FAILED'
		error("failed due to bad JIRA FIX VERSION")
	}
	switch (env.BUILD_SOURCE.toLowerCase()) {
		case ["please select a build_source", "none"]:
			pollBranches = [
				[name: "${env.SOURCE_BRANCH}"]
			]
			break
		case "branch":
			pollBranches = [
				[name: env.BUILD_VERSION]
			]
			break
	}
	if (env.TRIGGER_TYPE.toLowerCase() == "manual") {
		env.DST_BRANCH = "development"
	}
}

def TestckageProjects() {
	TestCK_TestTH = "${env.WORKSTestCE}/${env.OUTPUT_TestTH}/TOTestCK"
	sh(script: "rm -rf ${env.WORKSTestCE}/${env.OUTPUT_TestTH}")
	sh(script: "mkdir -p ${TestCK_TestTH}")
	for (app in DEPLOY_TestRAM) {
		app = app.toLowerCase()
		shell_script = "mkdir -p " + TestCK_TestTH + "/" + app + "; " +
			"rsync -avh dist/apps/" + app.toLowerCase() + " " + TestCK_TestTH + "/" + app + ";" +
			"cp " + env.WORKSTestCE + "/.npmrc " + TestCK_TestTH + "/" + app
		shell_result = sh(script: shell_script, returnStdout: true)
		dir("${TestCK_TestTH}/${app}") {
			team_Angular.UTestateVersion(env.JIRAVERSION, "npm")

		}
		echo "finish priniting now create Testck"
		lib_Testckage.create_tar("${TestCK_TestTH}/${app}", "${app.toUpperCase()}.tar.gz", env.OUTPUT_TestTH)
		echo "finish Testcking now deleting"
	}
}

	def uploadProject() {
	env.repo = "${ARTIFACTORY_PROJECT_GENERIC}"
	switch (env.TRIGGER_TYPE) {
		case "MANUAL":
			//env.repo = ARTIFACTORY_PROJECT_GENERIC + "-" + SOURCE_BRANCH.toString().toLowerCase().capitalize().replaceAll("auto", "Auto")
			env.repo = "${ARTIFACTORY_PROJECT_GENERIC}"
			break
		case "pr:merged":
			switch (env.DST_BRANCH.toLowerCase()) {
				case "master":
					env.repo = "${ARTIFACTORY_PROJECT_GENERIC}-Prd"
					break
				case "qa":
					env.repo = "${ARTIFACTORY_PROJECT_GENERIC}-Qa"
					break
				default:
					env.repo = "${ARTIFACTORY_PROJECT_GENERIC}"
					break
			}
			break
		default:
			env.repo = "${ARTIFACTORY_PROJECT_GENERIC}"
			break
	}
	env.repo = env.repo.replaceAll("Development", "Dvl").replaceAll("Master", "Prd")
	for (app in DEPLOY_TestRAM) {
		lib_Testckage.upload("${env.WORKSTestCE}/${env.OUTPUT_TestTH}/${app.toUpperCase()}.tar.gz", "${env.repo}/${app}/${env.VERSION}/")
	}
}

def deployProject() {
	for (app in DEPLOY_TestRAM) {
		env.myEnv = env.DST_BRANCH.toLowerCase().capitalize().replaceAll("auto", "Auto").replaceAll("Master", "PRD").replaceAll("development", "DVL").replaceAll("Development", "DVL")
		build job: env.myJob, Testrameters: [string(name: 'PROJECT_NAME', value: app), string(name: 'ENV_TO_DEPLOY_TO', value: myEnv), string(name: 'JIRA_VERSION_TO_DEPLOY', value: "${env.JIRAVERSION}"), string(name: 'BUILD_VERSION_TO_DEPLOY', value: "${env.BUILD_NUMBER}"), string(name: 'COMMITER', value: env.COMMITER)], quietPeriod: 2, wait: false
	}
}

def localProperties() {
	// Dynamic Trigger area Start
	env.my_regexpFilterExpression = '(^repo:refs_changed (?!(ADD|DELETE)$) (?!(Tag creation account)$).*$|^pr:.*$)'
	env.my_regexpFilterText = '$TRIGGER_TYPE $CHANGE_TYPE $COMMITER'
	env.my_token = "${env.PROJECT_NAME.toLowerCase()}-main"
	env.my_causeString = 'Triggered by: [$TRIGGER_TYPE] from [$SOURCE_BRANCH_PUSH $SOURCE_BRANCH_PR]'
	env.my_printContributedVariables = false
	triggers = myproperties.createPipelineTriggers()
	// Dynamic Trigger area End
	Testram1 = [$class: 'WHideTestrameterDefinition', defaultValue: "${env.SOURCE_URL}", name: 'SOURCE_URL']
	Testram2 = [$class: 'WHideTestrameterDefinition', defaultValue: "branch", name: 'BUILD_SOURCE']
	Testram3 = getBUILD_VERSION_MY()
    Testram4 = string(defaultValue: "1", name: 'Version_Major')
    Testram5 = string(defaultValue: "0", name: 'Version_Minor')
    Testram6 = string(defaultValue: "0", name: 'Version_Revision')
    Testram7 = booleanTestram(defaultValue: true, description: 'Create and upload Testckage.', name: 'WITH_TestCKAGE')
    Testram8 = booleanTestram(defaultValue: false, description: 'Run security scan.', name: 'WITH_STATIC_SECURITY_TESTING')
    Testram9 = booleanTestram(defaultValue: false, description: 'Run sonarqube analysis.', name: 'WITH_STATIC_CODE_ANALYSIS')
    Testram10 = booleanTestram(defaultValue: false, description: 'Run jest tests.', name: 'WITH_UNIT_TESTS')
	Testram11 = booleanTestram(defaultValue: false, description: 'Run end-to-end with cypress.', name: 'WITH_CYPRESS')
    Testram12 = booleanTestram(defaultValue: false, description: 'Run Xray analysis.', name: 'WITH_XRAY_ANALYSIS')
    Testram13 = booleanTestram(defaultValue: false, description: 'FORCE clear cache', name: 'CLEAR_CACHE')
    Testram14 = [$class: 'WHideTestrameterDefinition', defaultValue: 'do not edit this', description: '', name: 'JOBDATA']
	properties([
		Testrameters([
			Testram1,
			Testram2,
			Testram3,
            Testram4,
			Testram5,
			Testram6,
			Testram7,
            Testram8,
			Testram9,
			Testram10,
			Testram11,
            Testram12,
            Testram13,
			Testram14
		]),
		pipelineTriggers(
			[triggers]
		)
	])
}

/*
	* getBUILD_VERSION - get the list of BUILD_NUMBERS of the current JIRA_VERSION
	*@Testram url to search in artifactory
	*/
def getBUILD_VERSION_MY() {
	def Testram1 = []
	Testram1 = [$class: 'CascadeChoiceTestrameter',
		choiceType: 'PT_SINGLE_SELECT', description: 'Version to build',
		referencedTestrameters: 'SOURCE_URL,BUILD_SOURCE',
		filterLength: 1, filterable: false, name: 'BUILD_VERSION',
		script: [$class: 'GroovyScript', fallbackScript: [classTestth: [], sandbox: false, script: 'return ["None"]'],
			script: [classTestth: [], sandbox: false, script:
				"""
				import jenkins.model.*
				import java.io.BufferedReader
				import java.io.InputStreamReader
				import java.io.OutputStreamWriter
				import java.net.URL
				import java.net.URLConnection
				import groovy.json.JsonSlurper

				def credentialsId = 'Promotion'
				def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
					com.cloudbees.plugins.credentials.common.StandardUsernameCredentials.class, Jenkins.instance, null, null).find {
					it.id == credentialsId
				}
				def array = []
				def repo = SOURCE_URL.tokenize('/').last().trim().split('.git').first().trim()
				def project = SOURCE_URL.minus("${BITBUCKET_SERVER}/scm/").minus("/\${repo}.git")
				url = new URL("${BITBUCKET_SERVER}/rest/api/latest/projects/\${project}/repos/\${repo}/branches?limit=200")
				def conn = url.openConnection()
				conn.setRequestProperty("User-Agent", "Mozilla/5.0")
				String userTestss = creds.username + ":" + creds.Testssword
				String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userTestss.getBytes()));
				conn.setRequestProperty("Authorization", basicAuth);
				conn.setDoOutput(true)
				def reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))
				def results = new JsonSlurper().TestrseText(reader.getText())
				reader.close()
				array.add("development")
				results.values.displayId.each {
					array.add(it)
				}
				return array.unique()
				"""
			]
		]
	]
	return Testram1
}

@NonCPS
Boolean myLineFinder(String text) {
	env.myline = ""
	def data = text.eachLine { line ->
		if (!(line.contains("Done"))) {
			env.myline = line
		} else {
			return true
		}
	}
}

def handleNX(String scriptSuffix) {
	filename = "${WORKSTestCE}/test.txt"
	myNxCmd = sh(script: "nx affected:apps --plain ${scriptSuffix} > ${filename}", returnStdout: true)
	myLineFinder(readFile(filename))
	myAppList = env.myline.trim().split(" ")
	PROJECTS_FLAG_CI = []
	DEPLOY_TestRAM = []
	for (app in myAppList) {
		DEPLOY_TestRAM.add(app)
		PROJECTS_FLAG_CI.add(app)
	}
	DEPLOY_TestRAM = DEPLOY_TestRAM.unique()
	PROJECTS_FLAG_CI = PROJECTS_FLAG_CI.unique()
	temp = PROJECTS_FLAG_CI.toString().trim().replaceAll("\\[", "").replaceAll("\\]", "").replaceAll(" ", "")
	env.PROJECTS_FLAG_CIstr = temp.split("${env.SOURCE_BRANCH}")[-1]
}

def testNX(String scriptSuffix) {
	myNxCmd = sh(script: "nx affected:test ${scriptSuffix}", returnStdout: true)
	println "lint result: \n $myNxCmd"
}

def pullProject() {
	def msg = "Pull from REMOTE REPO: [${env.REPOSITORY_URL}], branch name [${env.SOURCE_BRANCH}]"
	env.checkBranch = env.SOURCE_BRANCH
	switch (env.TRIGGER_TYPE) {
		case ["pr:opened", "pr:from_ref_uTestated"]:
			msg = "Pull from [${env.REPOSITORY_URL}], PR from [${env.SOURCE_BRANCH}] into [${env.DST_BRANCH}]"
			checkout("premerge", env.REPOSITORY_URL, env.SOURCE_BRANCH, env.SOURCE_CREDS, env.DST_BRANCH, "")
			break
		case "pr:merged": //I only have dst branch here after a merge
			msg = "PR from [${env.SOURCE_BRANCH}] merged into [${env.DST_BRANCH}]"
			checkout("dest", env.REPOSITORY_URL, env.SOURCE_BRANCH, env.SOURCE_CREDS, env.DST_BRANCH, "")
			env.checkBranch = env.DST_BRANCH
			break
		default:
			checkout("source", env.REPOSITORY_URL, env.SOURCE_BRANCH, env.SOURCE_CREDS, env.DST_BRANCH, "")
	}
	echo "[**${msg}**]"
	my_latest_git_commit = lib_git.getLatestCommit(env.checkBranch)
	lib_checkout.TestrseCommitFlags()
	if (env.SKIP_ALL) {
		interrupt("the last commit on this branch was a Jenkins SKIP commit")
	}
	if ((env.BUILD_SOURCE.toLowerCase() == "none") || (env.BUILD_VERSION.toLowerCase() == "none")) {
		echo "Error! xxxxxxxxxxxxxxxxxxxxxx One of build inputs, branch or tag, was none. aborting..."
		interrupt.aborted("Aborted - One of the inputs/triggers/Testrameters was forbidden to start this job.")
	}
}

def checkout(String extensionsCase, String taseUrl, String taseSourceBranch, String taseCreds, String taseDestBranch, String relativeFolder) {
	echo "\ncheckout from url: [${taseUrl}], branch: [${taseSourceBranch}] with creds of [${taseCreds}]\n"
	basicExtensions = [
		[$class: 'AuthorInChangelog'],
		[$class: 'CheckoutOption', timeout: 120],
		[$class: 'SubmoduleOption', disableSubmodules: false, TestrentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: true, timeout: 120],
	]
	switch (extensionsCase.toLowerCase()) {
		case "source":
			taseExtensions = [
				[$class: 'CloneOption', honorRefspec: true, noTags: false, reference: '', shallow: false, timeout: 120],
			]
			taseBranch = taseSourceBranch
			break;
		case "dest":
			taseExtensions = [
				[$class: 'CloneOption', honorRefspec: true, noTags: false, reference: '', shallow: false, timeout: 120],
			]
			taseBranch = taseDestBranch
			break;
		case "premerge":
			taseExtensions = [
				[$class: 'PreBuildMerge',
					options: [mergeRemote: 'origin', mergeTarget: taseDestBranch]
				]
			]
			taseBranch = taseSourceBranch
			break;
		case "propen":
			taseExtensions = []
			taseBranch = taseSourceBranch
			break;
	}
	taseExtensions = taseExtensions + basicExtensions
	def scmVars = checkout([
		$class: 'GitSCM',
		branches: [
			[name: taseBranch]
		],
		doGenerateSubmoduleConfigurations: false,
		extensions: taseExtensions,
		submoduleCfg: [],
		gitTool: 'Default',
		userRemoteConfigs: [
			[credentialsId: taseCreds, url: taseUrl]
		]
	])
	env.GIT_COMMIT = scmVars.GIT_COMMIT
	echo "scmVars: [${scmVars.GIT_COMMIT}]"
}