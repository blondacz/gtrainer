package com.gtrainer

import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Properties

internal class DevelopmentReviewStorageFailure : IllegalStateException("development_storage_unavailable")
internal class DevelopmentReviewCapacityReached : IllegalStateException("development_capacity_reached")

/** Sole JDBC/resource boundary; repository holds its monitor for every operation and close. */
internal class DevelopmentReviewDatabase(path: Path) : AutoCloseable {
    private val connection = storageOperation { open(path) }

    fun <T> transaction(block: () -> T): T = storageOperation {
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            result
        } catch (error: Exception) {
            // Preserve only the already-safe error if rollback itself fails.
            runCatching { connection.rollback() }
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    fun update(sql: String, vararg values: String?): Int = storageOperation {
        connection.prepareStatement(sql).use { statement ->
            statement.bind(values)
            statement.executeUpdate()
        }
    }

    fun <T> query(sql: String, vararg values: String?, read: (ResultSet) -> T): T = storageOperation {
        connection.prepareStatement(sql).use { statement ->
            statement.bind(values)
            statement.executeQuery().use(read)
        }
    }

    private fun PreparedStatement.bind(values: Array<out String?>) {
        values.forEachIndexed { index, value -> setString(index + 1, value) }
    }

    private fun open(path: Path): Connection {
        require(path.isAbsolute && path.parent != null)
        val existed = Files.exists(path, NOFOLLOW_LINKS)
        if (existed) validateExistingReadOnly(path)
        preparePermissions(path, existed)
        val properties = Properties().apply {
            setProperty("transaction_mode", "IMMEDIATE")
            setProperty("busy_timeout", "2000")
        }
        val result = DriverManager.getConnection("jdbc:sqlite:file:${path.toUri().rawPath}?mode=rw", properties)
        try {
            if (!existed) initialize(result)
            initializeOwnership(result)
            result.createStatement().use {
                it.execute(DevelopmentReviewSql.JOURNAL_MODE)
                it.execute(DevelopmentReviewSql.FOREIGN_KEYS)
            }
            verifyOwnerOnly(path)
            return result
        } catch (error: Exception) {
            runCatching { result.close() }
            throw error
        }
    }

    private fun initializeOwnership(connection: Connection) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement -> DevelopmentReviewOwnershipSql.INITIALIZE.forEach(statement::execute) }
            connection.createStatement().use { it.execute(DevelopmentReviewRuntimeSql.CREATE) }
            connection.createStatement().use { statement -> DevelopmentReviewQueueSql.INITIALIZE.forEach(statement::execute) }
            connection.commit()
        } catch (error: Exception) {
            runCatching { connection.rollback() }
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun initialize(connection: Connection) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement -> DevelopmentReviewSql.CREATE_SCHEMA.forEach(statement::execute) }
            connection.commit()
        } catch (error: Exception) {
            runCatching { connection.rollback() }
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun validateExistingReadOnly(path: Path) {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
        // immutable avoids SQLite creating/checkpointing WAL auxiliaries for an unrelated target.
        DriverManager.getConnection("jdbc:sqlite:file:${path.toUri().rawPath}?mode=ro&immutable=1").use { existing ->
            existing.createStatement().use { statement ->
                statement.executeQuery(DevelopmentReviewSql.READ_APP_ID).use { rows ->
                    require(rows.next() && rows.getInt(1) == DevelopmentReviewSql.APPLICATION_ID)
                }
                statement.executeQuery(DevelopmentReviewSql.MARKER_QUERY).use { rows ->
                    require(rows.next() && rows.getString(1) == DevelopmentReviewSql.SCHEMA)
                }
            }
        }
    }

    private fun preparePermissions(path: Path, existed: Boolean) {
        if (!Files.exists(path.parent, NOFOLLOW_LINKS)) {
            Files.createDirectories(path.parent, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY))
        }
        require(Files.isDirectory(path.parent, NOFOLLOW_LINKS))
        verifyOwnerOnly(path.parent)
        if (!existed) Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_FILE))
        verifyOwnerOnly(path)
    }

    private fun verifyOwnerOnly(path: Path) {
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        require(permissions.all { it.name.startsWith("OWNER_") })
        val expectedOwner = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        require(Files.getOwner(path, NOFOLLOW_LINKS) == expectedOwner)
    }

    override fun close() = storageOperation { connection.close() }

    private companion object {
        val OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------")
        val OWNER_FILE = PosixFilePermissions.fromString("rw-------")
    }
}

internal fun <T> storageOperation(block: () -> T): T = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (missing: ConnectedReviewNotFound) { throw missing }
catch (capacity: DevelopmentReviewCapacityReached) { throw capacity }
catch (expired: DevelopmentReviewPreviewExpired) { throw expired }
catch (ownership: DevelopmentReviewOwnershipFailure) { throw ownership }
catch (_: Exception) { throw DevelopmentReviewStorageFailure() }
