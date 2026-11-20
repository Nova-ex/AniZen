# Keep CloudStream (.cs3) plugin runtime API and dependencies intact for dynamic DexClassLoader plugins
-keep class com.lagradost.** { *; }
-keep interface com.lagradost.** { *; }
-keep enum com.lagradost.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class me.xdrop.fuzzywuzzy.** { *; }
-keep class org.schabi.newpipe.** { *; }
-keep class com.uwetrottmann.tmdb2.** { *; }
-keep class retrofit2.** { *; }

-dontwarn com.lagradost.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.**
-dontwarn com.uwetrottmann.tmdb2.**
-dontwarn retrofit2.**
