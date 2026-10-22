package com.gtrainer

import java.sql.Connection

/** SQL only. The existing HistoryStore removal transaction owns revocation, purge and queue clearing. */
internal class ImportedEvidenceRemovalStore(private val connection: Connection) {
    fun purgeDerivedCopies() {
        AdaptationReasonStore(connection).redact()
        PersonalSignalStateStore(connection).invalidateProofs()
        connection.createStatement().use { statement ->
            // Revoke publication BEFORE the binding's cascade. Never release unresolved resource occupancy.
            statement.executeUpdate("UPDATE personal_review_runtime_claims SET phase='STOPPING',publication_allowed=0 " +
                "WHERE phase<>'STOP_CONFIRMED' AND execution_id IN (SELECT execution_id FROM scoped_execution_bindings WHERE source_snapshot_id IS NOT NULL)")
            // All factual review packets include imported-fact/coverage state, even when the selected range is empty.
            // Intake-only suggestion executions have no factual snapshot and are deliberately not targeted.
            statement.executeUpdate("DELETE FROM connected_review_snapshots")
            statement.executeUpdate("DELETE FROM scoped_evidence_snapshots")
        }
        SignalMutationStore(connection).remove()
        PlanReconciliationStore(connection).purgeImportDependentHistories()
        // Original typed testimony survives, but no reimport can silently reactivate its evidence binding.
        ActivityEffortStore(connection).detachImports()
    }
}
