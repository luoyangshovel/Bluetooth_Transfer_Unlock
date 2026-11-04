package app.bluetoothtransferunlock.hooks;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.bluetoothtransferunlock.core.Hooks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * Feature 4: put a Bluetooth row into every app's share sheet.
 *
 * <p>Whether the row exists at all is decided by the system Bluetooth APK's manifest:
 * {@code BluetoothOppLauncherActivity} declares an {@code ACTION_SEND} filter with a
 * fixed MIME list ({@code image/*}, {@code video/*}, {@code audio/*}, a few text types,
 * {@code application/zip}, office types, {@code application/pdf}). An APK shared as
 * {@code application/vnd.android.package-archive} is therefore invisible to Bluetooth,
 * while the same file shared as {@code application/zip} is not - which is exactly why a
 * file manager that hard-codes the type can send APKs over Bluetooth and the gallery
 * next to it cannot. The list lives in another APK, so it cannot be rewritten.</p>
 *
 * <p>What can be changed is the <em>answer</em> the share sheet gets. The sheet is built
 * in the sharing app's process, but the resolution itself runs in system_server, so the
 * module appends the real Bluetooth {@link ResolveInfo} to every {@code ACTION_SEND} /
 * {@code ACTION_SEND_MULTIPLE} result list that does not already contain it. The entry is
 * not handcrafted: the hook re-queries the same method with the type swapped for
 * {@code application/zip} and reuses whatever the platform produced, so label, icon,
 * filter, match flags and user handling are all the genuine article. A tapped entry
 * starts the activity by explicit component, which is why the accept-list is not
 * consulted again, and the send path is then the one features 1-2 already cover.</p>
 *
 * <p>Fails closed: no probe result (Bluetooth off, so the activity is disabled; a fork
 * that renamed it) means nothing is injected, and every error path leaves the original
 * list untouched. The probe is cached per user, so the extra work happens at most every
 * {@link #PROBE_TTL_MS} instead of on every share sheet.</p>
 */
public final class ShareSheetHooks {

    private static final String BT_PACKAGE = "com.android.bluetooth";

    /** A type the stock filter does accept, used only to obtain a genuine ResolveInfo. */
    private static final String PROBE_MIME = "application/zip";

    /** How long a probed entry is reused; keeps the share sheet fast and the log quiet. */
    static final long PROBE_TTL_MS = 10_000L;

    private static final class Probe {
        @Nullable
        final ResolveInfo info;
        final long expiresAt;

        Probe(@Nullable ResolveInfo info, long expiresAt) {
            this.info = info;
            this.expiresAt = expiresAt;
        }
    }

    /** Keyed by calling user id, since system_server answers for every user. */
    private static final Map<Integer, Probe> CACHE = new ConcurrentHashMap<>();

    /**
     * Where the app-facing {@code queryIntentActivities} lives. Verified against the
     * {@code services.jar} of three real builds: Android 10 declares it on
     * {@code PackageManagerService} as {@code (Intent, String, int, int)}, while Android
     * 13 and 16 both declare it on {@code IPackageManagerBase} as
     * {@code (Intent, String, long, int)} - the service class itself no longer has it
     * there. Every candidate is tried until one yields a hook, since a class can exist
     * without carrying the method.
     */
    private static final String[] SERVICE_CANDIDATES = {
            "com.android.server.pm.IPackageManagerBase",
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.PackageManagerInternalBase",
            "com.android.server.pm.PackageManagerServiceEx",
    };

    private ShareSheetHooks() {
    }

    public static int install(@NonNull Hooks h, @NonNull ClassLoader loader) {
        StringBuilder tried = new StringBuilder();
        for (String name : SERVICE_CANDIDATES) {
            Class<?> clazz = h.findClass(loader, name);
            if (clazz == null) {
                continue;
            }
            int count = hookQueries(h, clazz);
            if (count > 0) {
                h.log(Log.INFO, "share: " + count + " resolver hook(s) in " + clazz.getSimpleName()
                        + " - Bluetooth will be offered for any shared file type");
                return count;
            }
            if (tried.length() > 0) {
                tried.append(" | ");
            }
            tried.append(name).append(" -> ").append(resolveLikeMethods(clazz));
        }
        h.log(Log.WARN, "share: no hookable queryIntentActivities in system_server, the share "
                + "sheet will keep hiding Bluetooth for other file types; tried: " + tried);
        return 0;
    }

    private static int hookQueries(@NonNull Hooks h, @NonNull Class<?> clazz) {
        return h.hookMethods(clazz, "share.resolver", ShareSheetHooks::isQueryList, chain -> {
            Object result = chain.proceed();
            if (result != null) {
                try {
                    appendBluetooth(h, chain, result);
                } catch (Throwable t) {
                    // The caller already has its list: never let injection break a share.
                    h.verbose("share: injection skipped (" + t + ")");
                }
            }
            return result;
        });
    }

    /**
     * The app-facing {@code queryIntentActivities(Intent, String, ...)} - the second
     * parameter is the resolved MIME the caller passed, which is what the probe below
     * swaps. The exact name plus that signature excludes the internal helpers reached
     * through nested classes ({@code PackageManagerInternalImpl} takes flags where the
     * type belongs) and the {@code ...AsUser} / {@code ...Internal} overloads, whose
     * results other system components consume in ways this feature must not touch.
     */
    private static boolean isQueryList(@NonNull Method method) {
        if (!Hooks.lower(method.getName()).equals("queryintentactivities")) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 3 || params[0] != Intent.class || params[1] != String.class) {
            return false;
        }
        Class<?> ret = method.getReturnType();
        return List.class.isAssignableFrom(ret)
                || ret.getName().endsWith("ParceledListSlice")
                || ret == Object.class;
    }

    private static void appendBluetooth(@NonNull Hooks h, @NonNull XposedInterface.Chain chain,
                                        @NonNull Object result) {
        Intent intent = (Intent) chain.getArg(0);
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }
        List<ResolveInfo> list = asList(result);
        if (list != null && containsBluetooth(list)) {
            return;
        }
        ResolveInfo bluetooth = probe(h, chain, intent);
        if (bluetooth == null) {
            return;
        }
        if (list != null) {
            try {
                list.add(bluetooth);
                return;
            } catch (Throwable ignored) {
                // an immutable list: fall back to the slice's own append
            }
        }
        appendThroughSlice(h, result, bluetooth);
    }

    /** Re-query with {@link #PROBE_MIME} through the unhooked original and keep Bluetooth's entry. */
    @Nullable
    private static ResolveInfo probe(@NonNull Hooks h, @NonNull XposedInterface.Chain chain,
                                     @NonNull Intent shareIntent) {
        // android.os.UserHandle.getUserId() is not in the SDK stubs; PER_USER_RANGE is 100000.
        int user = Binder.getCallingUid() / 100000;
        long now = System.currentTimeMillis();
        Probe cached = CACHE.get(user);
        if (cached != null && cached.expiresAt > now) {
            return cached.info;
        }
        ResolveInfo found = null;
        try {
            Object[] args = chain.getArgs().toArray();
            Intent clone = new Intent(shareIntent);
            clone.setType(PROBE_MIME);
            args[0] = clone;
            Class<?>[] paramTypes = chain.getExecutable().getParameterTypes();
            for (int i = 1; i < args.length && i < paramTypes.length; i++) {
                if (paramTypes[i] == String.class) {
                    args[i] = PROBE_MIME; // the resolvedType the caller passed
                    break;
                }
            }
            Object probed = h.invokeOriginal((Method) chain.getExecutable(),
                    chain.getThisObject(), args);
            List<ResolveInfo> results = probed == null ? null : asList(probed);
            if (results != null) {
                found = findBluetooth(results);
            }
            if (found == null) {
                h.verbose("share: " + BT_PACKAGE + " offered nothing for " + PROBE_MIME
                        + " - is Bluetooth switched on?");
            } else if (h.isVerbose()) {
                h.verbose("share: using " + found.activityInfo.name + " as the Bluetooth row");
            }
        } catch (Throwable t) {
            h.verbose("share: resolver probe failed (" + t + ")");
        }
        CACHE.put(user, new Probe(found, now + PROBE_TTL_MS));
        return found;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<ResolveInfo> asList(@Nullable Object result) {
        if (result instanceof List) {
            return (List<ResolveInfo>) result;
        }
        if (result == null) {
            return null;
        }
        try {
            Object inner = result.getClass().getMethod("getList").invoke(result);
            if (inner instanceof List) {
                return (List<ResolveInfo>) inner;
            }
        } catch (Throwable ignored) {
            // not a ParceledListSlice we can reach
        }
        return null;
    }

    private static boolean containsBluetooth(@NonNull List<ResolveInfo> list) {
        return findBluetooth(list) != null;
    }

    @Nullable
    private static ResolveInfo findBluetooth(@NonNull List<ResolveInfo> list) {
        for (ResolveInfo info : list) {
            if (info != null && info.activityInfo != null
                    && BT_PACKAGE.equals(info.activityInfo.packageName)
                    && info.activityInfo.name != null) {
                return info;
            }
        }
        return null;
    }

    /** {@code ParceledListSlice.append(T)} - used when the backing list refuses to change. */
    private static void appendThroughSlice(@NonNull Hooks h, @NonNull Object result,
                                           @NonNull ResolveInfo info) {
        try {
            Method append = result.getClass().getMethod("append", Object.class);
            append.invoke(result, info);
        } catch (Throwable t) {
            h.verbose("share: could not append to " + result.getClass().getName() + " (" + t + ")");
        }
    }

    /** Diagnostics for a ROM that renamed the service: what the class actually offers. */
    @NonNull
    private static String resolveLikeMethods(@NonNull Class<?> clazz) {
        List<String> out = new ArrayList<>();
        for (Method method : Hooks.collectMethods(clazz)) {
            String name = Hooks.lower(method.getName());
            if (name.contains("queryintent") || name.contains("resolve")) {
                out.add(method.getName() + "/" + method.getParameterCount());
                if (out.size() >= 8) {
                    break;
                }
            }
        }
        return out.isEmpty() ? "none" : String.join(", ", out);
    }
}
