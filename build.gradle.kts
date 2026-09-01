plugins {
    java
}

group = "com.kingbrezz"
version = "1.0.1"

repositories {
    mavenCentral()

    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain {
        languageVersion.set(
            JavaLanguageVersion.of(25)
        )
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    jar {
        archiveBaseName.set("RandomChunk")
        archiveVersion.set(project.version.toString())
    }
}
