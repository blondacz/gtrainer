package com.gtrainer

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalCheckpointContinuityTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val selector = PersonalSignalSelector("hrv", "ms", "synthetic", "fixture", "rmssd", "overnight")
    private val config = PersonalSignalConfiguration("UTC", selector, PersonalSignalBaseline(1, 1, 1.0, 1), "low", "absolute_limit", 10.0, 15.0, 1, 1, 3600)
    private val rule = PersonalSignalRule(id, 1, true, config, "2020-06-01T00:00:00Z")
    private val at = Instant.parse("2020-06-04T12:00:00Z")
    private val identity = selector.desiredIdentity()
    private fun sample(ident: ScopedSignalIdentity = identity, date: String = "2020-06-03", value: Double = 5.0,
                       evidence: String = "sample", received: String? = null, revision: Long? = null) =
        ScopedSignalSample(ident, date, value, evidence, received, revision)
    private fun binding(samples: List<ScopedSignalSample> = emptyList()) =
        SignalCheckpointContinuity.bind(selector, "2020-06-01", "2020-06-03", samples, at)
    private fun state(source: Long = 1) = PersonalSignalState(id, 1, "2020-06-03", PersonalSignalEpisode(), PersonalSignalEpisode(), source)
    private fun checkpoint(b: SignalCandidateBinding = binding(), s: PersonalSignalState = state()) =
        SignalReplayCheckpoint(s, null, b, 0, 0)
    private fun coverage(after: Long = 0, through: Long = after, rows: List<SignalEvidenceMutation> = emptyList(),
                         removal: Long = 0, expected: Long = removal, oldest: Long? = if (through == after) through else after + 1) =
        SignalMutationCoverage(after, through, oldest, rows, removal, expected)
    private fun validated(cp: SignalReplayCheckpoint = checkpoint(), r: PersonalSignalRule = rule, source: Long = 2,
                          c: SignalMutationCoverage = coverage(), b: SignalCandidateBinding = cp.binding) =
        SignalCheckpointContinuity.validate(cp, r, source, c, b, at)

    @Test fun `candidate binding is order stable multiplicity preserving and scoped only by field and source`() {
        val incompatible = identity.copy(method = "other", verification = "unverified")
        val other = identity.copy(intermediary = "other")
        val candidates = listOf(sample(), sample(incompatible, evidence = "bad"), sample(other, evidence = "ignored"))
        assertEquals(binding(candidates), binding(candidates.reversed()))
        assertNotEquals(binding(candidates), binding(candidates + candidates.first()))
        assertEquals(binding(candidates.take(2)), binding(candidates))
    }

    @Test fun `empty watch binds absence and rejects excess watch or sample budget`() {
        assertNotEquals(binding(), SignalCheckpointContinuity.bind(selector, "2020-06-01", "2020-06-03", listOf(sample()), at))
        assertFailsWith<IllegalArgumentException> { SignalCheckpointContinuity.bind(selector, "2018-06-01", "2020-06-03", emptyList(), at) }
        assertFailsWith<IllegalArgumentException> { SignalCheckpointContinuity.bind(selector, "2020-06-01", "2020-06-03", List(50_001) { sample(evidence = "x$it") }, at) }
        assertFailsWith<IllegalArgumentException> { binding(listOf(sample(evidence = "x".repeat(8*1024*1024)))) }
    }

    @Test fun `binding covers candidate revision receipt time value and date semantics`() {
        val base = sample()
        val variants = listOf(base.copy(revision = 2), base.copy(receivedUtc = "2020-06-04T11:00:00Z"),
            base.copy(value = 6.0), base.copy(date = "2020-06-02"))
        variants.forEach { assertNotEquals(binding(listOf(base)), binding(listOf(it))) }
        assertFailsWith<IllegalArgumentException> { binding(listOf(base.copy(receivedUtc = "2020-06-04T13:00:00Z"))) }
        assertFailsWith<IllegalArgumentException> { binding(listOf(base.copy(value = Double.POSITIVE_INFINITY))) }
    }

    @Test fun `bounds admit 732 dates and refuse 733 or nonfinite future candidates`() {
        assertEquals(selector, SignalCheckpointContinuity.bind(selector, "2020-06-01", "2022-06-02", emptyList(), at).selector)
        assertFailsWith<IllegalArgumentException> { bindingFor("2020-05-31", "2022-06-02") }
        assertFailsWith<IllegalArgumentException> { binding(listOf(sample(value = Double.NaN))) }
    }

    private fun bindingFor(oldest: String, newest: String) = SignalCheckpointContinuity.bind(selector, oldest, newest, emptyList(), at)

    @Test fun `complete unchanged causal watch validates prior copy across unrelated global revision`() {
        val prior = state(1)
        val result = validated(checkpoint(s = prior))
        assertEquals("validated", result.status)
        assertEquals(prior.copy(sourceRevision = 2), result.state)
        assertNotSameState(prior, result.state)
        assertEquals("needs_reevaluation", validated(checkpoint(s = prior), source = 0).status)
    }

    private fun assertNotSameState(a: PersonalSignalState, b: PersonalSignalState?) { assertTrue(a !== b) }

    @Test fun `coverage gap epoch change policy edit and changed binding refuse without fallback`() {
        val cp = checkpoint()
        val cases = listOf(
            validated(cp, c = coverage(after = 0, through = 2, oldest = 2)),
            validated(cp, c = coverage(removal = 1, expected = 0)),
            validated(cp, r = rule.copy(revision = 2)),
            validated(cp, b = binding(listOf(sample())))
        )
        cases.forEach { assertEquals("needs_reevaluation", it.status); assertNull(it.state) }
        assertEquals("needs_reevaluation", validated(cp, c = coverage(after = 0, through = 0, rows = listOf(
            SignalEvidenceMutation(1, "synthetic", "a".repeat(64), null, "2020-06-03", listOf("hrv"))
        ))).status)
    }

    @Test fun `relevant old or new date mutation invalidates while unrelated field source date remains valid`() {
        fun mutation(source: String, old: String?, new: String, field: String) =
            SignalEvidenceMutation(1, source, "a".repeat(64), old, new, listOf(field))
        val cp = checkpoint()
        val unrelated = listOf(mutation("other", null, "2020-06-03", "hrv"),
            mutation("synthetic", null, "2020-06-03", "sleepSecs"),
            mutation("synthetic", "2020-05-31", "2020-05-31", "hrv"))
        unrelated.forEach { m ->
            val c = coverage(after = 0, through = 1, rows = listOf(m))
            assertEquals("validated", validated(cp, source = 1, c = c).status)
        }
        for (m in listOf(mutation("synthetic", "2020-06-02", "2020-05-31", "hrv"),
                mutation("synthetic", "2020-05-31", "2020-06-02", "hrv"))) {
            assertEquals("needs_reevaluation", validated(cp, c = coverage(after = 0, through = 1, rows = listOf(m))).status)
        }
    }

    @Test fun `checkpoint creation requires anchor for known state and valid interval`() {
        assertFailsWith<IllegalArgumentException> { checkpoint(s = state().copy(projected = PersonalSignalEpisode(true))) }
        assertFailsWith<IllegalArgumentException> { SignalReplayCheckpoint(state(), null, bindingFor("2020-06-01", "2020-06-02"), 0, 0) }
        val anchor=SignalResetAnchor("2020-06-02","2020-06-02","UTC")
        val known=PersonalSignalEpisode(known=true)
        val supported=SignalReplayCheckpoint(state().copy(beforeDay=known,projected=known),anchor,binding(),0,0)
        assertEquals("validated",validated(supported).status)
        assertEquals("needs_reevaluation",validated(supported.copy(binding=bindingFor("2020-06-02","2020-06-03")),
            b=bindingFor("2020-06-02","2020-06-03")).status) // Anchor's baseline is outside the watched support.
    }

    @Test fun `admission highwater preserves maximum cooldown and reset begins after admitted civil day`() {
        val water = SignalAdmissionHighWater(id, 1, "2020-06-02T12:00:00Z", "2020-06-05T00:00:00Z", "2020-06-02", "UTC")
        assertEquals(Instant.parse("2020-06-05T00:00:00Z"), water.cooldownUntil(config))
        assertEquals(Instant.parse("2020-06-06T12:00:00Z"),water.cooldownUntil(config.copy(cooldownSeconds=345600)))
        assertTrue(water.resetCanReanchor(SignalResetAnchor("2020-06-03", "2020-06-04", "UTC")))
        assertEquals(false, water.resetCanReanchor(SignalResetAnchor("2020-06-02", "2020-06-03", "Pacific/Kiritimati")))
        assertEquals(false, water.resetCanReanchor(SignalResetAnchor("2020-06-02", "2020-06-03", "UTC")))
    }

    @Test fun `reset reanchor requires verified full persistence after prior admission including across zones`() {
        val persistent=rule.copy(configuration=config.copy(resetPersistenceDays=2))
        val samples=(1..4).map { day -> sample(date="2020-06-0$day",value=20.0,evidence="sample-$day") }
        val report=PersonalSignalRules.evaluate(persistent,PersonalSignalQuery("2020-06-02","2020-06-04"),at,samples)
        assertEquals(SignalResetAnchor("2020-06-03","2020-06-04","UTC"),SignalCheckpointContinuity.resetAnchor(report,null))
        val prior=SignalAdmissionHighWater(id,1,"2020-06-02T12:00:00Z","2020-06-05T00:00:00Z","2020-06-02","UTC")
        assertEquals("2020-06-03",SignalCheckpointContinuity.resetAnchor(report,prior)!!.resetStartedOn)
        val later=prior.copy(admittedUtc="2020-06-03T12:00:00Z",evaluatedOn="2020-06-03")
        assertNull(SignalCheckpointContinuity.resetAnchor(report,later))
        val broken=PersonalSignalRules.evaluate(persistent,report.query,at,samples.filter { it.date!="2020-06-03" })
        assertNull(SignalCheckpointContinuity.resetAnchor(broken,prior))
        val unverified=samples.map { it.copy(identity=it.identity.copy(verification="unknown")) }
        assertNull(SignalCheckpointContinuity.resetAnchor(PersonalSignalRules.evaluate(persistent,report.query,at,unverified),null))
    }
}
