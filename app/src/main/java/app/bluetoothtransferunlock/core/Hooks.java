package app.bluetoothtransferunlock.core;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import io.github.libxposed.api.XposedInterface;

/**
 * Target location layer for the modern API, which deliberately ships no
 * {@code XposedHelpers}. Everything is resolved by candidate class names plus
 * method name / signature <em>shape</em> tests so that a renamed or moved
 * vendor method still gets caught, and every miss is logged instead of thrown.
 */
public final class Hooks {

    public static final String TAG = "BTUnlock";

    /** {@code BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE}. */
    public static final int SCAN_MODE_DISCOVERABLE = 23;
    /** {@code BluetoothAdapter.SCAN_MODE_CONNECTABLE}. */
    public static final int SCAN_MODE_CONNECTABLE = 21;
    /** {@code BluetoothAdapter.SCAN_MODE_NONE}. */
    public static final int SCAN_MODE_NONE = 20;

    /** {@code AbstractionLayer.BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE} (Android 9-12 pass the HAL value). */
    static final int HAL_SCAN_MODE_DISCOVERABLE = 2;
    static final int HAL_SCAN_MODE_CONNECTABLE = 1;
    static final int HAL_SCAN_MODE_NONE = 0;

    private final XposedInterface api;
    private final Set<Executable> hooked = new HashSet<>();
    private final boolean verbose;

    public Hooks(XposedInterface api) {
        this.api = api;
        boolean v;
        try {
            v = Log.isLoggable(TAG, Log.VERBOSE);
        } catch (Throwable t) {
            v = false;
        }
        this.verbose = v;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void log(int priority, @NonNull String message) {
        log(priority, message, null);
    }

    public void log(int priority, @NonNull String message, @Nullable Throwable error) {
        try {
            api.log(priority, TAG, message, error);
        } catch (Throwable ignored) {
            // Never let diagnostics take down a hooked process.
        }
    }

    public void verbose(@NonNull String message) {
        if (verbose) {
            log(Log.VERBOSE, message);
        }
    }

    /** @return the first candidate that resolves, or {@code null}. */
    @Nullable
    public Class<?> findClass(@NonNull ClassLoader loader, @NonNull String... candidates) {
        for (String name : candidates) {
            try {
                Class<?> clazz = Class.forName(name, false, loader);
                if (clazz != null) {
                    return clazz;
                }
            } catch (Throwable ignored) {
                // Absent on this ROM: try the next alias.
            }
        }
        return null;
    }

    /**
     * Declared methods of {@code root} plus its nested classes, recursively but
     * bounded, because the binder stubs ({@code AdapterService$AdapterServiceBinder})
     * hold half of the interesting signatures.
     */
    @NonNull
    public static List<Method> collectMethods(@NonNull Class<?> root) {
        List<Method> out = new ArrayList<>();
        collectMethods(root, out, 0);
        return out;
    }

    private static void collectMethods(@NonNull Class<?> clazz, @NonNull List<Method> out, int depth) {
        try {
            Collections.addAll(out, clazz.getDeclaredMethods());
        } catch (Throwable t) {
            // A method whose parameter type cannot be resolved breaks the whole array;
            // public methods of the hierarchy are still worth trying.
            try {
                Collections.addAll(out, clazz.getMethods());
            } catch (Throwable ignored) {
                // class completely unreadable
            }
        }
        if (depth >= 3) {
            return;
        }
        try {
            for (Class<?> nested : clazz.getDeclaredClasses()) {
                collectMethods(nested, out, depth + 1);
            }
        } catch (Throwable ignored) {
            // no nested classes readable
        }
    }

    /**
     * Hooks every method of {@code clazz} (and its nested classes) accepted by {@code matcher}.
     *
     * @param id stable hook id, so a second call replaces the first instead of stacking
     * @return the number of installed hooks
     */
    public int hookMethods(@NonNull Class<?> clazz, @NonNull String id,
                           @NonNull Predicate<Method> matcher, @NonNull XposedInterface.Hooker hooker) {
        int count = 0;
        for (Method method : collectMethods(clazz)) {
            boolean matches;
            try {
                matches = matcher.test(method);
            } catch (Throwable t) {
                continue;
            }
            if (matches && hook(method, id, hooker)) {
                count++;
            }
        }
        return count;
    }

    public boolean hook(@NonNull Executable target, @NonNull String id, @NonNull XposedInterface.Hooker hooker) {
        if (!hooked.add(target)) {
            return false;
        }
        try {
            api.hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("btu:" + id)
                    .intercept(hooker);
            return true;
        } catch (Throwable t) {
            hooked.remove(target);
            log(Log.WARN, "hook failed for " + describe(target) + " (" + id + ")", t);
            return false;
        }
    }

    /**
     * Small helpers such as {@code mimeTypeMatches} are prime inlining candidates: the
     * caller would keep the baked-in decision and never reach our hook. Deoptimizing the
     * hooked executable itself makes the runtime re-dispatch it, which also un-inlines it
     * from its callers on the next compile.
     */
    public void deoptimize(@NonNull Executable target) {
        try {
            api.deoptimize(target);
        } catch (Throwable t) {
            log(Log.VERBOSE, "deoptimize unsupported for " + describe(target));
        }
    }

    /**
     * Calls {@code method} with its original body, skipping every hook - including ours.
     * Used by the share-sheet resolver probe, which has to ask the real
     * {@code queryIntentActivities} what a type the accept-list does know would return.
     */
    @Nullable
    public Object invokeOriginal(@NonNull Method method, @Nullable Object self,
                                 @NonNull Object... args) throws Throwable {
        return api.getInvoker(method)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .invoke(self, args);
    }

    @NonNull
    public static String describe(@NonNull Executable target) {
        StringBuilder sb = new StringBuilder();
        sb.append(target.getDeclaringClass().getName()).append('#').append(target.getName()).append('(');
        Class<?>[] params = target.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getName());
        }
        return sb.append(')').toString();
    }

    /** @return true when the value means "discoverable" in either the framework or the HAL encoding. */
    public static boolean isDiscoverableMode(int scanMode) {
        return scanMode == SCAN_MODE_DISCOVERABLE || scanMode == HAL_SCAN_MODE_DISCOVERABLE;
    }

    /** @return true when the value means "connectable, not discoverable" in either encoding. */
    public static boolean isConnectableOnlyMode(int scanMode) {
        return scanMode == SCAN_MODE_CONNECTABLE || scanMode == HAL_SCAN_MODE_CONNECTABLE;
    }

    /** @return true when the value means "nothing allowed" in either encoding. */
    public static boolean isNoneMode(int scanMode) {
        return scanMode == SCAN_MODE_NONE || scanMode == HAL_SCAN_MODE_NONE;
    }

    /** Indexes of the primitive numeric arguments, which is how every timeout is passed. */
    @NonNull
    public static List<Integer> numericArgIndexes(@NonNull List<Object> args) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof Integer || arg instanceof Long) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    public static boolean isBoolean(@NonNull Method method) {
        Class<?> type = method.getReturnType();
        return type == boolean.class || type == Boolean.class;
    }

    public static boolean takesString(@NonNull Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (type == String.class) {
                return true;
            }
        }
        return false;
    }

    public static String lower(@NonNull String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }
}
