# Release build keeps minification disabled, so these are precautionary.
# NewPipeExtractor relies on Rhino + reflection; keep its classes if you enable R8.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**
