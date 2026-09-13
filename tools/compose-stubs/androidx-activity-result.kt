package androidx.activity.result

class ActivityResultLauncher<I> internal constructor() {
    fun launch(input: I) {}
    fun unregister() {}
}
