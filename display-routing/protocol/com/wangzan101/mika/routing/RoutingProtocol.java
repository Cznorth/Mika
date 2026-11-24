package com.wangzan101.mika.routing;

/** Private extension on the existing activity_task Binder; only UID 0 may call it. */
public final class RoutingProtocol {
    private RoutingProtocol() {}
    public static final String DESCRIPTOR = "com.wangzan101.mika.routing.v1";
    public static final int TRANSACTION = 0x004d4b52;
    public static final int VERSION = 1;
    public static final int HEALTH = 0;
    public static final int OPEN = 1;
    public static final int BIND = 2;
    public static final int BEGIN_CLOSE = 3;
    public static final int CLOSE = 4;
    public static final int CHECK = 5;
}
