package com.gtrainer

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ApiError(val error: String)

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    routing {
        // Contains process availability only, never records, secrets, or model inputs.
        get("/healthz") {
            call.respond(HealthResponse("ok"))
        }
        // Fail closed until authenticated private routes are implemented in task 2.5.
        route("/api/{path...}") {
            handle {
                call.respond(HttpStatusCode.Unauthorized, ApiError("authentication_required"))
            }
        }
        // Docker packages the built UI in the jar; local development uses Vite.
        // These static resources contain no personal records or credentials.
        staticResources("/", "web")
    }
}

fun main() {
    val host = System.getenv("GTRAINER_HOST") ?: "127.0.0.1"
    val port = System.getenv("GTRAINER_PORT")?.toIntOrNull() ?: 8080
    require(port in 1..65535) { "GTRAINER_PORT must be a valid port" }
    embeddedServer(CIO, host = host, port = port, module = Application::module)
        .start(wait = true)
}
