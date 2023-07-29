package org.jetbrains.exposed.spring

import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.springframework.transaction.support.AbstractTransactionStatus

/**
 * @author ivan@daangn.com
 */
class ExposedTransactionStatus(
    val outerTransactionManager: TransactionManager?,
) : AbstractTransactionStatus() {

    override fun isNewTransaction(): Boolean {
        TODO("Not yet implemented")
    }
}
