package com.btunlock.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.btunlock.core.Hooks;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;

/**
 * Feature 2: let outbound OPP share every file type.
 *
 * <p>Two things stop a send, and only the first is hookable:</p>
 *
 * <ol>
 * <li>{@code BluetoothOppLauncherActivity#onCreate} requires a non-null
 * {@code Intent#getType()} next to {@code EXTRA_STREAM}; when it is null the activity
 * simply finishes and nothing is queued. That is the gate this class opens, by filling
 * in the MIME type the same way the OPP code would derive it.</li>
 * <li>Whether the share sheet even offers "Bluetooth" is decided by the
 * {@code intent-filter} MIME list in the installed Bluetooth APK's manifest, which no
 * hook can rewrite. See the README: with a ROM whose filter omits a type, launch the
 * activity by intent instead, which this module does let through.</li>
 * </ol>
 *
 * <p>Deliberately untouched: {@code BluetoothOppSendFileInfo}'s forbidden-URI and
 * external-storage rejections ({@code CVE-2022-35310991} et al.). Loosening those would
 * let any app read files it was never granted, which is a security bug rather than a
 * file-type restriction.</p>
 */
public final class OppSendHooks {

    private OppSendHooks() {
    }

    public static int install(@NonNull Hooks h, @NonNull ClassLoader loader) {
        int installed = launcherActivity(h, loader);
        installed += sendFileInfo(h, loader);
        installed += manager(h, loader);
        return installed;
    }

    private static int launcherActivity(@NonNull Hooks h, @NonNull ClassLoader loader) {
        Class<?> activity = h.findClass(loader,
                OppReceiveHooks.OPP_PACKAGE + ".BluetoothOppLauncherActivity",
                OppReceiveHooks.OPP_PACKAGE + ".BluetoothOppShareActivity");
        if (activity == null) {
            h.log(Log.WARN, "send: BluetoothOppLauncherActivity not found on this ROM");
            return 0;
        }
        int count = h.hookMethods(activity, "send.launcher", method -> {
            String name = Hooks.lower(method.getName());
            return (name.equals("oncreate") || name.equals("onnewintent"))
                    && method.getParameterCount() == 1;
        }, chain -> {
            Object self = chain.getThisObject();
            if (self instanceof Activity) {
                normalize(h, (Activity) self);
            }
            return chain.proceed();
        });
        if (count == 0) {
            h.log(Log.WARN, "send: no onCreate/onNewIntent in " + activity.getName());
        } else {
            h.log(Log.INFO, "send: " + count + " launcher entry point(s) accept any MIME type");
        }
        return count;
    }

