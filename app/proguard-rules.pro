# Required by NewPipeExtractor (YouTube signature deobfuscation uses Rhino)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
