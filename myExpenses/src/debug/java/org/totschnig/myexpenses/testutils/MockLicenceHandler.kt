package org.totschnig.myexpenses.testutils

import com.google.android.vending.licensing.PreferenceObfuscator
import org.totschnig.myexpenses.MyApplication
import org.totschnig.myexpenses.db2.Repository
import org.totschnig.myexpenses.preference.PrefHandler
import org.totschnig.myexpenses.util.CurrencyFormatter
import org.totschnig.myexpenses.util.crashreporting.CrashHandler
import org.totschnig.myexpenses.util.licence.LicenceHandler
import org.totschnig.myexpenses.util.licence.LicenceStatus

class MockLicenceHandler(
    context: MyApplication,
    licenseStatusPrefs: PreferenceObfuscator,
    crashHandler: CrashHandler,
    prefHandler: PrefHandler,
    repository: Repository,
    currencyFormatter: CurrencyFormatter
) : LicenceHandler(context, licenseStatusPrefs, crashHandler, prefHandler, repository, currencyFormatter) {
    fun setLockState(locked: Boolean) {
        this.licenceStatus = if (locked) null else LicenceStatus.PROFESSIONAL
        update()
    }
}