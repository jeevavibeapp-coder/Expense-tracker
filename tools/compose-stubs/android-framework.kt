// Android framework declarations. Only the members the app touches, but with
// the real shapes: nullability, `Array<*>` PDUs, the `Cursor` column-index
// contract, and the deprecated SmsMessage factories.
package android

object Manifest {
    object permission {
        const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"
        const val READ_SMS = "android.permission.READ_SMS"
        const val SEND_SMS = "android.permission.SEND_SMS"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    }
}
