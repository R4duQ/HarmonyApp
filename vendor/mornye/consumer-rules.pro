# UniFFI/JNA resolves FFI methods and structure fields by their generated names.
-keep class com.sun.jna.** { *; }
-keep class com.mornye.backend.** { *; }
# JNA's desktop AWT helpers are unavailable and unused on Android.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
