package android.net
class Uri private constructor(private val value: String) {
    private val uri = java.net.URI(value)
    val scheme: String? get() = uri.scheme
    val host: String? get() = uri.host
    fun getQueryParameter(key: String): String? = uri.rawQuery?.split('&')?.map { it.split('=', limit=2) }
        ?.firstOrNull { java.net.URLDecoder.decode(it[0], "UTF-8") == key }
        ?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    override fun toString(): String = value
    fun buildUpon() = Builder(value)
    class Builder(private val base: String) {
        private val params = mutableListOf<Pair<String,String>>()
        fun appendQueryParameter(key: String, value: String) = apply { params += key to value }
        fun build(): Uri = parse(base + "?" + params.joinToString("&") {
            java.net.URLEncoder.encode(it.first, "UTF-8") + "=" + java.net.URLEncoder.encode(it.second, "UTF-8")
        })
    }
    companion object { fun parse(value: String): Uri = Uri(value) }
}
