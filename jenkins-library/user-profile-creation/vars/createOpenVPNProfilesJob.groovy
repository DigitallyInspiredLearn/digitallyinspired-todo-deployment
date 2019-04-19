#!/usr/bin/env groovy

def call() {
    node {
        def users
        stage('Checkout user list') {
            git 'https://github.com/DigitallyInspiredLearn/digitallyinspired-todo-deployment.git'
            users = readYaml(file: 'jenkins-users/users.yml').users
        }
        stage('Create OVPN profiles') {
            for (user in users) {
                if (fileExists("${user.name}.ovpn.sealed*")) continue
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
        stage('Create K8S profiles') {
            for (user in users) {
                if (fileExists("${user.name}-k8s-config.sealed*")) continue
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
            sh 'mv jenkins-users/kube-user-creation/newest-user/* .'
            stash(includes: '*-k8s-config', name: 'new_k8s_profiles')
        }
        stage('Encrypt') {
            unstash('new_ovpn_profiles')
            unstash('new_k8s_profiles')
            def files = findFiles(glob: '*.ovpn')
            for (file in files) {
                def user_name = file.name.take(file.name.lastIndexOf('.'))
                encryptViaKubeseal(user_name, file.name, 'sealed.yml')
            }
            files = findFiles(glob: '*-k8s-config')
            for (file in files) {
                def user_name = file.name.take(file.name.lastIndexOf('.'))
                encryptViaKubeseal(user_name, file.name, 'sealed.yml')
            }
            stash includes: '*.sealed.*', name: 'new_sealed_profiles'
        }
        stage('Push to GitHub') {
            dir('jenkins-users/sealed-configs') {
                unstash('new_sealed_profiles')
                sh 'tree'
                sshagent(['digitallyinspired-todo-deployment-deploy-key']) {
                    sh 'git remote set-url origin git@github.com:DigitallyInspiredLearn/digitallyinspired-todo-deployment.git'
                    sh 'git add .'
                    sh "git commit -m 'Generated sealed user profile'"
                    sh 'git push origin master'
                }
            }
        }
    }
}

def encryptViaKubeseal(user, file, extension) {
    sh "kubectl create secret generic ${user}-secret --dry-run --from-file=${file} -o yaml > ${user}.yaml"
    sh "kubeseal < ${user}.yaml > ${file}.${extension}"
}
