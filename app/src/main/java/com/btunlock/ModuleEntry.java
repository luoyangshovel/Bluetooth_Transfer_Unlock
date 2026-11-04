package com.btunlock;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.btunlock.core.Hooks;
import com.btunlock.hooks.OppReceiveHooks;
import com.btunlock.hooks.OppSendHooks;
import com.btunlock.hooks.ScanModeHooks;
import com.btunlock.hooks.ShareSheetHooks;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * Module entry. There is no activity and no configuration to write: the scope is
 * fixed in {@code META-INF/xposed/scope.list} and {@code module.prop} declares it
 * as static, so enabling the module in LSPosed and restarting the
 * target process is all it takes.
 */
public class ModuleEntry extends XposedModule {

    private static final String BLUETOOTH_APK = "com.android.bluetooth";
    private static final String SYSTEM_SERVER = "system_server";

    /** Hook installation is per package and must not repeat within one process. */
    private static final Set<String> INSTALLED = Collections.synchronizedSet(new HashSet<>());

    @Nullable
    private volatile Hooks hooks;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        Hooks h = hooks();
        h.log(Log.INFO, "module loaded in " + param.getProcessName() + " (systemServer="
                + param.isSystemServer() + ") by " + getFrameworkName() + " " + getFrameworkVersion()
                + ", api " + getApiVersion());
    }

    /**
     * Android 10 and newer. The default class loader is already usable here, before
     * {@code AppComponentFactory} has run.
     */
    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        onPackage(param.getPackageName(), param.getDefaultClassLoader());
    }

    /** Android 9 has no {@code getDefaultClassLoader()}, this is the entry point there. */
    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        onPackage(param.getPackageName(), param.getClassLoader());
    }

    /**
     * The two features that live outside the Bluetooth APK: the discoverable timeout in
     * whichever process owns it, and the share-sheet row, which has to be injected into
     * system_server's intent resolution because the accept-list is another APK's manifest.
     */
    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        Hooks h = hooks();
        if (!INSTALLED.add(SYSTEM_SERVER)) {
            return;
        }
        int discoverable = ScanModeHooks.installSystemServer(h, param.getClassLoader());
        int share = ShareSheetHooks.install(h, param.getClassLoader());
        h.log(Log.INFO, "[system_server] hooks installed: discoverable-fallback=" + discoverable
                + ", share-sheet=" + share);
    }

    /**
     * Hot reload would drop every hook and package callbacks are not replayed, so the
     * module declines it rather than leaving a process silently unhooked.
     */
    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        hooks().log(Log.INFO, "hot reload declined: restart " + getFrameworkName()
                + "'s targets instead (for com.android.bluetooth: turn Bluetooth off and on, or reboot)");
        return false;
    }

    private void onPackage(@NonNull String packageName, @Nullable ClassLoader loader) {
        Hooks h = hooks();
        if (loader == null) {
            h.log(Log.WARN, "no class loader for " + packageName + ", nothing to do");
            return;
        }
        boolean target = BLUETOOTH_APK.equals(packageName);
        if (!target) {
            // Forks that rename the APK still expose the same AOSP class names; probe once.
            target = h.findClass(loader, OppReceiveHooks.OPP_PACKAGE + ".Constants") != null
                    || h.findClass(loader, ScanModeHooks.BTSERVICE_PACKAGE + ".AdapterProperties") != null;
        }
        if (!target) {
            h.verbose("skip " + packageName + " (no Bluetooth OPP / btservice classes)");
            return;
        }
        if (!INSTALLED.add(packageName)) {
            return;
        }

        int receive = OppReceiveHooks.install(h, loader);
        int send = OppSendHooks.install(h, loader);
        int discoverable = ScanModeHooks.installBluetoothApp(h, loader);

        h.log(Log.INFO, "[" + packageName + "] hooks installed: receive=" + receive
                + ", send=" + send + ", discoverable=" + discoverable);
        if (receive == 0) {
            h.log(Log.WARN, "receive unlock found no hook point: this ROM has no "
                    + "com.android.bluetooth.opp.Constants / onPut pair, inbound MIME check untouched");
        }
        if (discoverable == 0) {
            h.log(Log.WARN, "discoverable unlock found no hook point in the Bluetooth APK: "
                    + "check that the scope covers com.android.bluetooth and that the process was restarted");
        }
    }

    @NonNull
    private Hooks hooks() {
        Hooks local = hooks;
        if (local == null) {
            synchronized (this) {
                local = hooks;
                if (local == null) {
                    local = new Hooks(this);
                    hooks = local;
                }
            }
        }
        return local;
    }
}
