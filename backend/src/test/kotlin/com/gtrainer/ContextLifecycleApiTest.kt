package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class ContextLifecycleApiTest {
    @Test fun `lifecycle reads include historical context and writes require exact authenticated revision`() {
        val dir=Files.createTempDirectory("synthetic-context-lifecycle-api-")
        val store=HistoryStore(dir.resolve("history.db"))
        val source=SyntheticSource()
        val at=Instant.parse("2026-10-15T12:00:00Z")
        val today=LocalDate.parse("2026-10-15")
        val history=HistoryService(store,source,Clock.fixed(at,ZoneOffset.UTC))
        val model=SyntheticReviewModel()
        val reviews=ReviewInterpretationService(listOf(model))
        val illness=store.createContext(AthleteContextRequest("symptom","user_report","athlete","2026-10-01",
            applicableUntil="2026-10-10",content="synthetic improving illness"))
        val other=store.createContext(AthleteContextRequest("note","user_report","athlete","2026-10-01",content="unrelated note"))
        val evidence=AnalysisInput(1,"a".repeat(64),at.toString(),null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        fun snapshot(id: String, contexts: List<AthleteContext>)=store.createConnectedReviewSnapshot(ConnectedReviewBinding(id,evidence,
            retrieveAthleteContext(contexts,ContextRetrievalQuery(LocalDate.parse("2026-10-05"),hardPacketLimit=10)),
            "2026-10-01","2026-10-05",null,ConnectedReviewProviderSelection("synthetic","synthetic","local"),InterpretationContractV1.PROFILE),at)
        val dependent=snapshot("lifecycle-dependent",listOf(illness))
        val unrelated=snapshot("lifecycle-unrelated",listOf(other))
        val origin="http://127.0.0.1:8080"
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),history,reviews,schedules=null,reviewQueue=null) }
                assertEquals(HttpStatusCode.Unauthorized,client.get("/api/context-lifecycle").status)
                assertEquals(HttpStatusCode.Unauthorized,client.put("/api/contexts/${illness.contextId}/lifecycle").status)
                val login=client.post("/api/login") { header(HttpHeaders.Origin,origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun send(method: HttpMethod,path: String,body: Any?=null,token: String?=csrf,from: String=origin)=client.request(path) {
                    this.method=method; header(HttpHeaders.Cookie,cookie); header(HttpHeaders.Origin,from)
                    token?.let { header("X-CSRF-Token",it) }
                    if(body!=null) { contentType(ContentType.Application.Json); setBody(body) }
                }
                val before=store.contextRevisions(illness.contextId)
                repeat(2) {
                    val result=send(HttpMethod.Get,"/api/context-lifecycle")
                    assertEquals("no-store",result.headers[HttpHeaders.CacheControl])
                    val report=Json.decodeFromString<ContextLifecycleReport>(result.bodyAsText())
                    assertEquals(today.toString(),report.evaluatedOn)
                    assertTrue(report.interpretationBasis.contains("not_point_in_time_replay"))
                    val item=report.items.single { it.context.contextId==illness.contextId }
                    assertEquals("historical",item.state)
                    assertEquals("unresolved",item.lifecycle.resolution)
                    assertTrue(item.needsReview)
                }
                assertEquals(before,store.contextRevisions(illness.contextId))
                assertEquals(dependent,store.connectedReviewStatus(dependent.snapshotId))
                val path="/api/contexts/${illness.contextId}/lifecycle"
                val update=Json.encodeToString(ContextLifecycleUpdate(1,"unresolved","2026-10-15"))
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Put,path,update,token=null).status)
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Put,path,update,from="https://untrusted.invalid").status)
                val changed=send(HttpMethod.Put,path,update)
                assertEquals(HttpStatusCode.OK,changed.status)
                val item=Json.decodeFromString<ContextLifecycleView>(changed.bodyAsText())
                assertEquals(illness.copy(revision=2),item.context)
                assertTrue(item.needsReview)
                assertEquals("unresolved",item.lifecycle.resolution)
                assertEquals("STALE",store.connectedReviewStatus(dependent.snapshotId).state)
                assertEquals(unrelated,store.connectedReviewStatus(unrelated.snapshotId))
                val query=ScopedContextRetrievalQuery(today,listOf(ContextRetrievalTarget(today,today)),
                    listOf(ContextRetrievalTarget(today.plusDays(1),today.plusDays(1))),mandatoryLimit=10)
                val pending=history.contextLifecycle().retrieve(query)
                assertTrue(pending.evidence.mandatoryClarifications.isEmpty())
                assertEquals(listOf(illness.contextId),pending.amendments.mandatoryClarifications.map { it.context.contextId })
                assertTrue(pending.amendments.blocked)
                assertEquals(HttpStatusCode.Conflict,send(HttpMethod.Put,path,update).status)
                val resolution=Json.encodeToString(ContextLifecycleUpdate(2,"resolved",null))
                val resolved=send(HttpMethod.Put,path,resolution)
                assertEquals(HttpStatusCode.OK,resolved.status)
                val resolvedItem=Json.decodeFromString<ContextLifecycleView>(resolved.bodyAsText())
                assertEquals("resolved",resolvedItem.state)
                assertFalse(resolvedItem.needsReview)
                assertEquals(illness.copy(revision=3),resolvedItem.context)
                assertFalse(history.contextLifecycle().retrieve(query).amendments.blocked)
                // V1 retrieval still interprets the original observation/applicability fields,
                // independently of new-profile lifecycle metadata.
                assertTrue(store.retrieveContexts(ContextRetrievalQuery(LocalDate.parse("2026-10-05"),hardPacketLimit=10))
                    .optionalContexts.any { it.contextId==illness.contextId && it.revision==3 })
                for(body in listOf(update.dropLast(1)+",\"resolution\":\"resolved\"}",
                    update.dropLast(1)+",\"content\":\"synthetic private marker\"}",
                    update.replace("unresolved","recovered"),update.replace("2026-10-15","2026-02-30"),
                    """{"expectedContextRevision":3,"resolution":"unresolved"}""")) {
                    val rejected=send(HttpMethod.Put,path,body)
                    assertEquals(HttpStatusCode.BadRequest,rejected.status)
                    assertFalse(rejected.bodyAsText().contains("synthetic"))
                }
                assertEquals(HttpStatusCode.PayloadTooLarge,send(HttpMethod.Put,path,"x".repeat(1025)).status)
                assertEquals(HttpStatusCode.BadRequest,send(HttpMethod.Put,path,byteArrayOf(0xc3.toByte(),0x28)).status)
                assertEquals(HttpStatusCode.BadRequest,send(HttpMethod.Get,"/api/context-lifecycle?asOf=2026-02-30").status)
                assertEquals(HttpStatusCode.BadRequest,send(HttpMethod.Get,"/api/context-lifecycle?asOf=2026-10-15&asOf=2026-10-15").status)
                assertEquals(HttpStatusCode.NotFound,send(HttpMethod.Put,"/api/contexts/00000000-0000-4000-8000-000000000000/lifecycle",resolution).status)
                assertEquals(3,store.contextRevisions(illness.contextId).size)
                assertEquals(0,source.calls)
                assertTrue(model.packets.isEmpty())
            }
        } finally {
            history.close(); reviews.close()
            Files.list(dir).use { files -> files.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir)
        }
    }
}
