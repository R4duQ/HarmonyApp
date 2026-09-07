package android.net
data class Uri(private val value: String) {
    override fun toString() = value
    companion object {
        @JvmStatic fun parse(value: String) = Uri(value)
        @JvmField val EMPTY = Uri("")
    }
}
