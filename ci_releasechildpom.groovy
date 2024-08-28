#!/usr/bin/env groovy
def call() {
    checkout poll: false,  \
             scm: [$class      : 'GitSCM', branches: [[name: '${BRANCH}']],  \
             doGenerateSubmoduleConfigurations: false,  \
             extensions: [[$class: 'DisableRemotePoll'],  \
             [$class: 'CleanCheckout'],  \
             [$class: 'UserExclusion', excludedUsers: '''ENM_Jenkins
            ENM_CI_Admin
            CDS_CI_Admin'''],  \
             [$class: 'LocalBranch', localBranch: '${BRANCH}']],
                   submoduleCfg: [],  \
             userRemoteConfigs: [[name: 'gcm', url: '${GERRIT_MIRROR}/${REPO}'],  \
             [name: 'gcn', url: '${GERRIT_CENTRAL}/${REPO}']]]
    sh '''
        git fetch gcm
        git status
       '''
    checkGerritSync()
    setBuildName()
    releasechildboms()
    updateMainPomDependency()
    commitUpdatedVersion()
}
def checkGerritSync() {
    sh '''
       RETRY=10
       SLEEP=60
       if [ -z ${BRANCH+x} ]; then
           echo "BRANCH is unset using master"
           branch="master"
       else
           echo "Using branch '$BRANCH'"
           branch=${BRANCH}
       fi
       retry=0
       while (( retry < RETRY )); do
           echo "INFO: Attempting retry #$((retry+1)) of $RETRY in $SLEEP seconds."
           # get the commit ID's on GC master and mirror
           echo "INFO: Checking commit ID's for '$branch' branch on Gerrit Central."
           gcr=$(git ls-remote -h ${GERRIT_CENTRAL}/${REPO} ${branch} | awk '{print $1}')
           gmr=$(git ls-remote -h ${GERRIT_MIRROR}/${REPO} ${branch} | awk '{print $1}')
           echo "INFO: central: ${gcr}"
           echo "INFO: mirror:  ${gmr}"
               if [[ "${gcr}" != "${gmr}" ]]; then
                   echo "INFO: Gerrit central and mirror are out of sync."
                   let "retry=retry+1"
                   if [[ "${retry}" == "${RETRY}" ]]; then
                       echo "Gerrit mirror not in sync with central"
                       exit 1
                   else
                       echo "Waiting for sync...."
                       sleep $SLEEP
                   fi
               else
                   break
               fi
       done
       local_head=$(git rev-parse HEAD)
       [ "${local_head}" != "${gmr}" ] && echo -e "Fetching upstream changes" && git pull origin ${branch}
       echo "INFO: Branch in sync between Gerrit central and mirror."
       '''
}

def setBuildName() {
    currentBuild.displayName = "${BUILD_NUMBER} | ${env.BUILD_VERSION}"
}

def commitUpdatedVersion() {

    sh '''
       #Replace new versions
        #clean all untracted files
        git clean -fdx
        #Add files
        git add .
        git commit -m "Adding new version of child pom"
        git push ${GERRIT_CENTRAL}/${REPO} HEAD:${BRANCH}
       '''
}

def updateMainPomDependency() {

    withMaven(jdk: env.JDK_HOME, maven: env.MVN_HOME, options: [junitPublisher(healthScaleFactor: 1.0)]) {


        env.updateversioncommand = "versions:update-property -Dproperty=version.network.networkhealthmonitor,version.network.nodesyncmonitor"

        def buildResult = sh returnStatus: true, script: 'mvn ' + env.updateversioncommand
        println "Build Return Status: " + buildResult
        if (buildResult != 0) {
            currentBuild.rawBuild.result = Result.FAILURE
            throw new hudson.AbortException('Release Failed, Failing Build')
        }

    }
}


def releasechildboms() {

    withMaven(jdk: env.JDK_HOME, maven: env.MVN_HOME, options: [junitPublisher(healthScaleFactor: 1.0)]) {

        // check if snapshot found if not found skip it-- we assume that no change on the child pom
        // if there is change then add a new version with snapshot

        sh '''
        for f in $(pwd)/*-Bom.xml; do
            echo '***' ${f} '*** Release Child poms'
            snapshotversion=$(grep 'SNAPSHOT</version>' ${f})
            stripedversion=$(echo "$snapshotversion" | sed 's/<\\/version>//g' | sed 's/<version>//g')
            removedsnapversion=$(echo "$stripedversion" | sed 's/-SNAPSHOT//g')
            trimmed_string=$(echo $removedsnapversion | tr -d ' ')
            mvn -V -f ${f} versions:set -DnewVersion=$trimmed_string
            mvn -V -f ${f} clean install deploy
        done
       '''
    }
}

return this