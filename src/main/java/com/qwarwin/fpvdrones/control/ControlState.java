package com.qwarwin.fpvdrones.control;

// written by the client input handler, read by the drone entity on the pilot's client
public final class ControlState {
    public static volatile int controlledId = -1;

    public static float forward;
    public static float strafe;
    public static float vertical;
    public static boolean boost;
    public static float yaw;
    public static float pitch;

    public static void reset() {
        controlledId = -1;
        forward = strafe = vertical = 0F;
        boost = false;
    }

    private ControlState() {
    }
}
