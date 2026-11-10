package android.telephony

class SmsMessage private constructor() {
    val messageBody: String? get() = null
    val originatingAddress: String? get() = null
    val displayOriginatingAddress: String? get() = null
    val timestampMillis: Long get() = 0L

    companion object {
        @Deprecated("Use createFromPdu(byte[], String) instead")
        fun createFromPdu(pdu: ByteArray): SmsMessage? = null
        fun createFromPdu(pdu: ByteArray, format: String): SmsMessage? = null
    }
}

class TelephonyManager
