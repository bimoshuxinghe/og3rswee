# TV
-keep class androidx.leanback.widget.** { *; }
-keep class com.fongmi.quickjs.method.** { *; }
-keep class com.fongmi.android.tv.bean.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# SimpleXML
-keep interface org.simpleframework.xml.core.Label { public *; }
-keep class * implements org.simpleframework.xml.core.Label { public *; }
-keep interface org.simpleframework.xml.core.Parameter { public *; }
-keep class * implements org.simpleframework.xml.core.Parameter { public *; }
-keep interface org.simpleframework.xml.core.Extractor { public *; }
-keep class * implements org.simpleframework.xml.core.Extractor { public *; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Path <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Root <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Text <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Element <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Attribute <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.ElementList <fields>; }

# OkHttp
-dontwarn okhttp3.**
-keep class okio.** { *; }
-keep class okhttp3.** { *; }

# CatVod
-keep class com.github.catvod.Proxy { *; }
-keep class com.github.catvod.crawler.** { *; }
-keep class * extends com.github.catvod.crawler.Spider

# Jianpian
-keep class com.p2p.** { *; }

# JUPnP
-dontwarn org.jupnp.**
-keep class org.jupnp.** { *; }
-keep class javax.xml.** { *; }

# Nano
-keep class fi.iki.elonen.** { *; }

# NewPipeExtractor
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# TVBus
-keep class com.tvbus.engine.** { *; }

# XunLei
-keep class com.xunlei.downloadlib.** { *; }

# Zxing
-keep class com.google.zxing.** { *; }

# CatVod Spiders (Native Built-in)
# 这些类被反射加载，且源码/库中含 lambda/合成类；R8 的 shrinking/obfuscation/optimizing
# 都会破坏蜘蛛动态加载和对话框回调，导致 NoSuchMethodError / ClassCastException。
# 使用最保守策略：类名、成员、构造器、合成属性全部保留，并显式禁用优化。
-keep class com.github.catvod.spider.** { *; }
-keepclassmembers class com.github.catvod.spider.** { *; }
-keepnames class com.github.catvod.spider.**
-keepclassmembernames class com.github.catvod.spider.** { *; }
-keep class com.github.catvod.crawler.** { *; }
-keepclassmembers class com.github.catvod.crawler.** { *; }
-keepnames class com.github.catvod.crawler.**
-keepclassmembernames class com.github.catvod.crawler.** { *; }
-keep class * extends com.github.catvod.crawler.Spider { *; }
-keepclassmembers class * extends com.github.catvod.crawler.Spider { *; }
-keepnames class * extends com.github.catvod.crawler.Spider
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Synthetic, Exceptions, LineNumberTable, MethodParameters, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-dontwarn com.github.catvod.spider.**
-dontwarn com.github.catvod.crawler.**
# 禁用 R8 优化阶段，防止合成类/内部类/lambda 被合并或接口被剥离。
# 与 proguard-rules-media.pro 中的 -dontoptimize 冗余，确保生效。
-dontoptimize

# Chaquopy & PyLoader Bridge
-keep class com.fongmi.chaquo.** { *; }
-keep class com.chaquo.python.** { *; }

# JNA (pulled in by :zlive via Native.load) — desktop-only API references are unavailable on Android.
-dontwarn java.awt.Component
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Vosk ASR (native JNI bindings must not be stripped/obfuscated)
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**
