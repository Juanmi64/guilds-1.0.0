plugins {
    id("fabric-loom") version "1.17.20"
    `java-library`
    `maven-publish`
}

version = project.property("mod.version") as String
group = project.property("mod.group") as String

base {
    archivesName = project.property("mod.id") as String
}

repositories {
    // Pin exclusivo: los drivers JDBC se resuelven SOLO como artefacto desde
    // Maven Central. metadataSources(artifact()) evita parsear POMs, por lo que
    // no se arrastra ningún grafo transitivo (BOMs de AWS/JUnit) ni depende de
    // repos extra. Los drivers son autocontenidos; slf4j lo aporta Minecraft.
    exclusiveContent {
        forRepository {
            maven("https://repo.maven.apache.org/maven2/") {
                name = "CentralPin"
                metadataSources {
                    artifact()
                    ignoreGradleMetadataRedirection()
                }
            }
        }
        filter {
            includeGroup("org.mariadb.jdbc")
            includeGroup("org.xerial")
        }
    }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings("net.fabricmc:yarn:1.21.1+build.3:v2")
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.116.17+1.21.1")

    // Permisos estilo LuckPerms con fallback a OP. Se embebe (jar-in-jar).
    // Se excluye el bom/API de fabric-api para no mezclar módulos compilados
    // para otras versiones de MC (los aporta la fabric-api declarada arriba).
    val permsApi = modImplementation("me.lucko:fabric-permissions-api:0.3.3") as ModuleDependency
    permsApi.exclude(group = "net.fabricmc.fabric-api")
    include(permsApi)

    // Drivers JDBC: se embeben para que el jar sea autónomo (server-side).
    // runtimeOnly adicional: en runServer (dev) los JIJ anidados no van al classpath.
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    runtimeOnly("org.xerial:sqlite-jdbc:3.47.1.0")
    include("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    include("org.xerial:sqlite-jdbc:3.47.1.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.release = 21
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
