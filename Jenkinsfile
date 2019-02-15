node {
    stage('Build') {
        image = docker.image("maven:3-alpine")
        image.inside('-v /root/.m2:/root/.m2') {
            sh 'mvn -B -DskipTests clean package'
        }
    }
    stage('Push') {
        docker.withRegistry('http://localhost:5000') {
            def app = docker.build("todo-jenkins/app")
            app.push()
        }
    }
}
