
import java.net.Socket

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


fun loadEnv(task: JavaExec) {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.forEachLine { line ->
            if (line.isNotBlank() && !line.startsWith("#") && line.contains("=")) {
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) task.environment(parts[0].trim(), parts[1].trim())
            }
        }
    }
}

val knownMcpScripts = listOf("water-hammer.js", "shared-counter.js")

fun killAllMcpServers() {
    var killed = false
    knownMcpScripts.forEach { scriptName ->
        try {
            val pids = ProcessBuilder("pgrep", "-f", scriptName)
                .start().inputStream.bufferedReader().readText().trim()
            if (pids.isNotBlank()) {
                pids.lines().filter { it.isNotBlank() }.forEach { pid ->
                    ProcessBuilder("kill", "-9", pid).start().waitFor()
                }
                println("🛑 Killed existing MCP server ($scriptName, pids: $pids)")
                killed = true
            }
        } catch (_: Exception) {}
    }
    if (killed) Thread.sleep(500)
}

fun startMcpServer(projectDir: File, script: String): Process {
    killAllMcpServers()
    println("🚀 Starting MCP server: node $script")
    val process = ProcessBuilder("node", script)
        .directory(projectDir)
        .inheritIO()
        .start()
    Runtime.getRuntime().addShutdownHook(Thread { process.destroyForcibly() })
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
        try {
            Socket("localhost", 3001).close()
            println("✅ MCP server ready on port 3001")
            return process
        } catch (_: Exception) {
            Thread.sleep(300)
        }
    }
    process.destroyForcibly()
    throw GradleException("MCP server did not start within 15 seconds")
}

tasks.register<JavaExec>("runReactorDemo") {
    group = "demo"
    description = "Run the Reactor (Water-Hammer) demo — auto-starts water-hammer.js"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("demo.ReactorDemo")
    loadEnv(this)
    var mcpProcess: Process? = null
    doFirst { mcpProcess = startMcpServer(projectDir, "src/mcp/node/water-hammer.js") }
    doLast  { mcpProcess?.destroyForcibly(); println("🛑 MCP server stopped.") }
}

tasks.register<JavaExec>("runCollaborativeDemo") {
    group = "demo"
    description = "Run the Collaborative Agents (Even/Odd counter) demo — auto-starts shared-counter.js"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("demo.CollaborativeAgentsDemo")
    loadEnv(this)
    var mcpProcess: Process? = null
    doFirst { mcpProcess = startMcpServer(projectDir, "src/mcp/node/shared-counter.js") }
    doLast  { mcpProcess?.destroyForcibly(); println("🛑 MCP server stopped.") }
}

tasks.register("printClasspath") {
    doLast { println(sourceSets.main.get().runtimeClasspath.asPath) }
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