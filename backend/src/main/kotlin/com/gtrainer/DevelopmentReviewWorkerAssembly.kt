package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Operator-only opt-in. Nothing here is accepted from a browser or inherited personal settings. */
@Serializable
internal data class DevelopmentReviewWorkerConfiguration(val enabled: Boolean, val nodeName: String, val bootId: String,
    val containerId: String, val supervisorSocket: String, val applianceMemoryLimitBytes: Long, val protectApplication: Boolean,
    val nodeHost: String, val nodeUser: String, val nodePort: Int, val identityFile: String, val knownHostsFile: String) {
    init {
        DevelopmentNodeBoundary(nodeName, bootId, containerId)
        DevelopmentHealthPolicy(applianceMemoryLimitBytes, protectApplication)
        require(Path.of(supervisorSocket).isAbsolute)
        ssh() // Validate fixed operations/arguments without reading files or contacting a host.
    }
    fun ssh() = DevelopmentNodeSshConfiguration(nodeName, nodeHost, nodeUser, nodePort, Path.of(identityFile), Path.of(knownHostsFile))
    override fun toString() = "DevelopmentReviewWorkerConfiguration([REDACTED])"
}

internal fun DevelopmentReviewLaunchSettings.worker(store: DevelopmentReviewStore): DevelopmentReviewWorker? {
    val configuration = workerConfiguration?.takeIf { it.enabled } ?: return null
    require(System.getProperty("os.name") == "Linux" && ProcessHandle.current().parent().orElseThrow().pid() == 1L)
    require(System.getenv("GTRAINER_SUPERVISOR_SOCKET") == configuration.supervisorSocket)
    val boundary = DevelopmentNodeBoundary(configuration.nodeName, configuration.bootId, configuration.containerId)
    val policy = DevelopmentHealthPolicy(configuration.applianceMemoryLimitBytes, configuration.protectApplication)
    val ssh = configuration.ssh()
    ssh.verifyFiles()
    val supervisor = DevelopmentReviewSupervisorClient(DevelopmentUnixSupervisorTransport(Path.of(configuration.supervisorSocket)))
    val node = DevelopmentNodeHealthReader(ssh)
    val sample = AtomicReference<Pair<DevelopmentHealthDiagnostics, Long>?>(null)
    val execution = DevelopmentOwnedRunExecutor(supervisor, policy, { handle, token ->
        val runtime = DevelopmentOllamaRuntimeHealthRead(HttpClient(CIO), {
            DevelopmentRuntimeHealth(token.epoch, token.runtimeGeneration, boundary, token.desiredBinding, supervisor.running(handle), 0)
        }, { it.token == token && it.boundary == boundary && supervisor.running(handle) })
        OwnedDevelopmentHealthSource(runtime, DevelopmentReadOnlyHealthSource(runtime, node, if (policy.protectApplication) node else null)) { readings ->
            sample.set(readings?.let {
                val oldest = listOfNotNull(it.runtime.startedAtNanos, it.resources.startedAtNanos, it.application?.startedAtNanos,
                    it.initialBaseline?.first?.startedAtNanos).min()
                DevelopmentHealthDiagnostics(token.runtimeGeneration, token.containerIdentity, it.runtime.value.ready,
                    it.resources.value.availableBytes, it.resources.value.applianceMemoryLimitBytes, 0) to oldest
            })
        }
    })
    return DevelopmentReviewWorker(store, boundary, execution, DevelopmentNodeTerminationVerifier(boundary.nodeName, DevelopmentNodeSshTransport(ssh)),
        { model -> models().any { it == model } }, policy.applianceMemoryLimitBytes, supervisor,
        { sample.get()?.let { (value, oldest) -> value.copy(oldestMeasurementAgeMillis = ((System.nanoTime() - oldest) / 1_000_000).coerceAtLeast(0)) } })
}

private class OwnedDevelopmentHealthSource(private val runtime: AutoCloseable, private val source: DevelopmentRuntimeHealthSource,
    private val observe: (DevelopmentHealthReadings?) -> Unit) : DevelopmentRuntimeHealthSource, AutoCloseable {
    override suspend fun collect(request: DevelopmentHealthRequest) = source.collect(request).also(observe)
    override fun close() = runtime.close()
}
