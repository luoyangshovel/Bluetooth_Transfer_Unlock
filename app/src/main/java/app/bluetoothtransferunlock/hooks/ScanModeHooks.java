package app.bluetoothtransferunlock.hooks;

import android.app.Application;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.bluetoothtransferunlock.core.Hooks;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Feature 3: be discoverable without opening the pairing page.
 *
 * <p>The discoverable window is a <em>timeout</em>, not a mode: every Android release
 * between 9 and 16 ends up writing {@code BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT} to the
 * native stack, which then demotes the adapter on its own. The version differences are only
 * in how the number travels:</p>
 *
 * <ul>
 * <li>Android 9-12: {@code BluetoothAdapter.setScanMode(mode, duration)} &rarr;
 * {@code AdapterService.setScanMode(int, int)} &rarr; {@code setDiscoverableTimeout(int)}
 * &rarr; {@code AdapterProperties.setDiscoverableTimeout(int)}</li>
 * <li>Android 13-16: {@code BluetoothAdapter.setScanMode(int)} plus a separate
 * {@code setDiscoverableTimeout(Duration)} &rarr; binder
 * {@code setDiscoverableTimeout(long, AttributionSource, ...)} &rarr;
 * {@code AdapterProperties.setDiscoverableTimeout(int)}</li>
 * </ul>
 *
 * <p>So the hooks are name-driven over that whole family and clamp the timeout argument to
 * 0, which the framework documents as "no timeout, persisted until the next
 * {@code setScanMode}". {@code AdapterProperties.setDiscoverableTimeout(int)} is the single
 * choke point all of them funnel through, which is why the module also keeps working on
 * forks that reshuffle the layers above it.</p>
 *
 * <p>Clamping alone is not enough: it only stretches a window somebody already opened, so a
 * phone whose pairing page was never visited stays invisible. Permanent discoverability is
 * therefore a posture the module asserts by itself - the adapter enable callbacks hand over
 * the {@code AdapterService} / {@code AdapterProperties} instances, and from then on
 * {@link #verify} re-applies the discoverable mode whenever the live scan mode lost it.</p>
 *
 * <p>The trap is the <em>encoding</em>: {@code AdapterService.setScanMode} takes the framework
 * value ({@code 23}) and converts it, while on Android 9-12 {@code AdapterProperties.setScanMode}
 * is handed the result of that conversion - the HAL value {@code 2}. Writing {@code 23} there is
 * accepted by the Java layer and dropped by the stack, so the mode never leaves {@code 21}. The
 * module therefore asks through {@code BluetoothAdapter} first, then {@code AdapterService}, and
 * only ever uses {@code AdapterProperties} with the value it watched that class actually receive.
 * Because a phone that was never paired teaches the module nothing, the argument for the first two
 * layers is not guessed but probed: one write per pass, and only the one the live scan mode agrees
 * with is kept - see {@link #reapply}.</p>
 *
 * <p>What still wins:</p>
 * <ol>
 * <li>switching Bluetooth off - the mode is only re-applied while the adapter reports
 * {@code STATE_ON}, and nothing here ever calls {@code enable()};</li>
 * <li>an explicit scan mode request from another UID, which unpins until Bluetooth is
 * switched back on; the Bluetooth process's own writes - the demotion at the end of a
 * discoverable window included - never count as one;</li>
 * <li>a native demotion is caught twice over, by {@code adapterPropertyChangedCallback} and
 * by the watchdog, because a clamped timeout is not always honoured.</li>
 * </ol>
 */
public final class ScanModeHooks {

    public static final String BTSERVICE_PACKAGE = "com.android.bluetooth.btservice";

    private static final String FRAMEWORK_ADAPTER = "android.bluetooth.BluetoothAdapter";

    /** How often the watchdog re-checks the live scan mode. */
    private static final long POLL_INTERVAL_MS = 5_000L;

    /** Minimum gap between two re-assert attempts, so a chatty callback cannot loop. */
    private static final long RETRY_INTERVAL_MS = 1_000L;

    /** How long the stack gets to answer a request before the probe moves to the next encoding. */
    private static final long CONFIRM_GRACE_MS = 1_500L;

    /**
     * How long after the adapter came up the mode is still whatever the enable sequence left
     * behind. Nothing during that window is a decision anybody made, the user included.
     */
    private static final long ENABLE_GRACE_MS = 10_000L;

    /** {@code BluetoothAdapter.STATE_ON}. */
    private static final int STATE_ON = 12;

    /** Caller identity used when the module asks for the mode itself. */
    private static final String FALLBACK_PACKAGE = "com.android.bluetooth";

    /**
     * Reflection handles of one instance, either {@code AdapterProperties} or
     * {@code AdapterService}. Only methods declared by that class itself are collected: the
     * binder stubs are nested classes whose methods cannot be invoked on this object.
     */
    private static final class Accessors {
        @NonNull
        final Object target;
        /** {@code AdapterProperties#setScanMode} takes whatever the ROM feeds it, which on
         * Android 9-12 is the HAL value, not the framework one. */
        final boolean properties;
        @Nullable
        Method setMode;
        /** True when the setter is {@code setScanMode(int, String)}. */
        boolean setModeTakesPackage;
        /** {@code AdapterService#setScanMode(int mode, int duration)} - Android 9-12. */
        @Nullable
        Method setModeWithTimeout;
        @Nullable
        Method setTimeout;
        @Nullable
        Method readMode;
        @Nullable
        Method readState;

        Accessors(@NonNull Object target) {
            this.target = target;
            this.properties = target.getClass().getName().endsWith("AdapterProperties");
            Method[] methods;
            try {
                methods = target.getClass().getDeclaredMethods();
            } catch (Throwable t) {
                methods = target.getClass().getMethods();
            }
            for (Method method : methods) {
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // still reachable through the hook chain even if reflection is blocked
                }
                String name = Hooks.lower(method.getName());
                Class<?>[] params = method.getParameterTypes();
                if (name.equals("setscanmode")) {
                    if (params.length == 1 && isNumeric(params[0])) {
                        setMode = method;
                    } else if (params.length == 2 && isNumeric(params[0])
                            && params[1] == String.class) {
                        setMode = method;
                        setModeTakesPackage = true;
                    } else if (params.length == 2 && isNumeric(params[0]) && isNumeric(params[1])) {
                        setModeWithTimeout = method;
                    }
                } else if (name.equals("setdiscoverabletimeout") && params.length == 1
                        && isNumeric(params[0])) {
                    setTimeout = method;
                } else if (name.equals("getscanmode") && params.length == 0
                        && isNumeric(method.getReturnType())) {
                    readMode = method;
                } else if (name.equals("getstate") && params.length == 0
                        && isNumeric(method.getReturnType())) {
                    readState = method;
                }
            }
        }

        boolean usable() {
            return setMode != null || setModeWithTimeout != null;
        }
    }

    /** Process state of the pin; only meaningful inside com.android.bluetooth. */
    private static final class Pin {
        /**
         * Permanent discoverable is the default posture rather than a reaction, so a phone
         * nobody ever opened the pairing page for is still findable. An explicit request
         * from another UID clears it until Bluetooth is switched back on.
         */
        volatile boolean wanted = true;
        /** The value that this ROM's {@code AdapterService} understood as "discoverable". */
        volatile int discoverableArg = Hooks.SCAN_MODE_DISCOVERABLE;
        /** Only used to log the encoding once, so a confirmed default stays quiet. */
        volatile boolean serviceArgSeen;
        /** The encoding behind {@link #discoverableArg} has been seen take effect. */
        volatile boolean proven;
        /** Index into the current probe, so every attempt is not the same refused guess. */
        volatile int step;
        /** Writes issued, so the probe only narrates its first pass. */
        volatile int attempts;
        /** The write whose effect the next pass is supposed to see, once anything is in flight. */
        @Nullable
        volatile Attempt pending;
        /** A full pass over the probe without the live mode turning discoverable. */
        volatile long exhaustedMs;
        /** {@code AdapterProperties} may want the HAL encoding, so it is only used once seen. */
        volatile int propertiesArg;
        volatile boolean propertiesArgLearned;
        /** {@code BluetoothAdapter.getDefaultAdapter()} - the public door is the safest ask. */
        @Nullable
        volatile Method adapterGetter;
        @Nullable
        volatile Method adapterSetter;
        volatile boolean adapterSetterTakesDuration;
        @Nullable
        volatile Object adapter;
        @Nullable
        volatile Accessors properties;
        @Nullable
        volatile Accessors service;
        volatile long lastAttemptMs;
        /** When the adapter last reached {@code STATE_ON}, for as long as NONE is its own doing. */
        volatile long lastEnableMs;
        /** Keeps the "nothing reachable" warning to one line per process. */
        volatile boolean complained;
    }

    /** Which layer a probe step asks, in the order the encodings are documented to belong to. */
    private static final int LAYER_ADAPTER = 0;
    private static final int LAYER_SERVICE = 1;
    /** Its candidate index is ignored: that layer only takes the value the ROM was seen using. */
    private static final int LAYER_PROPERTIES = 2;

    /** One discoverable request: a layer, and the encoding handed to it. */
    private static final class Attempt {
        final int layer;
        final int arg;
        final long startedMs;

        Attempt(int layer, int arg, long startedMs) {
            this.layer = layer;
            this.arg = arg;
            this.startedMs = startedMs;
        }

        /** {@code AdapterProperties} only holds what the stack last reported, so a write there
         * that the read agrees with would prove nothing - it must not retire the pin. */
        boolean provable() {
            return layer != LAYER_PROPERTIES;
        }

        String describe() {
            if (layer == LAYER_ADAPTER) {
                return FRAMEWORK_ADAPTER;
            }
            return layer == LAYER_SERVICE ? "AdapterService" : "AdapterProperties";
        }
    }

    private static final Pin PIN = new Pin();

    @Nullable
    private static volatile Handler worker;
    @Nullable
    private static HandlerThread workerThread;

    private ScanModeHooks() {
    }

    /** The Bluetooth APK owns the pin, so the watchdog runs only there. */
    public static int installBluetoothApp(@NonNull Hooks h, @NonNull ClassLoader loader) {
        int installed = install(h, loader, true);
        if (installed > 0) {
            startWatchdog(h);
        }
        return installed;
    }

    /**
     * Best effort for ROMs that proxy scan mode through system_server. Nothing is asserted
     * here: the timeout is clamped on the way through and the Bluetooth APK stays the
     * single place that decides what "discoverable" means.
     */
    public static int installSystemServer(@NonNull Hooks h, @NonNull ClassLoader loader) {
        return install(h, loader, false);
    }

    private static int install(@NonNull Hooks h, @NonNull ClassLoader loader, boolean owner) {
        int installed = 0;
        for (Class<?> clazz : candidates(h, loader)) {
            installed += clampTimeouts(h, clazz);
            installed += trackScanMode(h, clazz, owner);
            if (owner) {
                installed += watchDemotions(h, clazz);
                installed += captureInstances(h, clazz);
                if (clazz != null && FRAMEWORK_ADAPTER.equals(clazz.getName())) {
                    resolveAdapterApi(clazz);
                }
            }
        }
        if (installed == 0) {
            h.log(Log.WARN, "discoverable: no setScanMode / setDiscoverableTimeout method found "
                    + "in " + (owner ? BTSERVICE_PACKAGE : "system_server"));
        } else if (owner) {
            h.log(Log.INFO, "discoverable: " + installed + " scan-mode hook(s) in the Bluetooth "
                    + "APK; discoverable is asserted on its own, no pairing page needed");
        } else {
            h.log(Log.INFO, "discoverable: " + installed
                    + " scan-mode hook(s) in system_server (timeout clamp only)");
        }
        return installed;
    }

    @NonNull
    private static Class<?>[] candidates(@NonNull Hooks h, @NonNull ClassLoader loader) {
        Class<?> properties = h.findClass(loader, BTSERVICE_PACKAGE + ".AdapterProperties");
        Class<?> service = h.findClass(loader, BTSERVICE_PACKAGE + ".AdapterService");
        Class<?> adapter = h.findClass(loader, FRAMEWORK_ADAPTER);
        return new Class<?>[]{properties, service, adapter};
    }

    /** Any {@code set*DiscoverableTimeout*} overload: every numeric/Duration argument becomes 0. */
    private static int clampTimeouts(@NonNull Hooks h, @Nullable Class<?> clazz) {
        if (clazz == null) {
            return 0;
        }
        return h.hookMethods(clazz, "scan.timeout", method ->
                Hooks.lower(method.getName()).contains("setdiscoverabletimeout"), chain -> {
            Object[] args = chain.getArgs().toArray();
            boolean changed = false;
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Integer) {
                    args[i] = 0;
                    changed = true;
                } else if (arg instanceof Long) {
                    args[i] = 0L;
                    changed = true;
                } else if (arg instanceof Duration) {
                    args[i] = Duration.ZERO;
                    changed = true;
                } else if (arg == null && Duration.class.isAssignableFrom(
                        chain.getExecutable().getParameterTypes()[i])) {
                    args[i] = Duration.ZERO;
                    changed = true;
                }
            }
            if (changed && h.isVerbose()) {
                h.verbose("discoverable: timeout clamped to 0 in "
                        + Hooks.describe(chain.getExecutable()));
            }
            return chain.proceed(args);
        });
    }

    /**
     * {@code setScanMode(int mode, int timeout)} overloads get their timeout clamped too,
     * and this is where the module learns whichever mode value the ROM uses for discoverable
     * ({@code 23} since Android 13, the HAL value {@code 2} before that).
     */
    private static int trackScanMode(@NonNull Hooks h, @Nullable Class<?> clazz, boolean owner) {
        if (clazz == null) {
            return 0;
        }
        return h.hookMethods(clazz, "scan.mode", method ->
                Hooks.lower(method.getName()).contains("setscanmode"), chain -> {
            Object self = chain.getThisObject();
            Object[] args = chain.getArgs().toArray();
            List<Integer> numeric = Hooks.numericArgIndexes(Arrays.asList(args));
            if (numeric.isEmpty()) {
                return chain.proceed();
            }
            int mode = ((Number) args[numeric.get(0)]).intValue();
            if (numeric.size() >= 2 && Hooks.isDiscoverableMode(mode)) {
                int timeoutIndex = numeric.get(numeric.size() - 1);
                args[timeoutIndex] = args[timeoutIndex] instanceof Long ? (Object) 0L : (Object) 0;
                if (h.isVerbose()) {
                    h.verbose("discoverable: duration forced to 0 in "
                            + Hooks.describe(chain.getExecutable()));
                }
            }
            if (owner) {
                learn(h, self, mode, chain.getExecutable());
            }
            return chain.proceed(args);
        });
    }

    /** Detect the native stack falling back to connectable once its own timeout expired. */
    private static int watchDemotions(@NonNull Hooks h, @Nullable Class<?> clazz) {
        if (clazz == null || !(BTSERVICE_PACKAGE + ".AdapterProperties").equals(clazz.getName())) {
            return 0;
        }
        return h.hookMethods(clazz, "scan.propertyChanged", method ->
                Hooks.lower(method.getName()).contains("propertychangedcallback"), chain -> {
            Object result = chain.proceed();
            remember(chain.getThisObject());
            verify(h, "property changed");
            return result;
        });
    }

    /**
     * The enable callbacks are the only code guaranteed to run before anybody asks for a
     * scan mode, and they run on the object that can set one. Without them the module could
     * only ever stretch a window the pairing page had already opened.
     */
    private static int captureInstances(@NonNull Hooks h, @Nullable Class<?> clazz) {
        if (clazz == null) {
            return 0;
        }
        return h.hookMethods(clazz, "scan.capture", ScanModeHooks::isEnableCallback, chain -> {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            if (self == null) {
                return result;
            }
            if (remember(self)) {
                h.log(Log.INFO, "discoverable: holding " + self.getClass().getName()
                        + ", captured in " + Hooks.describe(chain.getExecutable()));
            }
            if (!PIN.wanted) {
                PIN.wanted = true;
                h.log(Log.INFO, "discoverable: re-pinned, the adapter came up");
            }
            // Whatever mode the enable sequence leaves behind only counts as a decision once the
            // stack has settled, so the grace window is stamped on every turn of the adapter.
            if (read(false) == STATE_ON) {
                PIN.lastEnableMs = System.currentTimeMillis();
            }
            verify(h, "adapter enable");
            return result;
        });
    }

    private static boolean isEnableCallback(@NonNull Method method) {
        String name = Hooks.lower(method.getName());
        return name.equals("onadapterenabled") || name.equals("processprofileservicestatechanged")
                || name.equals("setstate") || name.equals("setadapterstate")
                || name.equals("oncreate");
    }

    /** @return true the first time this particular instance is stored. */
    private static boolean remember(@Nullable Object self) {
        if (self == null) {
            return false;
        }
        String name = self.getClass().getName();
        boolean isProperties = name.endsWith("AdapterProperties");
        if (!isProperties && !name.endsWith("AdapterService")) {
            return false;
        }
        Accessors current = isProperties ? PIN.properties : PIN.service;
        if (current != null && current.target == self) {
            return false;
        }
        if (isProperties) {
            PIN.properties = new Accessors(self);
        } else {
            PIN.service = new Accessors(self);
            // AdapterProperties is a field of the service; take it while we are here.
            Object properties = findPropertiesField(self);
            if (properties != null
                    && (PIN.properties == null || PIN.properties.target != properties)) {
                PIN.properties = new Accessors(properties);
            }
        }
        return true;
    }

    @Nullable
    private static Object findPropertiesField(@NonNull Object service) {
        for (Class<?> clazz = service.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            Field[] fields;
            try {
                fields = clazz.getDeclaredFields();
            } catch (Throwable t) {
                return null;
            }
            for (Field field : fields) {
                if (!field.getType().getName().endsWith("AdapterProperties")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(service);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // keep looking: the service setter alone is enough
                }
            }
        }
        return null;
    }

    private static void learn(@NonNull Hooks h, @Nullable Object self, int mode,
                              @NonNull Executable executable) {
        boolean fromAnotherApp = Binder.getCallingUid() != Process.myUid();
        if (Hooks.isDiscoverableMode(mode)) {
            // Each layer has its own encoding: AdapterService takes the framework value and
            // converts, AdapterProperties takes whatever the conversion produced - which on
            // Android 9-12 is the HAL value 2. Guessing one for the other is silently dropped
            // by the stack, so both are learned from the ROM's own calls.
            if (executable.getDeclaringClass().getName().endsWith("AdapterProperties")) {
                if (!PIN.propertiesArgLearned || PIN.propertiesArg != mode) {
                    PIN.propertiesArg = mode;
                    PIN.propertiesArgLearned = true;
                    h.log(Log.INFO, "discoverable: AdapterProperties takes " + mode
                            + " for discoverable (" + Hooks.describe(executable) + ")");
                }
            } else if (!PIN.serviceArgSeen || PIN.discoverableArg != mode) {
                PIN.discoverableArg = mode;
                PIN.serviceArgSeen = true;
                // the ROM asked for this value itself, which beats anything the probe concludes
                PIN.proven = true;
                PIN.step = 0;
                h.log(Log.INFO, "discoverable: "
                        + executable.getDeclaringClass().getSimpleName() + " takes " + mode
                        + " for discoverable (" + Hooks.describe(executable) + ")");
            }
            if (!PIN.wanted) {
                PIN.wanted = true;
                h.log(Log.INFO, "discoverable: re-pinned by a discoverable request from uid "
                        + Binder.getCallingUid());
            }
            remember(self);
            startWatchdog(h);
            return;
        }
        if (Hooks.isNoneMode(mode)) {
            // The Bluetooth process reaches NONE twice on its own: once inside the enable
            // sequence and once as the tail of a discoverable window. Neither is a decision, so
            // only a request from another UID unpins - switching Bluetooth off is covered by the
            // STATE_ON gate in verify, and the posture comes back with the next enable.
            if (fromAnotherApp) {
                unpin(h, "uid " + Binder.getCallingUid() + " requested scan mode " + mode + " via "
                        + Hooks.describe(executable));
            }
            return;
        }
        if (fromAnotherApp) {
            unpin(h, "uid " + Binder.getCallingUid() + " requested scan mode " + mode + " via "
                    + Hooks.describe(executable));
        }
        // Otherwise it is the in-process demotion that follows a timeout: the watchdog fixes it.
    }

    /** Drop the posture until Bluetooth comes back; only a change of state is worth a line. */
    private static void unpin(@NonNull Hooks h, @NonNull String reason) {
        boolean wasWanted = PIN.wanted;
        PIN.wanted = false;
        PIN.pending = null;
        if (wasWanted) {
            h.log(Log.INFO, "discoverable: unpinned, " + reason
                    + "; re-pinned when Bluetooth is on again");
        }
    }

    private static synchronized void startWatchdog(@NonNull Hooks h) {
        if (worker != null) {
            return;
        }
        try {
            workerThread = new HandlerThread("btunlock-scan-pin");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());
        } catch (Throwable t) {
            h.log(Log.WARN, "discoverable: the watchdog thread could not start", t);
            return;
        }
        h.log(Log.INFO, "discoverable: watchdog armed, checking the live scan mode every "
                + POLL_INTERVAL_MS / 1000L + " s");
        schedule(h, nextDelayMs());
    }

    /**
     * A write is answered by the stack inside {@link #CONFIRM_GRACE_MS}, so the passes around a
     * probe run close together; with nothing in flight the watchdog goes back to polling.
     */
    private static long nextDelayMs() {
        return PIN.pending != null ? RETRY_INTERVAL_MS : POLL_INTERVAL_MS;
    }

    private static void schedule(@NonNull Hooks h, long delayMs) {
        Handler handler = worker;
        if (handler == null) {
            return;
        }
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> tick(h), delayMs);
    }

    private static void tick(@NonNull Hooks h) {
        verify(h, "watchdog");
        schedule(h, nextDelayMs());
    }

    /** Re-assert discoverable with a zero timeout if the live scan mode lost it. */
    private static void verify(@NonNull Hooks h, String reason) {
        if (!PIN.wanted) {
            return;
        }
        int live = read(true);
        if (live < 0) {
            return; // no instance captured yet, or the stack has no scan mode to report
        }
        long now = System.currentTimeMillis();
        if (Hooks.isDiscoverableMode(live)) {
            if (confirm(h, live)) {
                return;
            }
            // only AdapterProperties answered, and that is a mirror of the stack, not the radio
        } else {
            reject(h, now);
        }
        int state = read(false);
        if (state >= 0 && state != STATE_ON) {
            return; // adapter off or still coming up: nothing to defend, and never re-enable
        }
        if (Hooks.isNoneMode(live) && now - PIN.lastEnableMs < ENABLE_GRACE_MS) {
            return; // NONE right after an enable is the startup, not somebody's decision
        }
        if (now - PIN.lastAttemptMs < RETRY_INTERVAL_MS) {
            return;
        }
        PIN.lastAttemptMs = now;
        reapply(h, live, reason);
    }

    /**
     * @return true when the live mode is one a real setter produced. A write to
     *     {@code AdapterProperties} only moves the mirror the read looks at, so it can never be
     *     the evidence that stops the asserting.
     */
    private static boolean confirm(@NonNull Hooks h, int liveMode) {
        Attempt attempt = PIN.pending;
        if (attempt == null) {
            return PIN.proven;
        }
        if (!attempt.provable()) {
            return false;
        }
        PIN.pending = null;
        boolean changed = PIN.discoverableArg != attempt.arg;
        if (changed) {
            PIN.discoverableArg = attempt.arg;
            PIN.step = 0;
        }
        if (!PIN.proven || changed) {
            h.log(Log.INFO, "discoverable: " + attempt.arg + " through " + attempt.describe()
                    + " is what this ROM takes for discoverable (live mode " + liveMode + ")");
        }
        PIN.proven = true;
        PIN.exhaustedMs = 0L;
        return true;
    }

    /** The write in flight left the mode where it was, so the probe moves to the next encoding. */
    private static void reject(@NonNull Hooks h, long now) {
        Attempt attempt = PIN.pending;
        if (attempt == null || now - attempt.startedMs < CONFIRM_GRACE_MS) {
            return;
        }
        PIN.pending = null;
        PIN.step = (PIN.step + 1) % PLAN.length;
        if (PIN.step == 0 && (PIN.exhaustedMs == 0L || now - PIN.exhaustedMs > 60_000L)) {
            PIN.exhaustedMs = now;
            h.log(Log.WARN, "discoverable: no encoding reached the stack through "
                    + attempt.describe() + " (last try " + attempt.arg + ") and the live mode "
                    + "never turned discoverable, so the pin keeps probing");
        }
    }

    /**
     * @param mode true to read the scan mode, false to read the adapter state
     * @return the value, or -1 when neither instance can answer
     */
    private static int read(boolean mode) {
        Accessors[] order = {PIN.properties, PIN.service};
        for (Accessors accessors : order) {
            if (accessors == null) {
                continue;
            }
            Method method = mode ? accessors.readMode : accessors.readState;
            if (method == null) {
                continue;
            }
            try {
                Object value = method.invoke(accessors.target);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            } catch (Throwable ignored) {
                // try the other instance
            }
        }
        return -1;
    }

    /**
     * Probe order: the framework encoding first because that is what the documented setter takes,
     * the HAL encoding second because that is what the stack carries, {@code AdapterProperties}
     * last because it is a mirror rather than a door. The second column is an index into
     * {@link #discoverableValue(int)}, resolved per attempt since the ROM can re-educate it.
     */
    private static final int[][] PLAN = {
            {LAYER_ADAPTER, 0}, {LAYER_SERVICE, 0},
            {LAYER_ADAPTER, 1}, {LAYER_SERVICE, 1},
            {LAYER_PROPERTIES, 0},
    };

    /** The framework encoding the ROM uses, and the other one, which is what is worth probing. */
    private static int discoverableValue(int candidate) {
        int primary = PIN.discoverableArg;
        if (candidate == 0) {
            return primary;
        }
        return primary == Hooks.SCAN_MODE_DISCOVERABLE ? Hooks.HAL_SCAN_MODE_DISCOVERABLE
                : Hooks.SCAN_MODE_DISCOVERABLE;
    }

    /** @return the first step at or after {@code from} this device can actually reach, or -1 */
    private static int reachableStep(int from) {
        for (int offset = 0; offset < PLAN.length; offset++) {
            int step = (from + offset) % PLAN.length;
            if (reachable(step)) {
                return step;
            }
        }
        return -1;
    }

    private static boolean reachable(int step) {
        int layer = PLAN[step][0];
        if (layer == LAYER_PROPERTIES) {
            Accessors properties = PIN.properties;
            return properties != null && properties.setMode != null && PIN.propertiesArgLearned;
        }
        return setterFor(layer) != null;
    }

    /** @return the setter this device offers for that layer, or null when it does not have one */
    @Nullable
    private static Method setterFor(int layer) {
        if (layer == LAYER_ADAPTER) {
            return adapterInstance() == null ? null : PIN.adapterSetter;
        }
        Accessors service = PIN.service;
        if (service == null || !service.usable()) {
            return null;
        }
        return service.setModeWithTimeout != null ? service.setModeWithTimeout : service.setMode;
    }

    /**
     * Ask for discoverable through one layer of the probe and leave the answer to the next pass.
     * Every call goes out through the original body, so the module's own clamp cannot zero the
     * duration it chose and its own request cannot be mistaken for the ROM teaching an encoding.
     */
    private static void reapply(@NonNull Hooks h, int liveMode, String reason) {
        int step = reachableStep(PIN.step);
        if (step < 0) {
            if (!PIN.complained) {
                PIN.complained = true;
                h.log(Log.WARN, "discoverable: nothing reachable to set the mode with - no "
                        + "BluetoothAdapter / AdapterService setter and AdapterProperties has never "
                        + "been seen taking a discoverable value, so only timeouts are clamped here");
            }
            return;
        }
        PIN.step = step;
        int layer = PLAN[step][0];
        // that layer only ever takes what the ROM was seen passing it, so it ignores the candidate
        int arg = layer == LAYER_PROPERTIES ? PIN.propertiesArg : discoverableValue(PLAN[step][1]);
        Attempt attempt = new Attempt(layer, arg, System.currentTimeMillis());
        PIN.pending = attempt;
        PIN.attempts++;
        boolean applied;
        if (layer == LAYER_ADAPTER) {
            applied = applyViaPublicAdapter(h, attempt, liveMode, reason);
        } else if (layer == LAYER_SERVICE) {
            applied = applyViaService(h, attempt, liveMode, reason);
        } else {
            applied = applyViaProperties(h, attempt, liveMode, reason);
        }
        if (!applied) {
            // a setter that threw is not worth a grace period: move on in the same pass
            PIN.pending = null;
            PIN.step = (step + 1) % PLAN.length;
        }
    }

    /** @return true when {@code BluetoothAdapter#setScanMode} carried the request. */
    private static boolean applyViaPublicAdapter(@NonNull Hooks h, @NonNull Attempt attempt,
                                                 int liveMode, String reason) {
        Method setter = setterFor(LAYER_ADAPTER);
        Object adapter = adapterInstance();
        if (setter == null || adapter == null) {
            return false;
        }
        try {
            if (PIN.adapterSetterTakesDuration) {
                h.invokeOriginal(setter, adapter, attempt.arg, zeroFor(setter, 1));
            } else {
                h.invokeOriginal(setter, adapter, attempt.arg);
            }
            logAsserted(h, attempt, setter, liveMode, reason);
            return true;
        } catch (Throwable t) {
            h.log(Log.WARN, "discoverable: the public setScanMode did not take", t);
            return false;
        }
    }

    /** The service takes framework values and does the HAL conversion itself. */
    private static boolean applyViaService(@NonNull Hooks h, @NonNull Attempt attempt,
                                           int liveMode, String reason) {
        Accessors service = PIN.service;
        if (service == null || !service.usable()) {
            return false;
        }
        try {
            Method setter = service.setModeWithTimeout != null ? service.setModeWithTimeout
                    : service.setMode;
            if (service.setModeWithTimeout != null) {
                h.invokeOriginal(setter, service.target, attempt.arg, zeroFor(setter, 1));
            } else {
                setTimeout(h, service);
                Object[] args = service.setModeTakesPackage
                        ? new Object[]{attempt.arg, selfPackage()}
                        : new Object[]{attempt.arg};
                h.invokeOriginal(setter, service.target, args);
            }
            logAsserted(h, attempt, setter, liveMode, reason);
            return true;
        } catch (Throwable t) {
            h.log(Log.WARN, "discoverable: asserting through AdapterService failed", t);
            return false;
        }
    }

    /** Only ever used with the value this ROM was observed passing to that very method. */
    private static boolean applyViaProperties(@NonNull Hooks h, @NonNull Attempt attempt,
                                              int liveMode, String reason) {
        Accessors properties = PIN.properties;
        if (properties == null || !properties.properties || !PIN.propertiesArgLearned
                || properties.setMode == null) {
            return false;
        }
        try {
            setTimeout(h, properties);
            h.invokeOriginal(properties.setMode, properties.target, attempt.arg);
            logAsserted(h, attempt, properties.setMode, liveMode, reason);
            return true;
        } catch (Throwable t) {
            h.log(Log.WARN, "discoverable: asserting through AdapterProperties failed", t);
            return false;
        }
    }

    private static void setTimeout(@NonNull Hooks h, @NonNull Accessors target) throws Throwable {
        if (target.setTimeout != null) {
            h.invokeOriginal(target.setTimeout, target.target, zeroFor(target.setTimeout, 0));
        }
    }

    /** @return a zero of exactly the type {@code method}'s parameter at {@code index} wants */
    @NonNull
    private static Object zeroFor(@NonNull Method method, int index) {
        Class<?> type = method.getParameterTypes()[index];
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        return 0;
    }

    /**
     * The first pass over the probe is the diagnosis worth keeping, so later repeats of the same
     * request only reach the verbose log.
     */
    private static void logAsserted(@NonNull Hooks h, @NonNull Attempt attempt,
                                    @NonNull Method setter, int liveMode, String reason) {
        String line = "discoverable: asserted mode " + attempt.arg + " through "
                + Hooks.describe(setter) + " with timeout 0 (found " + liveMode + ", "
                + reason + ")";
        if (PIN.attempts <= PLAN.length) {
            h.log(Log.INFO, line);
        } else {
            h.verbose(line);
        }
    }

    @Nullable
    private static synchronized Object adapterInstance() {
        Object cached = PIN.adapter;
        if (cached != null) {
            return cached;
        }
        Method getter = PIN.adapterGetter;
        if (getter == null) {
            return null;
        }
        try {
            cached = getter.invoke(null);
        } catch (Throwable t) {
            return null;
        }
        PIN.adapter = cached;
        return cached;
    }

    /**
     * The public {@code BluetoothAdapter#setScanMode} is the same door the pairing page uses and
     * the only one whose argument is documented, so it is tried first. The overload set moved in
     * Android 13 ({@code (int, int)} &rarr; {@code (int)}), hence the shape test instead of a
     * direct call.
     */
    private static void resolveAdapterApi(@NonNull Class<?> clazz) {
        Method withDuration = null;
        Method plain = null;
        Method getter = null;
        for (Method method : declaredMethods(clazz)) {
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                // reflection is a convenience here, the hooks do not need it
            }
            String name = Hooks.lower(method.getName());
            Class<?>[] params = method.getParameterTypes();
            if (name.equals("setscanmode") && params.length == 2 && isNumeric(params[0])
                    && isNumeric(params[1])) {
                withDuration = method;
            } else if (name.equals("setscanmode") && params.length == 1 && isNumeric(params[0])) {
                plain = method;
            } else if (name.equals("getdefaultadapter") && params.length == 0
                    && Modifier.isStatic(method.getModifiers())) {
                getter = method;
            }
        }
        PIN.adapterGetter = getter;
        PIN.adapterSetter = withDuration != null ? withDuration : plain;
        PIN.adapterSetterTakesDuration = withDuration != null;
    }

    @NonNull
    private static Method[] declaredMethods(@NonNull Class<?> clazz) {
        try {
            return clazz.getDeclaredMethods();
        } catch (Throwable t) {
            try {
                return clazz.getMethods();
            } catch (Throwable ignored) {
                return new Method[0];
            }
        }
    }

    /** Caller identity used when the module asks for the mode itself. */
    @NonNull
    private static String selfPackage() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                String name = Application.getProcessName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // the process name is a convenience: the setter only uses it for attribution
        }
        return FALLBACK_PACKAGE;
    }

    private static boolean isNumeric(@NonNull Class<?> type) {
        return type == int.class || type == Integer.class || type == long.class
                || type == Long.class;
    }
}
