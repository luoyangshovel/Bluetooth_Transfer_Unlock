package com.btunlock.hooks;

import android.util.Log;

import androidx.annotation.NonNull;

import com.btunlock.core.Hooks;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Feature 1: let inbound OPP accept every file type.
 *
 * <p>AOSP gates reception in {@code BluetoothOppObexServerSession#onPut} with
 * {@code Constants.ACCEPTABLE_SHARE_INBOUND_TYPES}, matched through
 * {@code Constants.mimeTypeMatches}. Three points are covered because the reject
 * decisions live in different places depending on the branch:</p>
 *
 * <ol>
 * <li>the accept-list test itself - always satisfied;</li>
 * <li>the "extension is not in the Android MIME map and the sender sent no TYPE
 * header" branch, which rejects with {@code OBEX_HTTP_UNSUPPORTED_TYPE} before the
 * accept-list is ever consulted;</li>
 * <li>{@code onPut} as the scope holder of point 2, so the MIME-map fallback below
 * only applies while an OPP push is being validated.</li>
 * </ol>
 *
 * <p>Unchanged on purpose: zero-length transfers, empty names and the "no extension
 * at all and no TYPE header" case still reject, because without either piece of
 * information there is no sane file name or type to hand to the user.</p>
 */
public final class OppReceiveHooks {

    public static final String OPP_PACKAGE = "com.android.bluetooth.opp";

    /** Used when the sender gave a name whose extension Android cannot resolve. */
    static final String FALLBACK_MIME = "application/octet-stream";

    /**
     * Thread confinement for the {@code android.webkit.MimeTypeMap} hook: it is only set
     * while {@code onPut} runs, so no other caller in the Bluetooth process sees a
     * invented MIME type.
     */
    static final ThreadLocal<Boolean> RELAXED = new ThreadLocal<>();

    private OppReceiveHooks() {
    }

    public static int install(@NonNull Hooks h, @NonNull ClassLoader loader) {
        int installed = acceptList(h, loader);
        installed += serverSession(h, loader);
        installed += extensionFallback(h, loader);
        return installed;
    }

    /** {@code Constants.mimeTypeMatches(mimeType, String[])} and its single-match helper. */
    private static int acceptList(@NonNull Hooks h, @NonNull ClassLoader loader) {
        Class<?> constants = h.findClass(loader,
                OPP_PACKAGE + ".Constants",
                OPP_PACKAGE + ".BluetoothOppConstants",
                OPP_PACKAGE + ".BluetoothOppUtils");
        if (constants == null) {
            h.log(Log.WARN, "receive: no OPP Constants class found, the inbound accept-list "
                    + "check is probably inlined into onPut; only the extension fallback applies");
            return 0;
        }
        int count = h.hookMethods(constants, "receive.acceptList",
                OppReceiveHooks::isAcceptListTest,
                chain -> Boolean.TRUE);
        if (count == 0) {
            h.log(Log.WARN, "receive: no MIME match method in " + constants.getName()
                    + " - inbound types stay restricted by this ROM");
        } else {
            h.log(Log.INFO, "receive: " + count + " accept-list test(s) in "
                    + constants.getSimpleName() + " now always pass");
        }
        return count;
    }

    /**
     * A boolean predicate over MIME strings: {@code mimeTypeMatches}, or anything that
     * takes the type plus a whitelist array. Matched by shape as well as by name so a
     * renamed fork helper is still caught.
     */
    private static boolean isAcceptListTest(@NonNull Method method) {
        if (!Hooks.isBoolean(method) || !Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        String name = Hooks.lower(method.getName());
        Class<?>[] params = method.getParameterTypes();
        boolean shape = params.length == 2 && params[0] == String.class
                && (params[1] == String[].class || params[1] == String.class || List.class.isAssignableFrom(params[1]));
        if (!shape) {
            return false;
        }
        return name.contains("mime") || name.contains("type") || name.contains("match")
                || name.contains("accept") || name.contains("allow")
                || name.contains("whitelist") || name.contains("blacklist");
    }

    /** {@code BluetoothOppObexServerSession#onPut}: opens the relaxed window, nothing else. */
    private static int serverSession(@NonNull Hooks h, @NonNull ClassLoader loader) {
        Class<?> session = h.findClass(loader,
                OPP_PACKAGE + ".BluetoothOppObexServerSession",
                OPP_PACKAGE + ".BluetoothOppServerSession",
                OPP_PACKAGE + ".BluetoothOppObexSession");
        if (session == null) {
            h.log(Log.WARN, "receive: no OPP server session class found");
            return 0;
        }
        int count = 0;
        for (Method method : Hooks.collectMethods(session)) {
            if (!isOnPut(method)) {
                continue;
            }
            if (h.hook(method, "receive.onPut", chain -> {
                RELAXED.set(Boolean.TRUE);
                try {
                    return chain.proceed();
                } finally {
                    RELAXED.remove();
                }
            })) {
                // onPut is the caller that inlines both helpers below; force re-dispatch.
                h.deoptimize(method);
                count++;
                h.log(Log.INFO, "receive: relaxed " + Hooks.describe(method));
            }
        }
        if (count == 0) {
            h.log(Log.WARN, "receive: no onPut(Operation) in " + session.getName());
        }
        return count;
    }

    /** {@code public int onPut(Operation op)} - the Operation type differs per Android version. */
    private static boolean isOnPut(@NonNull Method method) {
        if (method.getParameterCount() < 1 || method.getParameterCount() > 2) {
            return false;
        }
        Class<?> returnType = method.getReturnType();
        if (returnType != int.class && returnType != Integer.class) {
            return false;
        }
        return Hooks.lower(method.getName()).equals("onput");
    }

    /**
     * Android resolves the MIME type from the file name before it ever consults the
     * accept-list, and rejects when that lookup returns null. Answering with a generic
     * type inside the relaxed window is what makes "unknown extension" files receivable.
     */
    private static int extensionFallback(@NonNull Hooks h, @NonNull ClassLoader loader) {
        Class<?> mimeTypeMap = h.findClass(loader, "android.webkit.MimeTypeMap");
        if (mimeTypeMap == null) {
            h.log(Log.WARN, "receive: android.webkit.MimeTypeMap unavailable, unknown "
                    + "extensions will still be rejected");
            return 0;
        }
        int count = h.hookMethods(mimeTypeMap, "receive.extension", method -> {
            String name = Hooks.lower(method.getName());
            return name.contains("getmimetypefromextension")
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class
                    && method.getReturnType() == String.class;
        }, chain -> {
            Object result = chain.proceed();
            if (result != null || !Boolean.TRUE.equals(RELAXED.get())) {
                return result;
            }
            h.verbose("receive: extension " + chain.getArg(0) + " is unmapped, reporting "
                    + FALLBACK_MIME + " to the OPP accept-list check");
            return FALLBACK_MIME;
        });
        if (count == 0) {
            h.log(Log.WARN, "receive: getMimeTypeFromExtension not found on this ROM");
        }
        return count;
    }
}
