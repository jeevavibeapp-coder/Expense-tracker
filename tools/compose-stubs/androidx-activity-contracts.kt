package androidx.activity.result.contract

import android.net.Uri

abstract class ActivityResultContract<I, O>

object ActivityResultContracts {
    /** Returns the per-permission grant map, not a single Boolean — the
     *  difference matters to every caller that inspects the result. */
    class RequestMultiplePermissions :
        ActivityResultContract<Array<String>, Map<String, Boolean>>()

    class RequestPermission : ActivityResultContract<String, Boolean>()

    /** Null when the user backs out of the picker. */
    class GetContent : ActivityResultContract<String, Uri?>()

    class OpenDocument : ActivityResultContract<Array<String>, Uri?>()

    class CreateDocument(private val mimeType: String) :
        ActivityResultContract<String, Uri?>()

    class StartActivityForResult :
        ActivityResultContract<android.content.Intent, ActivityResult>()
}

class ActivityResult(val resultCode: Int, val data: android.content.Intent?)
