#!/usr/bin/env groovy

def call() {
	node {
	    stage('Check List') {
            def users
            dir('digitallyinspired-todo-deployment/jenkins-users') {
                users = readYaml(file: 'users.yml').users
                sh 'if [ ! -d sealed-configs ]; then mkdir configs; fi'
            }
            dir('digitallyinspired-todo-deployment/jenkins-users/configs') {
                for (user in users) {
                    if (fileExists("${it}.ovpn")) continue
                    def passphrase = sh returnStdout: true, script: 'cat /dev/urandom | base64 | head -c10'
                    def built = build job: 'CreateOpenVPNProfile', parameters: [
                        [$class: 'StringParameterValue', name: 'OVPN_USER_NAME', value: user.name],
                        [$class: 'StringParameterValue', name: 'OVPN_USER_EMAIL', value: user.email],
                        [$class: 'PasswordParameterValue', name: 'OVPN_USER_PASSPHRASE', value: passphrase],
                        [$class: 'PasswordParameterValue', name: 'CONFIRM_PASSPHRASE', value: passphrase]
                    ]
                    copyArtifacts(
                        projectName: 'CreateOpenVPNProfile',
                        selector: specific("${built.number}"),
                        target: '.'
                    )
                }
                sh 'mv client-configs/files/newest-user/* .'
                stash(includes: '*.ovpn', name: 'new_profiles')
            }
	    }
	    stage('Make secrets') {
            dir('digitallyinspired-todo-deployment/jenkins-users') {
                unstash('new_profiles')
                files = findFiles(glob: '*.ovpn')
                for (file in files) {
                    def user = "${file.name}"
                    user = user.take(user.lastIndexOf('.'))
                    sh "kubectl create secret generic ${user}-ovpn-secret --dry-run --from-file=${file.name} -o yaml > ${user}.yaml"
                    sh "kubeseal < ${user}.yaml > ${user}.yaml.sealed"
                    stash(includes: '*.yaml.sealed', name: 'new_sealed_profiles')
                }
            }
	    }
        stage('Push') {
            sh 'tree'
            unstash('new_sealed_profiles')
            files = findFiles(glob: '*.yaml.sealed')
            for (file in files) {
                sh "git add ${file.name}"
            }
            sh "git commit -m 'Generated sealed user profile'"
            git url: "ssh://git@github.com:DigitallyInspiredLearn/digitallyinspired-todo-deployment.git",
                credentialsId: 'ssh-bastion-creds',
                branch: 'master'
            sh 'git push origin master'
        }
	}
}
