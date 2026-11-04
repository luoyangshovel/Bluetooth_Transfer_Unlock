# R8 is disabled in this project (minifyEnabled false): a module is tiny, and the
# entry class name is referenced from META-INF/xposed/java_init.list, so shrinking
# buys nothing and risks losing the hook targets that are looked up by name.
# These rules are here for the day someone turns minification on.

# The annotation jar is compileOnly and never shipped; the service library is optional.
-dontwarn io.github.libxposed.annotation.**

# Keep the entry class reachable and rename-proof enough for the framework, and let
# java_init.list follow it if it is obfuscated.
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list
