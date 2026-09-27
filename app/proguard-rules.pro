# Anti-Decompilation & Shrink Obfuscation
-repackageclasses 'com.agentforge.app.obfuscated'
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Strip Debug Logging from Production Binary
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Preserve Critical Shizuku IPC Services
-keep class com.agentforge.app.shizuku.PrivilegedUserService { *; }
-keep interface com.agentforge.app.shizuku.IPrivilegedActions { *; }
