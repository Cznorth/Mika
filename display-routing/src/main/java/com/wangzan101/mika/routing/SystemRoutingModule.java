package com.wangzan101.mika.routing;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** System-server-only enforcement. No task polling and no after-the-fact move to another screen. */
public final class SystemRoutingModule implements IXposedHookLoadPackage {
    private static final String TAG = "MikaRouting";
    private final SessionRoutes routes = new SessionRoutes();
    private final ThreadLocal<SessionRoutes.Lease> launching = new ThreadLocal<>();
    private final ConcurrentHashMap<IBinder, IBinder.DeathRecipient> deaths = new ConcurrentHashMap<>();
    private volatile Object service;
    private volatile boolean ready;
    private Class<?> activityClass, taskClass;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!"android".equals(pkg.packageName) || !"android".equals(pkg.processName)) return;
        try {
            ClassLoader loader = pkg.classLoader;
            activityClass = cls("com.android.server.wm.ActivityRecord", loader);
            taskClass = cls("com.android.server.wm.Task", loader);
            Class<?> atms = cls("com.android.server.wm.ActivityTaskManagerService", loader);
            validateLayout(loader, atms);
            XposedBridge.hookAllConstructors(atms, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { service = p.thisObject; }
            });
            installLaunchHooks(loader);
            installPlacementHooks(loader);
            installWakeHooks(loader);
            // Hook only this service's actual Stub dispatch, never Binder.execTransact globally.
            Method dispatch = inherited(atms, "onTransact", int.class, Parcel.class, Parcel.class, int.class);
            XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if ((int) p.args[0] != RoutingProtocol.TRANSACTION || !atms.isInstance(p.thisObject)) return;
                    Parcel data = (Parcel) p.args[1], reply = (Parcel) p.args[2];
                    if (reply == null) { p.setResult(false); return; }
                    try {
                        if (Binder.getCallingUid() != 0) throw new SecurityException("Mika routing requires root");
                        data.enforceInterface(RoutingProtocol.DESCRIPTOR);
                        int op = data.readInt();
                        Object lock = field(p.thisObject, "mGlobalLock");
                        synchronized (lock) { transact(op, data, reply); }
                    } catch (Exception error) {
                        reply.setDataSize(0); reply.setDataPosition(0); reply.writeException(error);
                    }
                    p.setResult(true);
                }
            });
            ready = true;
            Log.i(TAG, "system launch, placement and wake hooks installed; protocol=" + RoutingProtocol.VERSION);
        } catch (Throwable error) {
            ready = false; // A partial install must never authorize the Mirage fallback.
            XposedBridge.log(TAG + ": disabled (unsupported system hooks): " + error);
        }
    }

    private void validateLayout(ClassLoader loader, Class<?> atms) throws Exception {
        for (String name : new String[]{"mGlobalLock", "mRootWindowContainer", "mContext"}) XposedHelpers.findField(atms, name);
        for (String name : new String[]{"info", "launchedFromUid", "intent"}) XposedHelpers.findField(activityClass, name);
        Class<?> info = cls("android.view.DisplayInfo", loader);
        for (String name : new String[]{"type", "ownerUid", "flags", "displayGroupId", "uniqueId", "name", "removeMode"}) {
            XposedHelpers.findField(info, name);
        }
        Class<?> token = cls("android.window.WindowContainerToken", loader);
        inherited(ActivityOptions.class, "setLaunchTaskDisplayArea", token);
        inherited(ActivityOptions.class, "setLaunchRootTask", token);
        inherited(ActivityOptions.class, "setLaunchTaskId", int.class);
        inherited(activityClass, "setTurnScreenOn", boolean.class);
        Class<?> container = cls("com.android.server.wm.WindowContainer", loader);
        Class<?> remote = XposedHelpers.findField(container, "mRemoteToken").getType();
        inherited(remote, "toWindowContainerToken");
        inherited(container, "getDisplayContent");
        inherited(container, "forAllActivities", Consumer.class);
        inherited(cls("com.android.server.power.PowerGroup", loader), "getGroupId");
    }

    private void transact(int op, Parcel data, Parcel reply) throws Exception {
        if (!ready || service == null) throw new IllegalStateException("routing hooks unavailable");
        if (op == RoutingProtocol.HEALTH) {
            reply.writeNoException(); reply.writeInt(RoutingProtocol.VERSION); return;
        }
        IBinder token = data.readStrongBinder();
        if (token == null || !token.isBinderAlive()) throw new SecurityException("dead session owner");
        if (op == RoutingProtocol.OPEN) {
            int display = data.readInt(); String identity = data.readString();
            validateDisplay(display, identity);
            SessionRoutes.Lease lease = routes.open(token, display, identity);
            IBinder.DeathRecipient death = () -> {
                synchronized (field(service, "mGlobalLock")) {
                    routes.revoke(lease);
                    Log.w(TAG, "owner died; retaining deny bindings for display=" + lease.display);
                }
            };
            try { token.linkToDeath(death, 0); deaths.put(token, death); }
            catch (Exception error) { routes.revoke(lease); throw error; }
        } else {
            SessionRoutes.Lease lease = routes.get(token);
            switch (op) {
                case RoutingProtocol.BIND -> {
                    live(lease);
                    String name = data.readString(); int user = data.readInt();
                    bind(lease, name, user);
                }
                case RoutingProtocol.CHECK -> live(lease);
                case RoutingProtocol.BEGIN_CLOSE -> lease.closing = true;
                case RoutingProtocol.CLOSE -> {
                    // An empty display is insufficient: also reject if any bound app has escaped.
                    for (Object activity : activities(root())) {
                        if (routeForActivity(activity) == lease || displayOf(activity) == lease.display) {
                            throw new SecurityException("session still owns activities");
                        }
                    }
                    routes.close(lease, true);
                    IBinder.DeathRecipient death = deaths.remove(token);
                    if (death != null) token.unlinkToDeath(death, 0);
                }
                default -> throw new IllegalArgumentException("unknown routing operation");
            }
        }
        reply.writeNoException(); reply.writeInt(RoutingProtocol.VERSION);
    }

    private void bind(SessionRoutes.Lease lease, String name, int user) throws Exception {
        // Package identity is resolved by system_server, not trusted from the root caller's options.
        if (name == null || name.equals("android") || name.equals("com.android.systemui")) {
            throw new SecurityException("system UI cannot be leased");
        }
        Context context = (Context) field(service, "mContext");
        int uid = (int) call(context.getPackageManager(), "getPackageUidAsUser", name, user);
        for (Object activity : activities(root())) {
            ActivityInfo info = (ActivityInfo) field(activity, "info");
            if (info.applicationInfo.uid == uid && displayOf(activity) != lease.display) {
                throw new SecurityException("app/UID already has activity outside virtual session");
            }
        }
        routes.bind(lease, new SessionRoutes.App(name, user), uid);
        Log.i(TAG, "bound " + name + " user=" + user + " uid=" + uid + " display=" + lease.display);
    }

    private void installLaunchHooks(ClassLoader loader) throws Exception {
        Class<?> starter = cls("com.android.server.wm.ActivityStarter", loader);
        Method inner = unique(starter, "startActivityInner");
        Class<?>[] types = inner.getParameterTypes();
        if (types.length < 7 || types[0] != activityClass || types[1] != activityClass || inner.getReturnType() != int.class) {
            throw new NoSuchMethodException("unsupported startActivityInner");
        }
        final int optionsIndex = index(types, ActivityOptions.class);
        XposedBridge.hookMethod(inner, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                SessionRoutes.Lease previous = launching.get();
                p.setObjectExtra("mika.previous", previous);
                if (routes.isEmpty()) return;
                try {
                    Object record = p.args[0], source = p.args[1];
                    SessionRoutes.Lease lease = resolve(record, source);
                    if (lease == null) return;
                    live(lease);
                    ActivityInfo info = (ActivityInfo) field(record, "info");
                    bind(lease, info.packageName, info.applicationInfo.uid / 100000);
                    ActivityOptions options = (ActivityOptions) p.args[optionsIndex];
                    if (options == null) options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(lease.display);
                    call(options, "setLaunchTaskDisplayArea", call(field(area(lease), "mRemoteToken"), "toWindowContainerToken"));
                    // Ignore any stale caller-supplied root-task/task selectors; task reuse is checked below.
                    call(options, "setLaunchRootTask", (Object) null);
                    call(options, "setLaunchTaskId", -1);
                    p.args[optionsIndex] = options;
                    for (int i = 0; i < types.length; i++) {
                        if (types[i] == taskClass && p.args[i] != null && displayOf(p.args[i]) != lease.display) {
                            throw new SecurityException("inTask belongs to another display");
                        }
                    }
                    Intent intent = (Intent) field(record, "intent");
                    intent.removeFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME);
                    call(record, "setTurnScreenOn", false);
                    launching.set(lease);
                    Log.i(TAG, "launch " + info.packageName + "/" + info.name + " display=" + lease.display);
                } catch (Throwable error) {
                    Log.w(TAG, "blocked activity before placement", error);
                    p.setResult(102); // ActivityManager.START_ABORTED; normal start-result cleanup still runs.
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                launching.set((SessionRoutes.Lease) p.getObjectExtra("mika.previous"));
            }
        });
        // Override the final controller output, after every AOSP and vendor launch modifier.
        // Android 17 no longer has getPreferredLaunchTaskDisplayArea; do not depend on that helper.
        Class<?> controller = cls("com.android.server.wm.LaunchParamsController", loader);
        Method calculate = unique(controller, "calculate");
        Class<?>[] calculateTypes = calculate.getParameterTypes();
        if (calculateTypes.length < 8 || calculateTypes[2] != activityClass || calculateTypes[3] != activityClass) {
            throw new NoSuchMethodException("unsupported launch params signature");
        }
        XposedBridge.hookMethod(calculate, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (routes.isEmpty()) return;
                try {
                    SessionRoutes.Lease lease = resolve(p.args[2], p.args[3]);
                    if (lease == null) lease = routeForTask(p.args[0]);
                    if (lease == null) return;
                    Object out = p.args[p.args.length - 1];
                    XposedHelpers.setObjectField(out, "mPreferredTaskDisplayArea", area(lease));
                    XposedHelpers.setIntField(out, "mWindowingMode", 1);
                } catch (Throwable error) { p.setThrowable(block(error)); }
            }
        });
        Method reusable = unique(starter, "resolveReusableTask");
        XposedBridge.hookMethod(reusable, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                SessionRoutes.Lease lease = launching.get();
                if (lease == null || p.hasThrowable() || p.getResult() == null) return;
                try {
                    live(lease);
                    if (displayOf(p.getResult()) != lease.display) p.setResult(null);
                } catch (Throwable error) { p.setThrowable(block(error)); }
            }
        });
    }

    private void installPlacementHooks(ClassLoader loader) throws Exception {
        Class<?> container = cls("com.android.server.wm.WindowContainer", loader);
        int guarded = 0;
        for (Method method : container.getDeclaredMethods()) {
            String name = method.getName();
            if ((!name.equals("reparent") && !name.equals("addChild")) || method.getParameterCount() < 1 ||
                    !container.isAssignableFrom(method.getParameterTypes()[0])) continue;
            boolean reparent = name.equals("reparent");
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (routes.isEmpty()) return;
                    Object child = reparent ? p.thisObject : p.args[0];
                    if (child == null || (!taskClass.isInstance(child) && !activityClass.isInstance(child))) return;
                    try {
                        SessionRoutes.Lease lease = taskClass.isInstance(child) ? routeForTask(child) : routeForActivity(child);
                        if (lease == null) lease = launching.get();
                        if (lease == null) return;
                        Object parent = reparent ? p.args[0] : p.thisObject;
                        if (parent == null) return; // Removal is allowed; adding to another display is not.
                        int destination = displayOf(parent);
                        if (destination >= 0 && destination != lease.display) {
                            throw new SecurityException("refusing task placement on display=" + destination + " expected=" + lease.display);
                        }
                        if (destination >= 0) live(lease);
                    } catch (Throwable error) { p.setThrowable(block(error)); }
                }
            });
            guarded++;
        }
        if (guarded < 2) throw new NoSuchMethodException("WindowContainer placement guards missing");
    }

    private void installWakeHooks(ClassLoader loader) throws Exception {
        Method canTurnOn = inherited(activityClass, "canTurnScreenOn");
        XposedBridge.hookMethod(canTurnOn, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (routes.isEmpty()) return;
                try { if (routeForActivity(p.thisObject) != null) p.setResult(false); }
                catch (Throwable error) { p.setResult(false); }
            }
        });
        Class<?> power = cls("com.android.server.power.PowerManagerService", loader);
        Method wake = unique(power, "wakePowerGroupLocked");
        Class<?>[] types = wake.getParameterTypes();
        if (types.length != 7 || types[1] != long.class || types[2] != int.class || types[4] != int.class || types[6] != int.class) {
            throw new NoSuchMethodException("unsupported power-group wake signature");
        }
        XposedBridge.hookMethod(wake, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (routes.isEmpty() || p.args[0] == null) return;
                try {
                    int group = (int) call(p.args[0], "getGroupId");
                    int reason = (int) p.args[2];
                    // Real power-button/gesture wakes remain the user's decision, even during a session.
                    if (group == 0 && reason == 2 && (launching.get() != null ||
                            routes.uid((int) p.args[4]) != null || routes.uid((int) p.args[6]) != null)) {
                        Log.w(TAG, "blocked session application wake of physical power group");
                        p.setResult(null);
                    }
                } catch (Throwable error) {
                    // Never break unrelated power handling. Startup validates this method shape.
                    Log.e(TAG, "wake attribution failed", error);
                }
            }
        });
    }

    private SessionRoutes.Lease resolve(Object target, Object source) {
        SessionRoutes.Lease fromSource = routeForActivity(source);
        if (fromSource == null) fromSource = launching.get();
        if (target == null) return fromSource;
        ActivityInfo info = (ActivityInfo) field(target, "info");
        return routes.resolve(info.packageName, info.applicationInfo.uid / 100000,
                (int) field(target, "launchedFromUid"), fromSource);
    }
    private SessionRoutes.Lease routeForActivity(Object record) {
        if (record == null) return null;
        ActivityInfo info = (ActivityInfo) field(record, "info");
        return routes.app(info.packageName, info.applicationInfo.uid / 100000);
    }
    private SessionRoutes.Lease routeForTask(Object task) {
        if (task == null) return null;
        SessionRoutes.Lease found = null;
        for (Object record : activities(task)) {
            SessionRoutes.Lease next = routeForActivity(record);
            if (next == null) continue;
            if (found != null && found != next) throw new SecurityException("mixed-session task");
            found = next;
        }
        return found;
    }
    private List<Object> activities(Object container) {
        List<Object> records = new ArrayList<>();
        call(container, "forAllActivities", (Consumer<Object>) records::add);
        return records;
    }
    private Object root() { return field(service, "mRootWindowContainer"); }
    private Object area(SessionRoutes.Lease lease) {
        live(lease);
        return call(call(root(), "getDisplayContent", lease.display), "getDefaultTaskDisplayArea");
    }
    private void live(SessionRoutes.Lease lease) {
        try { validateDisplay(lease.display, lease.identity); lease.requireLive(lease.identity, 1); }
        catch (Throwable error) { routes.revoke(lease); throw block(error); }
    }
    private void validateDisplay(int id, String identity) {
        if (id <= 0 || identity == null || identity.isBlank()) throw new SecurityException("invalid virtual display");
        Object dc = call(root(), "getDisplayContent", id);
        if (dc == null) throw new SecurityException("display removed");
        Object info = call(dc, "getDisplayInfo");
        int type = (int) field(info, "type"), owner = (int) field(info, "ownerUid");
        int flags = (int) field(info, "flags"), group = (int) field(info, "displayGroupId");
        int required = (1 << 7) | (1 << 8) | (1 << 12); // TRUSTED, OWN_DISPLAY_GROUP, STEAL_TOP_FOCUS_DISABLED
        if (type != 5 || owner != 0 || group <= 0 || !"mika-agent".equals(field(info, "name")) ||
                !identity.equals(field(info, "uniqueId")) || (flags & required) != required || (int) field(info, "removeMode") != 1) {
            throw new SecurityException("not a trusted root-owned independent Mika virtual display");
        }
    }
    private static int displayOf(Object node) {
        Object display = call(node, "getDisplayContent");
        return display == null ? -1 : (int) call(display, "getDisplayId");
    }
    private static Object field(Object object, String name) { return XposedHelpers.getObjectField(object, name); }
    private static Object call(Object object, String name, Object... args) { return XposedHelpers.callMethod(object, name, args); }
    private static Class<?> cls(String name, ClassLoader loader) { return XposedHelpers.findClass(name, loader); }
    private static SecurityException block(Throwable error) { return new SecurityException("Mika session routing refused", error); }
    private static int index(Class<?>[] types, Class<?> type) throws NoSuchMethodException {
        for (int i = 0; i < types.length; i++) if (types[i] == type) return i;
        throw new NoSuchMethodException(type.getName());
    }
    private static Method unique(Class<?> type, String name) throws NoSuchMethodException {
        Method found = null;
        for (Method method : type.getDeclaredMethods()) if (method.getName().equals(name)) {
            if (found != null) throw new NoSuchMethodException("ambiguous " + name);
            found = method;
        }
        if (found == null) throw new NoSuchMethodException(name);
        found.setAccessible(true); return found;
    }
    private static Method inherited(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method method = current.getDeclaredMethod(name, args); method.setAccessible(true); return method; }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }
}
