package com.qwarwin.fpvdrones.entity;

public enum DroneKind {
    FPV,
    MAVIC,
    STRIKE;

    public static DroneKind byId(int id) {
        DroneKind[] v = values();
        return id >= 0 && id < v.length ? v[id] : FPV;
    }
}
