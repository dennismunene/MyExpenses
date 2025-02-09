package org.totschnig.myexpenses.viewmodel

import android.app.Application
import android.content.ContentUris
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.totschnig.myexpenses.model.Grouping
import org.totschnig.myexpenses.provider.DatabaseConstants.*
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.provider.getIntIfExists
import org.totschnig.myexpenses.util.enumValueOrDefault
import org.totschnig.myexpenses.viewmodel.data.DistributionAccountInfo

class DistributionViewModel(application: Application, savedStateHandle: SavedStateHandle):
    DistributionViewModelBase<DistributionAccountInfo>(application, savedStateHandle) {
    private fun getGroupingPrefKey(accountId: Long) = stringPreferencesKey("distributionGrouping_$accountId")
    fun initWithAccount(accountId: Long, defaultGrouping: Grouping) {
        val isAggregate = accountId < 0
        val base =
            if (isAggregate) TransactionProvider.ACCOUNTS_AGGREGATE_URI else TransactionProvider.ACCOUNTS_URI
        val projection = if (isAggregate) arrayOf(KEY_LABEL, KEY_CURRENCY) else arrayOf(KEY_LABEL, KEY_CURRENCY, KEY_COLOR)
        viewModelScope.launch(coroutineContext()) {
            contentResolver.query(ContentUris.withAppendedId(base, accountId),
            projection, null, null, null)?.use {
                it.moveToFirst()
                _accountInfo.tryEmit(object: DistributionAccountInfo {
                    val label = it.getString(0)
                    override val accountId = accountId
                    override fun label(context: Context) = label
                    override val currency = currencyContext.get(it.getString(1))
                    override val color = if (isAggregate) -1 else it.getInt(2)
                })
            }
        }
        viewModelScope.launch {
            dataStore.data.map {
                enumValueOrDefault(it[getGroupingPrefKey(accountId)], defaultGrouping)
            }.collect {
                setGrouping(it)
            }
        }
    }

    fun persistGrouping(grouping: Grouping) {
        accountInfo.value?.let {
            viewModelScope.launch {
                dataStore.edit { preference ->
                    preference[getGroupingPrefKey(it.accountId)] = grouping.name
                }
            }
        }
    }
}
