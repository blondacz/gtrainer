package com.gtrainer

import java.sql.Connection
import java.sql.ResultSet

/** Named context columns/read/copy SQL shared by revision writers; never owns a transaction. */
internal class ContextRows(private val connection: Connection) {
    private val columns="context_id,revision,category,source_category,author_attribution,entered_by,observed_on," +
        "applicable_from,applicable_until,sport,activity_id,review_id,content,retired,restriction_kind,restriction_value,restriction_unit"
    private fun record(rows: ResultSet) = AthleteContext(rows.getString(1),rows.getInt(2),rows.getString(3),
        rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7),rows.getString(8),rows.getString(9),
        rows.getString(10),rows.getString(11),rows.getString(12),rows.getString(13),rows.getInt(14)!=0,
        rows.getString(15),rows.getString(16),rows.getString(17))

    fun latest(): List<AthleteContext> = connection.createStatement().use { s ->
        s.executeQuery("SELECT $columns FROM athlete_context WHERE (context_id,revision) IN " +
            "(SELECT context_id,max(revision) FROM athlete_context GROUP BY context_id) ORDER BY context_id LIMIT 1001").use { rows ->
            buildList { while (rows.next()) { require(size < 1000); add(record(rows)) } }
        }
    }

    fun history(id: String): List<AthleteContext> {
        validateContextId(id)
        return connection.prepareStatement("SELECT $columns FROM athlete_context WHERE context_id=? ORDER BY revision DESC LIMIT 1001").use {
            it.setString(1,id); it.executeQuery().use { rows -> buildList { while(rows.next()) { require(size < 1000); add(record(rows)) } } }
        }.also { if(it.isEmpty()) throw ContextNotFound() }
    }

    fun current(id: String): AthleteContext {
        validateContextId(id)
        return connection.prepareStatement("SELECT $columns FROM athlete_context WHERE context_id=? ORDER BY revision DESC LIMIT 1").use {
            it.setString(1,id); it.executeQuery().use { rows -> if(rows.next()) record(rows) else throw ContextNotFound() }
        }
    }

    fun revision(id: String, revision: Int): AthleteContext {
        validateContextId(id); require(revision in 1..1000)
        return connection.prepareStatement("SELECT $columns FROM athlete_context WHERE context_id=? AND revision=?").use {
            it.setString(1,id); it.setInt(2,revision); it.executeQuery().use { rows -> if(rows.next()) record(rows) else throw ContextNotFound() }
        }
    }

    fun requireCapacity() {
        val count=connection.createStatement().use { it.executeQuery("SELECT count(DISTINCT context_id) FROM athlete_context").use { rows -> rows.next(); rows.getInt(1) } }
        require(count<1000)
    }

    fun insert(id: String, revision: Int, request: AthleteContextRequest, retired: Boolean = false): AthleteContext {
        require(revision in 1..1000)
        connection.prepareStatement("INSERT INTO athlete_context($columns) VALUES(?,?,?,?,?,'authenticated_user',?,?,?,?,?,?,?,?,?,?,?)").use {
            it.setString(1,id); it.setInt(2,revision); it.setString(3,request.category); it.setString(4,request.sourceCategory)
            it.setString(5,request.authorAttribution); it.setString(6,request.observedOn); it.setString(7,request.applicableFrom)
            it.setString(8,request.applicableUntil); it.setString(9,request.sport); it.setString(10,request.activityId)
            it.setString(11,request.reviewId); it.setString(12,request.content); it.setInt(13,if(retired) 1 else 0)
            it.setString(14,request.restrictionKind); it.setString(15,request.restrictionValue); it.setString(16,request.restrictionUnit); it.executeUpdate()
        }
        return AthleteContext(id,revision,request.category,request.sourceCategory,request.authorAttribution,"authenticated_user",request.observedOn,
            request.applicableFrom,request.applicableUntil,request.sport,request.activityId,request.reviewId,request.content,retired,request.restrictionKind,request.restrictionValue,request.restrictionUnit)
    }

    /** Stored legacy unstructured restrictions remain editable; no request reconstruction or attribution replacement. */
    fun appendCopy(current: AthleteContext, retired: Boolean = current.retired): AthleteContext {
        require(current.revision in 1..999)
        val revision=current.revision+1
        connection.prepareStatement("INSERT INTO athlete_context($columns) SELECT context_id,?,category,source_category," +
            "author_attribution,entered_by,observed_on,applicable_from,applicable_until,sport,activity_id,review_id,content,?," +
            "restriction_kind,restriction_value,restriction_unit FROM athlete_context WHERE context_id=? AND revision=?").use {
            it.setInt(1,revision); it.setInt(2,if(retired) 1 else 0); it.setString(3,current.contextId); it.setInt(4,current.revision)
            check(it.executeUpdate()==1)
        }
        return current.copy(revision=revision,retired=retired)
    }
}
