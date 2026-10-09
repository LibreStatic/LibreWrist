# libadb-android loads the Conscrypt provider reflectively.
-keep class org.conscrypt.OpenSSLProvider { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.spake2.** { *; }
