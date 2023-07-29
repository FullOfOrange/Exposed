package org.jetbrains.exposed.spring

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.exposedLogger
import org.jetbrains.exposed.sql.statements.api.ExposedConnection
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionInterface
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

class SpringTransactionManager(
    dataSource: DataSource,
    databaseConfig: DatabaseConfig = DatabaseConfig { },
    private val showSql: Boolean = false,
    @Volatile override var defaultReadOnly: Boolean = databaseConfig.defaultReadOnly,
    @Volatile override var defaultRepetitionAttempts: Int = databaseConfig.defaultRepetitionAttempts,
    @Volatile override var defaultMinRepetitionDelay: Long = databaseConfig.defaultMinRepetitionDelay,
    @Volatile override var defaultMaxRepetitionDelay: Long = databaseConfig.defaultMaxRepetitionDelay
) : PlatformTransactionManager, TransactionManager {

    private val db = Database.connect(
        datasource = dataSource,
        databaseConfig = databaseConfig,
        manager = { this }
    )

    @Volatile
    override var defaultIsolationLevel: Int = -1
        get() {
            if (field == -1) {
                field = Database.getDefaultIsolationLevel(db)
            }
            return field
        }


    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        val outerTransactionManager = TransactionManager.manager
        val transactionStatus = ExposedTransactionStatus(outerTransactionManager)

        TransactionManager.resetCurrent(this)
        if (TransactionSynchronizationManager.hasResource(this)) {
            currentOrNull() ?: initTransaction()
        }

        return transactionStatus
    }

    override fun commit(status: TransactionStatus) {
        @Suppress("TooGenericExceptionCaught")
        try {
            currentOrNull()?.commit()
        } catch (e: Exception) {
            throw TransactionSystemException(e.message.orEmpty(), e)
        } finally {
            cleanup(status)
        }
    }

    override fun rollback(status: TransactionStatus) {
        @Suppress("TooGenericExceptionCaught")
        try {
            currentOrNull()?.rollback()
        } catch (e: Exception) {
            throw TransactionSystemException(e.message.orEmpty(), e)
        } finally {
            cleanup(status)
        }
    }

    internal fun cleanup(status: TransactionStatus) {
        if (!TransactionSynchronizationManager.hasResource(this)) {
            TransactionSynchronizationManager.unbindResourceIfPossible(this)
        }

        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.getSynchronizations().isEmpty()) {
            TransactionSynchronizationManager.clearSynchronization()
        }

        val outerManager = (status as ExposedTransactionStatus).outerTransactionManager
        TransactionManager.resetCurrent(outerManager)
    }

    override fun newTransaction(
        isolation: Int,
        readOnly: Boolean,
        outerTransaction: Transaction?
    ): Transaction {
        val tDefinition = DefaultTransactionDefinition().apply {
            isReadOnly = readOnly
            isolationLevel = isolation
        }

        getTransaction(tDefinition)

        return currentOrNull() ?: initTransaction()
    }

    private fun initTransaction(): Transaction {
        val connection = (TransactionSynchronizationManager.getResource(this) as ConnectionHolder).connection

        @Suppress("TooGenericExceptionCaught")
        val transactionImpl = try {
            SpringTransaction(
                connection = JdbcConnectionImpl(connection),
                db = db,
                transactionIsolation = defaultIsolationLevel,
                readOnly = defaultReadOnly,
                outerTransaction = currentOrNull()
            )
        } catch (e: Exception) {
            exposedLogger.error("Failed to start transaction. Connection will be closed.", e)
            connection.close()
            throw e
        }

        TransactionManager.resetCurrent(this)
        return Transaction(transactionImpl).apply {
            TransactionSynchronizationManager.bindResource(this@SpringTransactionManager, this)
            if (showSql) {
                addLogger(StdOutSqlLogger)
            }
        }
    }

    override fun currentOrNull(): Transaction? = TransactionSynchronizationManager.getResource(this) as Transaction?
    override fun bindTransactionToThread(transaction: Transaction?) {
        if (transaction != null) {
            bindResourceForSure(this, transaction)
        } else {
            TransactionSynchronizationManager.unbindResourceIfPossible(this)
        }
    }

    private fun bindResourceForSure(key: Any, value: Any) {
        TransactionSynchronizationManager.unbindResourceIfPossible(key)
        TransactionSynchronizationManager.bindResource(key, value)
    }

    private inner class SpringTransaction(
        override val connection: ExposedConnection<*>,
        override val db: Database,
        override val transactionIsolation: Int,
        override val readOnly: Boolean,
        override val outerTransaction: Transaction?,
        val currentTransaction: Transaction? = currentOrNull()
    ) : TransactionInterface {

        override fun commit() {
            connection.commit()
        }

        override fun rollback() {
            connection.rollback()
        }

        override fun close() {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                this@SpringTransactionManager.cleanup(currentTransaction)
            }
        }
    }
}
