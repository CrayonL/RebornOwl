package com.owl.auxiliary;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/** 统一日志：同时写 logcat 与 Xposed 日志。 */
public final class Logx {

    private static final String TAG = "OwlAux";
    private static boolean sMirror = true;

    private Logx() {}

    public static void setMirror(boolean mirror) {
        sMirror = mirror;
    }

    public static void i(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
        if (sMirror) {
            try {
                XposedBridge.log(TAG + ": " + msg);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void w(String msg, Throwable t) {
        try {
            Log.w(TAG, msg, t);
        } catch (Throwable ignored) {
        }
        if (sMirror) {
            try {
                XposedBridge.log(TAG + ": " + msg);
                if (t != null) {
                    XposedBridge.log(t);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static void e(String msg) {
        try {
            Log.e(TAG, msg);
        } catch (Throwable ignored) {
        }
        if (sMirror) {
            try {
                XposedBridge.log(TAG + ": " + msg);
            } catch (Throwable ignored) {
            }
        }
    }
}
