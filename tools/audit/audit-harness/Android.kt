package android.content
import java.io.ByteArrayInputStream
class Context(val preferences: MemoryPreferences = MemoryPreferences()) {
    val contentResolver = ContentResolver()
    fun getSharedPreferences(name: String, mode: Int) = preferences
    companion object { const val MODE_PRIVATE = 0 }
}
class ContentResolver {
    fun openInputStream(uri: android.net.Uri) = ByteArrayInputStream(byteArrayOf(1, 2, 3))
}
class MemoryPreferences {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()
    fun getString(key: String, fallback: String?) = values[key] as? String ?: fallback
    fun getLong(key: String, fallback: Long) = values[key] as? Long ?: fallback
    fun edit() = Editor()
    inner class Editor {
        private val changes = mutableMapOf<String, Any?>()
        fun putString(key: String, value: String?) = apply { changes[key] = value }
        fun putLong(key: String, value: Long) = apply { changes[key] = value }
        fun remove(key: String) = apply { changes[key] = null }
        fun apply() { commit() }
        fun commit(): Boolean { changes.forEach { (k,v) -> if(v == null) values.remove(k) else values[k] = v }; return true }
    }
}
