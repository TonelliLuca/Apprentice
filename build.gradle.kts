
plugins {
    id("java")
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.langchain4j:langchain4j-agentic:1.7.1-beta14")
    implementation("dev.langchain4j:langchain4j-mcp-docker:1.7.1-beta14")
    implementation("dev.langchain4j:langchain4j-mcp:1.7.1-beta14")
    implementation("dev.langchain4j:langchain4j:1.7.1")
    implementation("dev.langchain4j:langchain4j-http-client:1.7.1")
    implementation("dev.langchain4j:langchain4j-ollama:1.7.1")
    implementation("dev.langchain4j:langchain4j-open-ai:1.7.1")
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:1.8.0-beta15")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    // Test
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}


tasks.test {
    useJUnitPlatform()

    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.forEachLine { line ->
            // Ignora commenti e righe vuote
            if (line.isNotBlank() && !line.startsWith("#") && line.contains("=")) {
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    // Passa la variabile all'ambiente del test
                    environment(key, value)
                }
            }
        }
        println("✅ Loaded environment variables from .env")
    } else {
        println("⚠️ Warning: .env file not found. System.getenv() might return null.")
    }
}