    /** Fill in a missing intent type so the {@code stream != null && type != null} test passes. */
    private static void normalize(@NonNull Hooks h, @NonNull Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (action == null || !intent.hasExtra(Intent.EXTRA_STREAM)) {
                return;
            }
            boolean send = Intent.ACTION_SEND.equals(action);
            boolean sendMultiple = Intent.ACTION_SEND_MULTIPLE.equals(action);
            if (!send && !sendMultiple) {
                return;
            }
            if (!isMissingType(intent.getType())) {
                return;
            }
            String resolved;
            if (sendMultiple) {
                Uri first = firstStream(intent);
                resolved = first == null ? null : resolveType(activity, first);
            } else {
                Uri uri = singleStream(intent);
                resolved = uri == null ? null : resolveType(activity, uri);
            }
            if (resolved != null) {
                // EXTRA_TEXT-only shares keep their original behaviour: no stream, no rewrite.
                intent.setType(resolved);
                h.log(Log.INFO, "send: " + action + " had no MIME type, using " + resolved
                        + " so the OPP queue accepts it");
            }
        } catch (Throwable t) {
            h.log(Log.WARN, "send: could not normalize the share intent", t);
        }
    }

    @Nullable
    private static Uri singleStream(@NonNull Intent intent) {
        Object extra = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                : intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return extra instanceof Uri ? (Uri) extra : null;
    }

    @Nullable
    private static Uri firstStream(@NonNull Intent intent) {
        ArrayList<Uri> uris = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class)
                : intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        return firstUri(uris);
    }

    @Nullable
    private static Uri firstUri(@Nullable ArrayList<Uri> uris) {
        if (uris == null) {
            return null;
        }
        for (Uri uri : uris) {
            if (uri != null) {
                return uri;
            }
        }
        return null;
    }

    private static boolean isMissingType(@Nullable String type) {
        return type == null || type.trim().isEmpty() || "*/*".equals(type.trim());
    }

    /**
     * Same precedence the OPP code uses: the provider's own type, then the platform MIME
     * map for the file name extension, then a generic type. A {@code file://} URI resolves
     * through the extension branch; it is never dropped here.
     */
    @NonNull
    static String resolveType(@Nullable Context context, @NonNull Uri uri) {
        String type = null;
        if (context != null) {
            try {
                type = context.getContentResolver().getType(uri);
            } catch (Throwable ignored) {
                // Unreadable provider: fall through to the name-based guess.
            }
        }
        if (type == null) {
            type = typeFromExtension(uri);
        }
        return type != null ? type : OppReceiveHooks.FALLBACK_MIME;
    }

    @Nullable
    private static String typeFromExtension(@NonNull Uri uri) {
        try {
            String name = uri.getLastPathSegment();
            if (name == null) {
                name = uri.getPath();
            }
            if (name == null) {
                return null;
            }
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) {
                return null;
            }
            String extension = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code BluetoothOppSendFileInfo.generateFileInfo(Context, Uri, String type, boolean)}:
     * for a {@code file://} share the {@code type} argument becomes the OBEX TYPE header
     * verbatim, so a null there has to be filled in.
     */
    private static int sendFileInfo(@NonNull Hooks h, @NonNull ClassLoader loader) {
        Class<?> info = h.findClass(loader,
                OppReceiveHooks.OPP_PACKAGE + ".BluetoothOppSendFileInfo");
        if (info == null) {
            return 0;
        }
        int count = h.hookMethods(info, "send.generateFileInfo", method -> {
            for (Class<?> type : method.getParameterTypes()) {
                if (Uri.class.isAssignableFrom(type)) {
                    return Hooks.lower(method.getName()).contains("generatefileinfo")
                            || Hooks.lower(method.getName()).contains("sendfileinfo");
                }
            }
            return false;
        }, chain -> {
            Object[] args = chain.getArgs().toArray();
            Class<?>[] paramTypes = parameterTypes(chain);
            Context context = null;
            Uri uri = null;
            for (Object arg : args) {
                if (arg instanceof Context) {
                    context = (Context) arg;
                } else if (arg instanceof Uri) {
                    uri = (Uri) arg;
                }
            }
            int index = missingStringArg(args, paramTypes);
            if (index >= 0 && uri != null) {
                String resolved = resolveType(context, uri);
                h.verbose("send: " + uri + " had no MIME type argument, using " + resolved);
                args[index] = resolved;
                return chain.proceed(args);
            }
            return chain.proceed();
        });
        if (count > 0) {
            h.verbose("send: " + count + " generateFileInfo overloads guarded");
        }
        return count;
    }

    /** Last net: {@code BluetoothOppManager.saveSendingFileInfo(String mimeType, ...)} with a null type. */
    private static int manager(@NonNull Hooks h, @NonNull ClassLoader loader) {
        Class<?> manager = h.findClass(loader,
                OppReceiveHooks.OPP_PACKAGE + ".BluetoothOppManager");
        if (manager == null) {
            return 0;
        }
        return h.hookMethods(manager, "send.saveFileInfo", method -> {
            String name = Hooks.lower(method.getName());
            if (!name.contains("savesendingfileinfo")) {
                return false;
            }
            for (Class<?> type : method.getParameterTypes()) {
                if (type == String.class) {
                    return true;
                }
            }
            return false;
        }, chain -> {
            Object[] args = chain.getArgs().toArray();
            int index = missingStringArg(args, parameterTypes(chain));
            if (index < 0) {
                return chain.proceed();
            }
            h.verbose("send: null MIME type argument in "
                    + Hooks.describe(chain.getExecutable()) + ", using " + OppReceiveHooks.FALLBACK_MIME);
            args[index] = OppReceiveHooks.FALLBACK_MIME;
            return chain.proceed(args);
        });
    }

    @NonNull
    private static Class<?>[] parameterTypes(@NonNull XposedInterface.Chain chain) {
        Executable executable = chain.getExecutable();
        if (executable instanceof Method) {
            return ((Method) executable).getParameterTypes();
        }
        return new Class<?>[0];
    }

    /** @return the index of the first {@code String} parameter that arrived as null, or -1. */
    private static int missingStringArg(@NonNull Object[] args, @NonNull Class<?>[] paramTypes) {
        for (int i = 0; i < args.length && i < paramTypes.length; i++) {
            if (args[i] == null && paramTypes[i] == String.class) {
                return i;
            }
        }
        return -1;
    }
}
