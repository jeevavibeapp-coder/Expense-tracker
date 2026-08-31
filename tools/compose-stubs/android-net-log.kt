package android.net

class Uri private constructor(private val raw: String) {
    override fun toString(): String = raw
    val path: String? get() = raw
    val lastPathSegment: String? get() = raw.substringAfterLast('/')

    companion object {
        fun parse(uriString: String): Uri = Uri(uriString)
        fun fromFile(file: java.io.File): Uri = Uri(file.path)
    }
}
