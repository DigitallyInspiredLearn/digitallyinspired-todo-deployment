#!/usr/bin/env groovy

def call() {
    node {
        def users
        sh 'if [ ! -d sealed-configs ]; then mkdir sealed-configs; fi'
        stage('Check List') {
            dir('digitallyinspired-todo-deployment/jenkins-users') {
                users = readYaml(file: 'users.yml').users
            }
        }
        stage('Create OVPN profiles') {
            dir('digitallyinspired-todo-deployment/jenkins-users/sealed-configs') {
                for (user in users) {
                    if (fileExists("${it}-ovpn.yaml.sealed")) continue
                    def passphrase = sh returnStdout: true, script: 'cat /dev/urandom | base64 | head -c 10'
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
                stash(includes: '*.ovpn', name: 'new_ovpn_profiles')
            }
        }
        stage('Create K8S profiles') {
            dir('digitallyinspired-todo-deployment/jenkins-users/sealed-configs') {
                for (user in users) {
                    if (fileExists("${it}-k8s.yaml.sealed")) continue
                    def built = build job: 'CreateK8SNamespaceProfile', parameters: [
                        [$class: 'StringParameterValue', name: 'USER_NAME', value: user.name],
                        [$class: 'StringParameterValue', name: 'NAMESPACE', value: 'todo-dev']
                    ]
                    copyArtifacts(
                        projectName: 'CreateK8SNamespaceProfile',
                        selector: specific("${built.number}"),
                        target: '.'
                    )
                }
                stash(includes: '*-config', name: 'new_k8s_profiles')
            }
        }
        stage('Encrypt') {
            dir('digitallyinspired-todo-deployment/jenkins-users/sealed-configs') {
                unstash('new_ovpn_profiles')
                unstash('new_k8s_profiles')
                files = findFiles(glob: '*.ovpn')
                for (file in files) {
                    def file = "${file.name}"
                    encryptViaKubeseal(file, file.take(file.lastIndexOf('.')), 'sealed.yaml')
                }
                files = findFiles(glob: '*-config')
                for (file in files) {
                    def file = "${file.name}"
                    encryptViaKubeseal(file, file.take(file.lastIndexOf('.')), 'sealed.yaml')
                }
                sh 'tree'
//                stash(includes: '*.yaml.sealed', name: 'new_sealed_profiles')
            }
        }
//         stage('Push') {
//         sh 'tree'
//            unstash('new_sealed_profiles')
//            files = findFiles(glob: '*.yaml.sealed')
//            for (file in files) {
//                sh "git add ${file.name}"
//            }
//            sh "git commit -m 'Generated sealed user profile'"
//            sh 'git push origin master'
//        }
    }
}

def encryptViaKubeseal(user, file, extension) {
    sh "kubectl create secret generic ${user}-ovpn-secret --dry-run --from-file=${file.name} -o yaml > ${user}.yaml"
    sh "kubeseal < ${user}.yaml > ${user}.${extension}"
}

