# Commons Compress uses optional codecs via reflection / ServiceLoader.
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.compress.** { *; }

# XZ / LZMA
-dontwarn org.tukaani.xz.**
-keep class org.tukaani.xz.** { *; }

# Zstd (pure-Java, aircompressor)
-dontwarn io.airlift.compress.**
-keep class io.airlift.compress.** { *; }

# Brotli
-dontwarn org.brotli.dec.**
-keep class org.brotli.dec.** { *; }

# junrar
-dontwarn com.github.junrar.**
-keep class com.github.junrar.** { *; }

# ISO9660 (loop-fs). Hadoop is excluded from the build; silence its refs.
-dontwarn com.github.stephenc.javaisotools.**
-keep class com.github.stephenc.javaisotools.** { *; }
-dontwarn org.apache.hadoop.**
# commons-logging discovers its Log impl reflectively; keep it intact.
-dontwarn org.apache.commons.logging.**
-keep class org.apache.commons.logging.** { *; }

# Silence optional deps not present on Android
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.slf4j.**
