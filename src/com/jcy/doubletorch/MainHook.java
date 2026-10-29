package com.jcy.doubletorch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 双击电源键 -> 手电筒（替代 ColorOS 的双击卡包）。
 *
 * v6 起：手电筒不再由 system_server 直接调 CameraManager，改为发广播给 SystemUI，
 * 由 SystemUI 的 FlashlightController#setFlashlightFromUser() 开启，
 * 这样才会触发系统的「手电筒已开启」提示（灵动岛 / 状态通知）。
 *
 * 两个进程各司其职：
 *   system_server：判定双击 / 亮屏，发广播
 *   SystemUI    ：捕获 FlashlightControllerImpl 实例，收到广播后调用它
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[DoubleTapTorch] ";
    private static final String TORCH_ACTION = "com.jcy.doubletorch.action.SET_TORCH";
    private static final long DOUBLE_GAP_MS = 500L;
    /** 开机后这段时间内不走广播（SystemUI 尚未就绪），直接控制灯。 */
    private static final long BOOT_GRACE_MS = 10000L;

    // ---------- system_server 侧 ----------
    private static volatile long sLastDown;
    private static volatile int sSeq;
    private static volatile boolean sFirstInteractive = true;
    private static volatile boolean sPendingWake;

    private static CameraManager sCamera;
    private static Handler sBg;
    private static String sFlashId;
    private static volatile boolean sInit;
    private static final Map<String, Boolean> sState = new ConcurrentHashMap<String, Boolean>();

    /** 最近一次下发的开灯命令，用于抵消异步延迟导致的连按误判。 */
    private static volatile Boolean sLastCmd;
    private static volatile long sLastCmdAt;

    // ---------- SystemUI 侧 ----------
    private static volatile Object sTorchCtrl;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.android.systemui".equals(lpparam.packageName)) {
            hookSystemUI(lpparam);
            return;
        }
        if (!"android".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + "attached: process=" + lpparam.processName);
        hookPowerKey(lpparam);
    }

    // ==================== SystemUI 侧 ====================
    private void hookSystemUI(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> flc = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.policy.FlashlightControllerImpl",
                    lpparam.classLoader);
            XposedBridge.hookAllConstructors(flc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sTorchCtrl = param.thisObject;
                    XposedBridge.log(TAG + "[SUI] controller captured");
                }
            });
            XposedBridge.log(TAG + "[SUI] constructor hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[SUI] controller hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                registerReceiver((Context) param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "[SUI] register failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[SUI] application hook failed: " + t);
        }
    }

    private static void registerReceiver(Context ctx) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean enable = intent.getBooleanExtra("enable", false);
                Object ctrl = sTorchCtrl;
                XposedBridge.log(TAG + "[SUI] cmd enable=" + enable + " ctrl=" + ctrl);
                if (ctrl == null) return;
                long token = Binder.clearCallingIdentity();
                try {
                    XposedHelpers.callMethod(ctrl, "setFlashlightFromUser", enable);
                    XposedBridge.log(TAG + "[SUI] setFlashlightFromUser(" + enable + ") ok");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "[SUI] call failed: " + t);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        };
        IntentFilter filter = new IntentFilter(TORCH_ACTION);
        try {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            ctx.registerReceiver(receiver, filter);
        }
        XposedBridge.log(TAG + "[SUI] receiver registered");
    }

    // ==================== system_server 侧 ====================
    private void hookPowerKey(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> pwm = XposedHelpers.findClass(
                    "com.android.server.policy.PhoneWindowManager", lpparam.classLoader);

            XposedBridge.hookAllMethods(pwm, "interceptPowerKeyDown", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    long now = SystemClock.uptimeMillis();
                    long gap = now - sLastDown;
                    boolean interactive = boolArg(param, 1, true);
                    int seq = (sLastDown > 0 && gap <= DOUBLE_GAP_MS) ? 2 : 1;
                    if (seq == 1) {
                        sFirstInteractive = interactive;
                    } else if (sFirstInteractive && !interactive) {
                        XposedBridge.log(TAG + "[PWM] screen went off -> plan wake-up");
                        seq = 1;
                        sPendingWake = true;
                    }
                    sSeq = seq;
                    sLastDown = now;
                    XposedBridge.log(TAG + "[PWM] DOWN seq=" + seq + " gap=" + gap
                            + " interactive=" + interactive);
                }
            });

            XposedBridge.hookAllMethods(pwm, "interceptPowerKeyUp", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int seq = sSeq;
                    boolean pendingWake = sPendingWake;
                    sPendingWake = false;
                    XposedBridge.log(TAG + "[PWM] UP seq=" + seq + " pendingWake=" + pendingWake);
                    Context ctx = context(param.thisObject);
                    if (ctx == null) return;

                    if (pendingWake) {
                        wakeUp(ctx);
                        param.setResult(null);
                        sSeq = 0;
                        sLastDown = 0;
                        XposedBridge.log(TAG + "wake-up consumed");
                        return;
                    }
                    if (seq != 2) return;

                    toggleTorch(ctx);
                    param.setResult(null);
                    sSeq = 0;
                    sLastDown = 0;
                    XposedBridge.log(TAG + "double press consumed");
                }
            });
            XposedBridge.log(TAG + "power key hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook failed: " + t);
        }
    }

    private static boolean boolArg(XC_MethodHook.MethodHookParam param, int index, boolean def) {
        try {
            Object a = param.args.length > index ? param.args[index] : null;
            if (a instanceof Boolean) return (Boolean) a;
        } catch (Throwable ignored) { }
        return def;
    }

    private static Context context(Object pwm) {
        try {
            Object c = XposedHelpers.getObjectField(pwm, "mContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) { }
        return null;
    }

    // ---------------- 手电筒 ----------------
    private static synchronized void ensureInit(Context ctx) {
        if (sInit) return;
        try {
            HandlerThread t = new HandlerThread("DoubleTapTorch");
            t.start();
            sBg = new Handler(t.getLooper());
            sCamera = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            sCamera.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    sState.put(cameraId, enabled);
                }
                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    sState.put(cameraId, Boolean.FALSE);
                }
            }, sBg);
            sInit = true;
            XposedBridge.log(TAG + "torch tracker ready");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "init failed: " + t);
        }
    }

    private static void toggleTorch(final Context ctx) {
        ensureInit(ctx);
        final Handler bg = sBg;
        if (bg == null) return;
        bg.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String id = flashId();
                    if (id == null) {
                        XposedBridge.log(TAG + "no flash unit");
                        return;
                    }
                    long now = SystemClock.uptimeMillis();
                    boolean target;
                    Boolean cur = sState.get(id);
                    Boolean last = sLastCmd;
                    if (last != null && now - sLastCmdAt < 1500L) {
                        target = !last;
                    } else {
                        target = !(cur != null && cur);
                    }
                    sLastCmd = target;
                    sLastCmdAt = now;

                    // 开机初期 SystemUI 还没起来，广播必然落空：
                    // 这段时间直接控制灯，双击照样能用，只是没有灵动岛提示。
                    if (SystemClock.elapsedRealtime() < BOOT_GRACE_MS) {
                        sCamera.setTorchMode(id, target);
                        XposedBridge.log(TAG + "boot grace -> torch=" + target);
                        return;
                    }

                    Intent intent = new Intent(TORCH_ACTION);
                    intent.putExtra("enable", target);
                    intent.setPackage("com.android.systemui");
                    long token = Binder.clearCallingIdentity();
                    try {
                        ctx.sendBroadcast(intent);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    XposedBridge.log(TAG + "broadcast sent enable=" + target);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "toggle failed: " + t);
                }
            }
        });
    }

    private static String flashId() {
        if (sFlashId != null) return sFlashId;
        long token = Binder.clearCallingIdentity();
        try {
            String fallback = null;
            for (String id : sCamera.getCameraIdList()) {
                CameraCharacteristics c = sCamera.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash == null || !flash) continue;
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    sFlashId = id;
                    return id;
                }
                if (fallback == null) fallback = id;
            }
            sFlashId = fallback;
            return fallback;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "flashId failed: " + t);
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // ---------------- 亮屏 ----------------
    private static Method sWakeUpMethod;
    private static boolean sWakeUpProbed;

    private static void wakeUp(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            Method m = sWakeUpMethod;
            if (m == null && !sWakeUpProbed) {
                sWakeUpProbed = true;
                m = findWakeUp();
                sWakeUpMethod = m;
                XposedBridge.log(TAG + "wakeUp probe -> " + m);
            }
            if (m == null) {
                XposedBridge.log(TAG + "no wakeUp method");
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                m.invoke(pm, buildArgs(m, SystemClock.uptimeMillis()));
                XposedBridge.log(TAG + "wakeUp sent");
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "wakeUp failed: " + t);
        }
    }

    private static Method findWakeUp() {
        Method best = null;
        try {
            for (Method m : PowerManager.class.getDeclaredMethods()) {
                if (!"wakeUp".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0 || p[0] != long.class) continue;
                if (best == null || p.length < best.getParameterTypes().length) best = m;
            }
        } catch (Throwable ignored) { }
        if (best != null) {
            try {
                best.setAccessible(true);
            } catch (Throwable ignored) { }
        }
        return best;
    }

    private static Object[] buildArgs(Method m, long now) {
        Class<?>[] p = m.getParameterTypes();
        Object[] a = new Object[p.length];
        for (int i = 0; i < p.length; i++) {
            if (p[i] == long.class) a[i] = (Long) now;
            else if (p[i] == int.class) a[i] = (Integer) 0;
            else if (p[i] == boolean.class) a[i] = Boolean.FALSE;
            else a[i] = null;
        }
        return a;
    }
}
