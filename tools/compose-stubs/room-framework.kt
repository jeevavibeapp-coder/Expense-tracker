// androidx.sqlite:sqlite-framework 2.4.0: the factory Room uses when it is
// handed none. The recovery path builds through it rather than replacing it,
// so the only difference from Room's default open is the corruption callback.
package androidx.sqlite.db.framework

import androidx.sqlite.db.SupportSQLiteOpenHelper

class FrameworkSQLiteOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration,
    ): SupportSQLiteOpenHelper = throw UnsupportedOperationException("stub")
}
