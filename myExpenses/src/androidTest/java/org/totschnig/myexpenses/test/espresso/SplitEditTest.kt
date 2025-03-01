package org.totschnig.myexpenses.test.espresso

import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSpinnerText
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.adevinta.android.barista.interaction.BaristaScrollInteractions.scrollTo
import com.adevinta.android.barista.internal.viewaction.NestedEnabledScrollToAction.nestedScrollToAction
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.instanceOf
import org.junit.Before
import org.junit.Test
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.activity.ExpenseEdit
import org.totschnig.myexpenses.adapter.IdHolder
import org.totschnig.myexpenses.contract.TransactionsContract.Transactions
import org.totschnig.myexpenses.delegate.TransactionDelegate
import org.totschnig.myexpenses.model.Money
import org.totschnig.myexpenses.model.SplitTransaction
import org.totschnig.myexpenses.model.Transaction
import org.totschnig.myexpenses.provider.DatabaseConstants
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_ACCOUNTID
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_ROWID
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.testutils.Espresso.withIdAndParent
import org.totschnig.myexpenses.testutils.withAccount
import org.totschnig.myexpenses.testutils.withOperationType

class SplitEditTest : BaseExpenseEditTest() {
    private val accountLabel1 = "Test label 1"

    private val baseIntent: Intent
        get() = intentForNewTransaction.apply {
            putExtra(Transactions.OPERATION_TYPE, Transactions.TYPE_SPLIT)
        }

    @Before
    fun fixture() {
        account1 = buildAccount(accountLabel1)
    }

    /*
    Verify resolution of
    https://github.com/mtotschnig/MyExpenses/issues/987
     */
    @Test
    fun bug987() {
        val account2 = buildAccount("Test Account 2")
        testScenario = ActivityScenario.launch(baseIntent.apply { putExtra(KEY_ACCOUNTID, account1.id) })
        closeSoftKeyboard()
        onView(withId(R.id.CREATE_PART_COMMAND)).perform(scrollTo(), click())
        enterAmountSave("50")
        onView(withId(R.id.OperationType)).perform(click())
        onData(
            allOf(
                instanceOf(TransactionDelegate.OperationType::class.java),
                withOperationType(Transactions.TYPE_TRANSFER)
            )
        ).perform(click())
        onView(withId(R.id.TransferAccount)).perform(scrollTo(), click())
        onData(
            allOf(
                instanceOf(IdHolder::class.java),
                withAccount(account2.label)
            )
        ).perform(click())
        onView(withId(R.id.CREATE_COMMAND)).perform(click())//save part
        onView(withId(R.id.Account)).perform(scrollTo(), click())
        onData(
            allOf(
                instanceOf(IdHolder::class.java),
                withAccount(account2.label)
            )
        ).perform(click())
        onView(withId(R.id.Account)).check(matches(withSpinnerText(CoreMatchers.containsString(account1.label))))
    }

    @Test
    fun canceledSplitCleanup() {
        testScenario = ActivityScenario.launchActivityForResult(baseIntent)
        val uncommittedUri = TransactionProvider.UNCOMMITTED_URI
        assertThat(Transaction.count(uncommittedUri, DatabaseConstants.KEY_STATUS + "= ?", arrayOf(DatabaseConstants.STATUS_UNCOMMITTED.toString()))).isEqualTo(1)
        closeSoftKeyboard()
        pressBackUnconditionally()
        assertCanceled()
        assertThat(Transaction.count(uncommittedUri, DatabaseConstants.KEY_STATUS + "= ?", arrayOf(DatabaseConstants.STATUS_UNCOMMITTED.toString()))).isEqualTo(0)
    }

    @Test
    fun createPartAndSave() {
        testScenario = ActivityScenario.launchActivityForResult(baseIntent)
        createParts(5)
        onView(withId(R.id.CREATE_COMMAND)).perform(click())//amount is now updated automatically
        assertFinishing()
    }

    private fun createParts(times: Int) {
        repeat(times) {
            closeSoftKeyboard()
            onView(withId(R.id.CREATE_PART_COMMAND)).perform(nestedScrollToAction(), click())
            onView(withId(R.id.MANAGE_TEMPLATES_COMMAND)).check(doesNotExist())
            onView(withId(R.id.CREATE_TEMPLATE_COMMAND)).check(doesNotExist())
            enterAmountSave("50")
            onView(withId(R.id.CREATE_COMMAND)).perform(click())//save part
        }
    }

    @Test
    fun loadEditSaveSplit() {
        testScenario = ActivityScenario.launchActivityForResult(baseIntent.apply { putExtra(KEY_ROWID, prepareSplit()) })
        onView(withId(R.id.list)).check(matches(hasChildCount(2)))
        closeSoftKeyboard()
        scrollTo(R.id.list)
        onView(withId(R.id.list))
            .perform(RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
        onView(withText(R.string.menu_edit)).perform(click())
        onView(withIdAndParent(R.id.AmountEditText, R.id.Amount)).perform(replaceText("150"))
        onView(withId(R.id.MANAGE_TEMPLATES_COMMAND)).check(doesNotExist())
        onView(withId(R.id.CREATE_TEMPLATE_COMMAND)).check(doesNotExist())
        onView(withId(R.id.CREATE_COMMAND)).perform(click())//save part
        onView(withId(R.id.CREATE_COMMAND)).perform(click())//save parent succeeds
        assertFinishing()
    }

    private fun prepareSplit(): Long {
        val currencyUnit = homeCurrency
        return with(SplitTransaction.getNewInstance(account1.id, currencyUnit)) {
            amount = Money(currencyUnit, 10000)
            status = DatabaseConstants.STATUS_NONE
            save(true)
            val part = Transaction.getNewInstance(account1.id, currencyUnit, id)
            part.amount = Money(currencyUnit, 5000)
            part.save()
            part.amount = Money(currencyUnit, 5000)
            part.saveAsNew()
            id
        }
    }

    @Test
    fun canceledTemplateSplitCleanup() {
        testScenario = ActivityScenario.launch(baseIntent.apply { putExtra(ExpenseEdit.KEY_NEW_TEMPLATE, true) })
        val uncommittedUri = TransactionProvider.TEMPLATES_UNCOMMITTED_URI
        assertThat(Transaction.count(uncommittedUri, DatabaseConstants.KEY_STATUS + "= ?", arrayOf(DatabaseConstants.STATUS_UNCOMMITTED.toString()))).isEqualTo(1)
        closeSoftKeyboard()
        pressBackUnconditionally()
        assertThat(Transaction.count(uncommittedUri, DatabaseConstants.KEY_STATUS + "= ?", arrayOf(DatabaseConstants.STATUS_UNCOMMITTED.toString()))).isEqualTo(0)
    }

    @Test
    fun create_and_save() {
        testScenario = ActivityScenario.launchActivityForResult(baseIntent)
        createParts(1)
        clickMenuItem(R.id.SAVE_AND_NEW_COMMAND, false) //toggle save and new on
        onView(withId(R.id.CREATE_COMMAND)).perform(click())
        onView(withId(com.google.android.material.R.id.snackbar_text))
                .check(matches(withText(R.string.save_transaction_and_new_success)))
        waitForSnackbarDismissed()
        createParts(1)
        clickMenuItem(R.id.SAVE_AND_NEW_COMMAND, false) //toggle save and new off
        closeKeyboardAndSave()
        assertFinishing()
    }

    private fun enterAmountSave(amount: String) {
        onView(withIdAndParent(R.id.AmountEditText, R.id.Amount)).perform(typeText(amount))
        closeSoftKeyboard()
    }
}