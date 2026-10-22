package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.encodeToString
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityEffortApiTest {
    @Test fun `effort endpoints enforce private auth csrf exact query and bounded strict bodies before service calls`() {
        val dir=Files.createTempDirectory("activity-effort-api-");val store=HistoryStore(dir.resolve("history.db"))
        val origin="http://127.0.0.1:8080";val clock=Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"),ZoneOffset.UTC)
        val source=SyntheticSource();val history=HistoryService(store,source,clock);val plans=PlanService(store,clock)
        val scale=EffortScale("33333333-3333-4333-8333-333333333333","Synthetic effort",1.0,10.0,1.0,true)
        plans.create(AuthoredPlanRequest("Synthetic",PlanAuthor("user","Synthetic"),listOf(PlannedSession(
            "22222222-2222-4222-8222-222222222222",PlanAuthor("user","Synthetic"),"Run","Synthetic",
            PlanSchedule("date_only",date="2026-10-15",timeZone="UTC"),listOf(PlannedTarget("reported_effort","scale_points","reported",4.0,6.0,scale))))))
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),history,ReviewInterpretationService(emptyList()),
                schedules=null,reviewQueue=null,plans=plans) }
            val getPath="/api/plans/effort-reports?activityId=${"a".repeat(64)}&scaleId=33333333-3333-4333-8333-333333333333"
            val endpoint="/api/plans/effort-reports"
            assertEquals(HttpStatusCode.Unauthorized,client.get(getPath).status)
            assertEquals(HttpStatusCode.Unauthorized,client.post(endpoint).status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun get(url:String)=client.get(url){header(HttpHeaders.Cookie,cookie)}
            suspend fun post(body:String,token:String?=csrf,from:String=origin)=client.post(endpoint) {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,from);token?.let { header("X-CSRF-Token",it) }
                contentType(ContentType.Application.Json);setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest,get("$getPath&extra=1").status)
            assertEquals(HttpStatusCode.BadRequest,get(getPath.replace("activityId=","activityId=&activityId=")).status)
            assertEquals(HttpStatusCode.Forbidden,post("{}",null).status)
            assertEquals(HttpStatusCode.Forbidden,post("{}",from="https://hostile.invalid").status)
            assertEquals(HttpStatusCode.BadRequest,post("{}").status)
            assertEquals(HttpStatusCode.BadRequest,post("{}&unknown=1").status)
            assertEquals(HttpStatusCode.PayloadTooLarge,post(" ".repeat(8193)).status)
            val read=get(getPath);assertEquals(HttpStatusCode.OK,read.status)
            assertEquals("no-store",read.headers[HttpHeaders.CacheControl])
            assertEquals(0,source.calls)
            assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
            val record=syntheticActivity().copy(startLocal="2026-10-15T10:00:00",startInstant="2026-10-15T10:00:00Z",timeZone=null,utcOffset=null,timeContext="upstream_instant")
            store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record)),clock.instant())
            val identity=store.capturePlanReconciliation(clock.instant()).activities.single()
            val input=ActivityEffortRequest(java.util.UUID.randomUUID().toString(),identity.activityId,scale.scaleId,null,0,0,identity.revision,identity.recordSha256,
                "report",7.0,"2026-10-15","Synthetic runner")
            val written=post(Json.encodeToString(input));assertEquals(HttpStatusCode.OK,written.status)
            assertEquals("no-store",written.headers[HttpHeaders.CacheControl])
            val report=Json.decodeFromString<ActivityEffortReceipt>(written.bodyAsText()).report
            assertEquals(7.0,report.value);assertEquals("authenticated_user",report.recordedBy)
            assertEquals(HttpStatusCode.Conflict,post(Json.encodeToString(input.copy(value=6.0))).status)
            assertEquals(HttpStatusCode.BadRequest,post(Json.encodeToString(input).replace("\"expectedActivityRevision\":${identity.revision},","")).status)
            assertTrue(store.reviewScheduleState().queue.pending.isEmpty());assertEquals(0,source.calls)
        } } finally { store.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}
