package android.os
object Build {
    @JvmField val DEVICE = "jvm"
    @JvmField val MANUFACTURER = "test"
    @JvmField val MODEL = "jvm"
    object VERSION { @JvmField val SDK_INT = 35; @JvmField val RELEASE = "15"; @JvmField val CODENAME = "REL" }
}
class Bundle {
    private val values = mutableMapOf<String, Any?>()
    constructor()
    constructor(other: Bundle) { values.putAll(other.values) }
    fun putFloat(key: String, value: Float) { values[key] = value }
    fun getFloat(key: String) = values[key] as? Float ?: 0f
    fun containsKey(key: String) = key in values
    companion object { @JvmField val EMPTY = Bundle() }
}
