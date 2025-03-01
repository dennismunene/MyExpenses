package org.totschnig.myexpenses.viewmodel

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import app.cash.copper.flow.mapToList
import app.cash.copper.flow.mapToOne
import app.cash.copper.flow.observeQuery
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.model.Transaction.EXTENDED_URI
import org.totschnig.myexpenses.provider.DatabaseConstants.*
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.util.*
import org.totschnig.myexpenses.viewmodel.data.Debt
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

open class DebtViewModel(application: Application) : ContentResolvingAndroidViewModel(application) {

    @Inject
    lateinit var currencyFormatter: CurrencyFormatter

    fun saveDebt(debt: Debt): LiveData<Unit> = liveData(context = coroutineContext()) {
        emit(repository.saveDebt(debt))
    }

    fun loadDebt(debtId: Long): StateFlow<Debt?> =
        contentResolver.observeQuery(
            singleDebtUri(debtId),
            null,
            null,
            null,
            null
        ).mapToOne {
            Debt.fromCursor(it, currencyContext)
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private fun singleDebtUri(debtId: Long) =
        ContentUris.withAppendedId(TransactionProvider.DEBTS_URI, debtId)

/*    fun loadDebugTransactions(count: Int = 10): LiveData<List<Transaction>> = liveData {
        emit(
            List(count) {
                Transaction(it.toLong(), LocalDate.now(), 4000L - it, 4000L - it * it, -1)
            }
        )
    }*/

    private fun transactionsFlow(debt: Debt): Flow<List<Transaction>> {
        var runningTotal: Long = 0
        var runningEquivalentTotal: Long = 0
        val homeCurrency = homeCurrencyProvider.homeCurrencyString
        val equivalentAmountColumn =
            "CASE WHEN $KEY_CURRENCY = '$homeCurrency' THEN $KEY_AMOUNT ELSE ${
                getAmountHomeEquivalent(VIEW_EXTENDED, homeCurrency)
            } END"
        return contentResolver.observeQuery(
            uri = EXTENDED_URI,
            projection = arrayOf(KEY_ROWID, KEY_DATE, KEY_AMOUNT, equivalentAmountColumn),
            selection = "$KEY_DEBT_ID = ?",
            selectionArgs = arrayOf(debt.id.toString()),
            sortOrder = "$KEY_DATE ASC"
        ).onEach {
            runningTotal = debt.amount
            runningEquivalentTotal = debt.equivalentAmount?: debt.amount
        }.mapToList {
            val amount = it.getLong(2)
            val equivalentAmount = it.getLong(3)
            runningTotal -= amount
            runningEquivalentTotal -= equivalentAmount
            Transaction(
                it.getLong(0),
                epoch2LocalDate(it.getLong(1)),
                -amount,
                runningTotal,
                equivalentAmount,
                runningEquivalentTotal
            )
        }
    }

    private val transactionsLiveData: Map<Debt, LiveData<List<Transaction>>> = lazyMap { debt ->
        transactionsFlow(debt).asLiveData(coroutineContext())
    }

    fun loadTransactions(debt: Debt): LiveData<List<Transaction>> =
        transactionsLiveData.getValue(debt)

    fun deleteDebt(debtId: Long): LiveData<Boolean> =
        liveData(context = coroutineContext()) {
            emit(contentResolver.delete(singleDebtUri(debtId), null, null) == 1)
        }

    fun closeDebt(debtId: Long) {
        viewModelScope.launch(coroutineDispatcher) {
            updateSealed(debtId, 1)
        }
    }

    fun reopenDebt(debtId: Long) {
        viewModelScope.launch(coroutineDispatcher) {
            updateSealed(debtId, 0)
        }
    }

    /**
     * @param isSealed 1 == Sealed, 0 == Open
     */
    private fun updateSealed(debtId: Long, isSealed: Int) = contentResolver.update(
        ContentUris.withAppendedId(TransactionProvider.DEBTS_URI, debtId),
        ContentValues(1).apply {
            put(KEY_SEALED, isSealed)
        }, null, null
    )

    private suspend fun exportData(
        context: Context,
        debt: Debt
    ): List<Triple<String, String, String>> {
        val transactions = buildList {
            add(Transaction(0, epoch2LocalDate(debt.date), 0, debt.amount))
            transactionsFlow(debt).take(1).collect {
                addAll(it)
            }
        }
        val dateFormatter = getDateTimeFormatter(context)
        return transactions.map { transaction ->
            Triple(
                dateFormatter.format(transaction.date),
                transaction.amount.takeIf { it != 0L }?.let {
                    currencyFormatter.convAmount(it, debt.currency)
                } ?: "",
                currencyFormatter.convAmount(transaction.runningTotal, debt.currency)
            )
        }
    }

    fun exportText(context: Context, debt: Debt): LiveData<String> =
        liveData(context = coroutineContext()) {
            val stringBuilder = StringBuilder().appendLine(debt.label)
                .appendLine(debt.title(context))
            debt.description.takeIf { it.isNotBlank() }?.let {
                stringBuilder.appendLine(it)
            }
            stringBuilder.appendLine()
            val exportData = exportData(context, debt)
            val columnWidths = exportData.fold(Triple(0, 0, 0)) { max, element ->
                Triple(
                    maxOf(max.first, element.first.length),
                    maxOf(max.second, element.second.length),
                    maxOf(max.third, element.third.length)
                )
            }
            exportData.forEach {
                stringBuilder.appendLine(
                    it.first.padStart(columnWidths.first) + " | " +
                            it.second.padStart(columnWidths.second) + " | " +
                            it.third.padStart(columnWidths.third)
                )
            }
            emit(stringBuilder.toString())
        }

    fun exportHtml(context: Context, debt: Debt): LiveData<Uri> =
        liveData(context = coroutineContext()) {
            val file = File(context.cacheDir, "debt_${debt.id}.html")
            file.writer().use { writer ->
                val table = exportData(context, debt)
                writer.appendHTML().html {
                    head {
                        meta(charset = "utf-8")
                        style {
                            unsafe {
                                raw(
                                    """
                                 table, th, td {
                                  border: 1px solid black;
                                  border-collapse: collapse;
                                }
                                td {
                                  text-align: end;
                                  padding: 5px;
                                }
                                div {
                                  margin-bottom: 10px;
                                """
                                )
                            }
                        }
                    }
                    body {
                        div {
                            b {
                                text(debt.label)
                            }
                            br
                            text(debt.title(context))
                            debt.description.takeIf { it.isNotBlank() }?.let {
                                br
                                text(debt.description)
                            }
                        }
                        table {
                            val count = table.size

                            table.forEachIndexed { index, row ->
                                tr {
                                    td { text(row.first) }
                                    td { text(row.second) }
                                    td {
                                        if (index == count - 1) {
                                            b {
                                                text(row.third)
                                            }
                                        } else {
                                            text(row.third)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            emit(AppDirHelper.getContentUriForFile(getApplication(), file))
        }

    data class Transaction(
        val id: Long,
        val date: LocalDate,
        val amount: Long,
        val runningTotal: Long,
        val equivalentAmount: Long = 0,
        val equivalentRunningTotal: Long = 0,
    )

    enum class ExportFormat(val mimeType: String, val resId: Int) {
        HTML("text/html", R.string.html), TXT("text/plain", R.string.txt)
    }
}