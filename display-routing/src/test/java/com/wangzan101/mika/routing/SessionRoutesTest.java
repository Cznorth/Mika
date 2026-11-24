package com.wangzan101.mika.routing;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionRoutesTest {
    private final SessionRoutes routes = new SessionRoutes();
    private final SessionRoutes.App app = new SessionRoutes.App("com.MobileTicket", 0);
    private SessionRoutes.Lease session(int display) { return routes.open(new Object(), display, "virtual:" + display); }
    @Test public void targetIsPinnedForSourceLessNewTasks() {
        var lease = session(14); routes.bind(lease, app, 10582);
        assertSame(lease, routes.resolve(app.packageName(), 0, 1000, null));
    }
    @Test public void appProcessAndSourcePropagateSessionToNewPackages() {
        var lease = session(14); routes.bind(lease, app, 10582);
        assertSame(lease, routes.resolve("another.app", 0, 10582, null));
        assertSame(lease, routes.resolve("another.app", 0, 1000, lease));
    }
    @Test public void otherUsersAndUnboundCallersStayUntouched() {
        var lease = session(14); routes.bind(lease, app, 10582);
        assertNull(routes.resolve(app.packageName(), 10, 1010582, null));
        assertNull(routes.resolve("another.app", 0, 2000, null));
    }
    @Test public void crossSessionLaunchAndUidAliasAreRejected() {
        var a = session(14); var b = session(15);
        routes.bind(a, app, 10582); routes.bind(b, new SessionRoutes.App("another.app", 0), 10583);
        assertThrows(SecurityException.class, () -> routes.resolve("another.app", 0, 10582, a));
        assertThrows(SecurityException.class, () -> routes.bind(b, new SessionRoutes.App("shared.uid", 0), 10582));
    }
    @Test public void invalidReusedOrDefaultGroupDisplayNeverFallsBack() {
        var lease = session(14);
        lease.requireLive("virtual:14", 2);
        assertThrows(SecurityException.class, () -> lease.requireLive("virtual:99", 2));
        assertThrows(SecurityException.class, () -> lease.requireLive("virtual:14", 0));
        assertThrows(SecurityException.class, () -> lease.requireLive(null, 2));
        assertThrows(IllegalArgumentException.class, () -> session(0));
    }
    @Test public void helperDeathRetainsFailClosedBindings() {
        var lease = session(14); routes.bind(lease, app, 10582); routes.revoke(lease);
        assertSame(lease, routes.resolve(app.packageName(), 0, 1000, null));
        assertThrows(SecurityException.class, () -> lease.requireLive("virtual:14", 2));
    }
    @Test public void closeCannotUnbindBeforeTasksHaveGone() {
        var lease = session(14); routes.bind(lease, app, 10582); lease.closing = true;
        assertThrows(SecurityException.class, () -> lease.requireLive("virtual:14", 2));
        assertThrows(SecurityException.class, () -> routes.close(lease, false));
        assertSame(lease, routes.app(app.packageName(), 0));
        routes.close(lease, true);
        assertNull(routes.app(app.packageName(), 0)); assertTrue(routes.isEmpty());
    }
    @Test public void rootMayExplicitlyRebindARevokedPackageWithoutFreeingItsOtherPackages() {
        var old = session(14); routes.bind(old, app, 10582);
        routes.bind(old, new SessionRoutes.App("session.child", 0), 10583);
        routes.revoke(old);
        var replacement = session(15); routes.bind(replacement, app, 10582);
        assertSame(replacement, routes.resolve(app.packageName(), 0, 1000, null));
        assertSame(old, routes.resolve("session.child", 0, 1000, null));
    }
    @Test public void systemUidsAndMismatchedUsersCannotBeBound() {
        var lease = session(14);
        assertThrows(SecurityException.class, () -> routes.bind(lease, app, 1000));
        assertThrows(SecurityException.class, () -> routes.bind(lease, app, 1010582));
        assertThrows(SecurityException.class, () -> routes.bind(lease, new SessionRoutes.App("system", 1), 101000));
    }
}
