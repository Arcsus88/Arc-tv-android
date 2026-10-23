# Release builds are not minified (see build.gradle.kts). If that changes,
# libVLC and libtorrent4j reach their Java classes from native code:
-keep class org.videolan.** { *; }
-keep class org.libtorrent4j.** { *; }
