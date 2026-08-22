# minifyEnabled is currently false, so these rules aren't active — kept here for reference
# in case you enable R8/ProGuard later. They come straight from the libxposed/api README
# and are required for module entry classes to survive obfuscation/shrinking.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
