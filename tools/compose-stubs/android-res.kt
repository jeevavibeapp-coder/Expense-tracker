package android.content.res

class Resources {
    class NotFoundException(message: String) : RuntimeException(message)
    val configuration: Configuration get() = Configuration()
}

class Configuration {
    var uiMode: Int = 0
    companion object { const val UI_MODE_NIGHT_YES = 0x20; const val UI_MODE_NIGHT_MASK = 0x30 }
}
