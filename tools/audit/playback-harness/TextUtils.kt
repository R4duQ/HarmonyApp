package android.text
object TextUtils {
    @JvmStatic fun isEmpty(value: CharSequence?) = value.isNullOrEmpty()
    @JvmStatic fun equals(a: CharSequence?, b: CharSequence?) = a?.toString() == b?.toString()
}
