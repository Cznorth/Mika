package com.wangzan101.mika.routing;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Android-free policy. Caller serializes mutations with ATMS's global lock. */
final class SessionRoutes {
    record App(String packageName, int user) {
        App { Objects.requireNonNull(packageName); if (packageName.isBlank() || user < 0) throw new IllegalArgumentException(); }
    }
    static final class Lease {
        final Object token;
        final int display;
        final String identity;
        volatile boolean closing;
        volatile boolean revoked;
        Lease(Object token, int display, String identity) {
            if (display <= 0 || identity == null || identity.isBlank()) throw new IllegalArgumentException("invalid virtual display");
            this.token = Objects.requireNonNull(token); this.display = display; this.identity = identity;
        }
        void requireLive(String actualIdentity, int group) {
            if (revoked || closing || group <= 0 || !identity.equals(actualIdentity)) {
                throw new SecurityException("Mika virtual display lease unavailable");
            }
        }
    }
    private final Map<Object, Lease> leases = new HashMap<>();
    private final Map<App, Lease> apps = new HashMap<>();
    private final Map<Integer, Lease> uids = new HashMap<>();

    synchronized Lease open(Object token, int display, String identity) {
        if (leases.containsKey(token) || leases.values().stream().anyMatch(l -> l.display == display && !l.revoked)) {
            throw new SecurityException("display already leased");
        }
        Lease lease = new Lease(token, display, identity); leases.put(token, lease); return lease;
    }
    synchronized Lease get(Object token) {
        Lease lease = leases.get(token);
        if (lease == null) throw new SecurityException("unknown lease");
        return lease;
    }
    synchronized void bind(Lease lease, App app, int uid) {
        if (lease.revoked || lease.closing || uid % 100000 < 10000 || uid / 100000 != app.user) throw new SecurityException("invalid app lease");
        Lease previous = apps.get(app), process = uids.get(uid);
        // Reclaim only an explicitly rebound package/UID after the system checked that no
        // activities exist outside the new display. Other packages retain their deny binding.
        if ((previous != null && previous != lease && !previous.revoked) ||
                (process != null && process != lease && !process.revoked)) {
            throw new SecurityException("app/UID belongs to another virtual session");
        }
        apps.put(app, lease); uids.put(uid, lease);
    }
    synchronized Lease app(String pkg, int user) { return apps.get(new App(pkg, user)); }
    synchronized Lease uid(int uid) { return uids.get(uid); }
    synchronized Lease resolve(String pkg, int user, int callingUid, Lease source) {
        Lease target = app(pkg, user), caller = uid(callingUid);
        Lease found = target != null ? target : (source != null ? source : caller);
        if ((source != null && found != source) || (caller != null && found != caller)) {
            throw new SecurityException("cross-session activity launch");
        }
        return found;
    }
    synchronized void revoke(Lease lease) { lease.revoked = true; } // Keep bindings: death must not silently unconfine a running app.
    synchronized void close(Lease lease, boolean tasksEmpty) {
        if (!tasksEmpty) throw new SecurityException("tasks must be removed before unbinding");
        apps.values().removeIf(l -> l == lease); uids.values().removeIf(l -> l == lease); leases.remove(lease.token);
    }
    synchronized boolean isEmpty() { return leases.isEmpty(); }
}
