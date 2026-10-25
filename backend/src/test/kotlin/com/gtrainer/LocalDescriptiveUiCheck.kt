package com.gtrainer

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/** Test-classpath only: normal application/routes with invented records and an in-process fake provider.
 * No environment credential/model loading, test HTTP routes, runtime processes or personal storage. */
object LocalDescriptiveUiCheck {
    @JvmStatic fun main(args: Array<String>)=runBlocking {
        require(args.size==2 && args[1] in setOf("first","restart"))
        val root=Path.of(args[0]).toAbsolutePath().normalize()
        require(root.fileName.toString().startsWith("synthetic-local-ui-") && Files.isDirectory(root,NOFOLLOW_LINKS))
        val owner=root.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        require(Files.getOwner(root,NOFOLLOW_LINKS)==owner && Files.getPosixFilePermissions(root,NOFOLLOW_LINKS)==PosixFilePermissions.fromString("rwx------"))
        val marker=root.resolve("synthetic-only")
        require(Files.isRegularFile(marker,NOFOLLOW_LINKS) && Files.readString(marker)=="synthetic-only-descriptive-ui-check-v1\n")
        val phase=args[1]
        val data=root.resolve("data")
        if(phase=="first") Files.createDirectory(data,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        else {
            require(Files.isRegularFile(root.resolve("first-terminal.json"),NOFOLLOW_LINKS))
            val previous=Json.parseToJsonElement(Files.readString(root.resolve("first-owner.json"))).jsonObject
            val pid=previous.getValue("pid").jsonPrimitive.long
            require(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true))
            require(Files.isRegularFile(data.resolve("history.db"),NOFOLLOW_LINKS))
        }
        val fixture=DescriptiveUiFixture(data)
        val origin="http://127.0.0.1:8080"
        val server=embeddedServer(CIO,host="127.0.0.1",port=8080) {
            module(auth=SingleUserAuth(syntheticVerifier(),origin,false,false),history=fixture.history,
                reviews=ReviewInterpretationService(emptyList()),schedules=null,reviewQueue=null,
                ownerUat=fixture.gate,ownerUatExecution={ fixture.jobs })
        }
        val pid=ProcessHandle.current().pid()
        val deadline=Instant.now().plusSeconds(1200)
        fun write(name: String,body: JsonObject) {
            val path=root.resolve(name)
            Files.writeString(path,body.toString()+"\n",CREATE_NEW)
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"))
        }
        write("$phase-owner.json",buildJsonObject {
            put("pid",pid);put("phase",phase);put("syntheticOnly",true);put("port",8080);put("deadlineUtc",deadline.toString())
        })
        try {
            server.start(wait=false)
            println("Synthetic normal-app UI check ready; phase=$phase; fake provider only; no source/model credentials.")
            // Test supervision only, not an application lifetime policy or installed owner service.
            while(Instant.now()<deadline && !Files.exists(root.resolve("stop-$phase"))) delay(200)
        } finally {
            val providerCalls=fixture.calls.get()
            val sourceCalls=fixture.source.calls
            val unresolved=fixture.store.unresolvedPersonalReviews().size
            server.stop(500,3000)
            fixture.close()
            write("$phase-terminal.json",buildJsonObject {
                put("phase",phase);put("syntheticOnly",true);put("providerKind","in_process_fake")
                put("fakeProviderCalls",providerCalls);put("sourceCalls",sourceCalls)
                put("unresolvedClaimsBeforeStop",unresolved);put("endedUtc",Instant.now().toString())
            })
        }
    }
}
