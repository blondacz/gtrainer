package com.gtrainer

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Offline bridge for private replay captures. Never invokes runtime or provider code. */
class ScopedCaptureValidationTest {
    @Serializable private data class Request(val caseId:String,val attempt:Int,val raw:String)
    @Serializable private data class RequestDocument(val version:String,val requests:List<Request>)
    @Serializable private data class Result(val caseId:String,val attempt:Int,val status:String)
    @Serializable private data class ResultDocument(val version:String,val results:List<Result>)
    private val json=Json { encodeDefaults=true;ignoreUnknownKeys=false;isLenient=false;coerceInputValues=false }
    private val version="scoped-capture-validation-v1"
    private val maxInput=2*1024*1024

    @Test fun validatesThreeProfilesAndRejectsMalformedDraftsAndExplanationTampering() {
        val descriptive=ScopedEvaluationFixtures.descriptive("d01")
        val dp=json.decodeFromString<ScopedDescriptivePacketV1>(descriptive.exactPrompt.userPacketJson)
        val ref=dp.references.first()
        val goodD=ScopedDescriptiveContractV1.encodeDraft(ScopedDescriptiveDraftV1(ScopedDescriptiveContractV1.PROFILE,
            dp.evidence.request.selection.mode,dp.evidence.request.selection.intent,
            listOf(ScopedDescriptiveItem("observation","Available evidence is limited.",listOf(ref),"unknown")),emptyList()))
        assertTrue(validate("d01",goodD))
        assertFalse(validate("d01","{}"))

        val intake=ScopedEvaluationFixtures.intake("i01")
        val ip=json.decodeFromString<ContextIntakeSuggestionPacketV1>(intake.exactPrompt.userPacketJson)
        val goodI=ContextIntakeSuggestionContractV1.encodeDraft(ContextIntakeSuggestionDraftV1(
            ContextIntakeSuggestionContractV1.PROFILE,ip.intake.intakeId,ip.intake.revision,emptyList(),emptyList()))
        assertTrue(validate("i01",goodI))
        assertFalse(validate("i01","{}"))

        val amendment=ScopedEvaluationFixtures.amendment("a01")
        val ap=json.decodeFromString<AmendmentSelectionPacketV1>(amendment.exactPrompt.userPacketJson)
        val candidate=ap.candidates.first()
        val choice=ap.explanationChoices.single { it.text.contains(candidate.candidateId) }
        val goodA=AmendmentSelectionContractV1.encodeDraft(AmendmentSelectionDraftV1(
            AmendmentSelectionContractV1.PROFILE,ap.candidateSetSha256,"select",listOf(candidate.candidateId),listOf(choice)))
        assertTrue(validate("a01",goodA))
        val changed=json.parseToJsonElement(goodA).jsonObject.toMutableMap()
        val originalChoices=changed.getValue("explanations") as kotlinx.serialization.json.JsonArray
        val firstChoice=originalChoices.single().jsonObject.toMutableMap()
        firstChoice["text"]=kotlinx.serialization.json.JsonPrimitive("Altered explanation")
        changed["explanations"]=kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject(firstChoice)))
        assertFalse(validate("a01",JsonObjectSafe(changed)))

        val collision=ScopedEvaluationFixtures.amendment("a06")
        val cp=json.decodeFromString<AmendmentSelectionPacketV1>(collision.exactPrompt.userPacketJson)
        val all=AmendmentSelectionContractV1.encodeDraft(AmendmentSelectionDraftV1(
            AmendmentSelectionContractV1.PROFILE,cp.candidateSetSha256,"select",cp.candidates.map { it.candidateId },
            cp.candidates.map { c->cp.explanationChoices.single { it.text.contains(c.candidateId) } }))
        assertFalse(validate("a06",all))
    }

    @Test fun rejectsInvalidBridgeRequestsWithoutEchoingInput() {
        assertThrows(IllegalArgumentException::class.java) { process("""{"version":"scoped-capture-validation-v1","requests":[{"caseId":"d01","attempt":1,"raw":"x"},{"caseId":"d01","attempt":1,"raw":"y"}]}""") }
        assertThrows(IllegalArgumentException::class.java) { process("""{"version":"scoped-capture-validation-v1","requests":[{"caseId":"nope","attempt":1,"raw":"x"}]}""") }
        assertThrows(IllegalArgumentException::class.java) { process("""{"version":"scoped-capture-validation-v1","requests":[{"caseId":"d01","attempt":3,"raw":"x"}]}""") }
        assertThrows(IllegalArgumentException::class.java) { process("""{"version":"scoped-capture-validation-v1","version":"scoped-capture-validation-v1","requests":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { process("""{"version":"scoped-capture-validation-v1","requests":[{"caseId":"d01","attempt":"1","raw":"x"}]}""") }
        val twoAttempts=process("""{"version":"scoped-capture-validation-v1","requests":[{"caseId":"d01","attempt":1,"raw":"{}"},{"caseId":"d01","attempt":2,"raw":"{}"}]}""")
        assertEquals(2,json.parseToJsonElement(twoAttempts).jsonObject.getValue("results").jsonArray.size)
        val batch=(1..12).flatMap { n->(1..2).map { attempt->Request("d${n.toString().padStart(2,'0')}",attempt," ".repeat(2000)) } }
        val large=json.encodeToString(RequestDocument(version,batch))
        assertTrue(large.length>32768)
        assertEquals(24,json.parseToJsonElement(process(large)).jsonObject.getValue("results").jsonArray.size)
    }

    @Test fun privateDirectoryBridgeWhenConfigured() {
        val configured=System.getenv("GTRAINER_SCOPED_CAPTURE_VALIDATION_DIR") ?: return
        try {
            val dir=Path.of(configured)
            requirePrivateDirectory(dir)
            val requestPath=dir.resolve("request.json"); val outputPath=dir.resolve("result.json")
            requireRegularNoLink(requestPath)
            require(Files.size(requestPath)<=maxInput)
            val bytes=Files.newInputStream(requestPath,LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxInput+1) }
            require(bytes.size<=maxInput)
            val input=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            verifyFrozenArtifacts()
            val output=process(input)
            FileChannel.open(outputPath,setOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
                val buffer=ByteBuffer.wrap(output.toByteArray(Charsets.UTF_8))
                while(buffer.hasRemaining())channel.write(buffer)
                channel.force(true)
            }
        } catch (_:Exception) { throw IllegalArgumentException("Scoped capture validation failed") }
    }

    private fun process(source:String):String {
        require(source.toByteArray(Charsets.UTF_8).size<=maxInput)
        rejectDuplicateKeys(source)
        val element=json.parseToJsonElement(source)
        val envelope=element.jsonObject
        require(envelope.keys==setOf("version","requests") && envelope.getValue("version").jsonPrimitive.isString)
        val request=RequestDocument(envelope.getValue("version").jsonPrimitive.content,envelope.getValue("requests").jsonArray.map { row ->
            val item=row.jsonObject
            require(item.keys==setOf("caseId","attempt","raw"))
            require(item.getValue("caseId").jsonPrimitive.isString && item.getValue("raw").jsonPrimitive.isString)
            val attempt=item.getValue("attempt").jsonPrimitive
            require(!attempt.isString)
            Request(item.getValue("caseId").jsonPrimitive.content,requireNotNull(attempt.intOrNull),item.getValue("raw").jsonPrimitive.content)
        })
        require(request.version==version && request.requests.size<=56)
        require(request.requests.map { it.caseId to it.attempt }.distinct().size==request.requests.size)
        val results=request.requests.map { item ->
            require(knownCase(item.caseId) && item.attempt in 1..2 && item.raw.toByteArray(Charsets.UTF_8).size<=12*1024)
            Result(item.caseId,item.attempt,if(validate(item.caseId,item.raw))"accepted" else "rejected")
        }
        return json.encodeToString(ResultDocument(version,results))
    }

    // The model parser's 32 KiB envelope limit is intentionally not enlarged for batch tooling.
    // Limit nesting before JSON parsing and reject duplicate decoded keys in the larger envelope.
    private fun rejectDuplicateKeys(source:String) {
        val objects=mutableListOf<MutableSet<String>?>()
        var index=0
        while(index<source.length) {
            when(source[index]) {
                '{' -> { objects.add(mutableSetOf());require(objects.size<=16) }
                '[' -> { objects.add(null);require(objects.size<=16) }
                '}',']' -> objects.removeLast()
                '"' -> {
                    val start=index++
                    while(source[index]!='"') { if(source[index]=='\\')index++;index++ }
                    val end=++index
                    while(index<source.length && source[index].isWhitespace())index++
                    if(index<source.length && source[index]==':') {
                        val key=json.decodeFromString<String>(source.substring(start,end))
                        require(requireNotNull(objects.last()).add(key))
                    }
                    continue
                }
            }
            index++
        }
    }

    private fun validate(caseId:String,raw:String):Boolean=try {
        when {
            caseId.matches(Regex("d(0[1-9]|1[0-2])")) -> {
                val packet=json.decodeFromString<ScopedDescriptivePacketV1>(ScopedEvaluationFixtures.descriptive(caseId).exactPrompt.userPacketJson)
                ScopedDescriptiveContractV1.decodeDraft(raw,packet);true
            }
            caseId.matches(Regex("i(0[1-8])")) -> {
                val packet=json.decodeFromString<ContextIntakeSuggestionPacketV1>(ScopedEvaluationFixtures.intake(caseId).exactPrompt.userPacketJson)
                ContextIntakeSuggestionContractV1.decodeDraft(raw,packet);true
            }
            caseId.matches(Regex("a(0[1-8])")) -> {
                val input=ScopedEvaluationFixtures.amendmentInput(caseId)
                val packet=json.decodeFromString<AmendmentSelectionPacketV1>(ScopedEvaluationFixtures.amendment(caseId).exactPrompt.userPacketJson)
                val draft=AmendmentSelectionContractV1.decodeDraft(raw,packet)
                val selected=draft.candidateIds.map { id->packet.candidates.single { it.candidateId==id } }
                PlanAmendmentPolicy.checkWholeResult(input,ScopedEvaluationFixtures.at,selected).isEmpty()
            }
            else -> false
        }
    } catch (_:Exception) { false }

    private fun verifyFrozenArtifacts() {
        val candidates=listOf(Path.of("../benchmarks/scoped-profiles/artifacts-v1.json"),Path.of("benchmarks/scoped-profiles/artifacts-v1.json"))
        val artifact=candidates.firstOrNull { Files.exists(it,LinkOption.NOFOLLOW_LINKS) } ?: throw IllegalArgumentException()
        require(!Files.isSymbolicLink(artifact) && Files.readAllBytes(artifact).contentEquals(ScopedEvaluationFixtures.bytes()))
    }
    private fun requirePrivateDirectory(path:Path) {
        val real=path.toAbsolutePath().normalize()
        val root=generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("backend/build.gradle.kts")) }.toRealPath()
        require(!real.startsWith(root))
        require(generateSequence(real) { it.parent }.none(Files::isSymbolicLink))
        require(!Files.isSymbolicLink(path) && Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))
        val perms=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS)
        require(perms==PosixFilePermissions.fromString("rwx------"))
    }
    private fun requireRegularNoLink(path:Path) {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
        require(Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS)==PosixFilePermissions.fromString("rw-------"))
        require((Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS) as Number).toInt()==1)
    }
    private fun knownCase(caseId:String)=caseId.matches(Regex("d(0[1-9]|1[0-2])")) ||
        caseId.matches(Regex("i(0[1-8])")) || caseId.matches(Regex("a(0[1-8])"))
    private fun JsonObjectSafe(values:Map<String,kotlinx.serialization.json.JsonElement>) =
        kotlinx.serialization.json.JsonObject(values).toString()
}
