package com.jcy.doubletorch;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

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
 * 排查记录：
 *  v1 hook PhoneWindowManager$PowerKeyRule#onMultiPress  -> 从未被调用
 *  v2 追加 GestureLauncherService 全套 + interceptKeyBeforeQueueing
 *     -> 只有 [PWM] POWER 出现，PowerKeyRule / GLS 全部沉默
 *     即 ColorOS 在 PhoneWindowManager 内部用自己的 IInputExt 处理电源键，
 *     卡包在 "电源键抬起" 时进入（system_server 里有方法名 handlePowerKeyUpForWallet）。
 *
 * v3 因此直接挂 PhoneWindowManager 真正的电源键处理入口：
 *   interceptPowerKeyDown(KeyEvent, boolean, boolean)   void  只观测，记录按下序列
 *   interceptPowerKeyUp(KeyEvent, boolean)              void  双击的第二下抬起时，
 *                                                            吃掉手势（卡包不弹）并开关手电筒
 * 单击不干预，走系统原逻辑。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[DoubleTapTorch] ";
    private static final long DOUBLE_GAP_MS = 501L;

    private static volatile long sLastDown;
    private static volatile int sSeq;

    private static CameraManager sCamera;
    private static Handler sBg;
    private static String sFlashId;
    private static volatile boolean sInit;
    private static final Map<String, Boolean> sState = new ConcurrentHashMap<String, Boolean>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + "attached: process=" + lpparam.processName);

        try {
            Class<?> pwm = XposedHelpers.findClass(
                    "com.android.server.policy.PhoneWindowManager", lpparam.classLoader);

            XposedBridge.hookAllMethods(pwm, "interceptPowerKeyDown", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    long now = SystemClock.uptimeMillis();
                    long gap = now - sLastDown;
                    int seq = (sLastDown > 0 && gap <= DOUBLE_GAP_MS) ? 2 : 1;
                    sSeq = seq;
                    sLastDown = now;
                    XposedBridge.log(TAG + "[PWM] DOWN seq=" + seq + " gap=" + gap);
                }
            });
            XposedBridge.log(TAG + "hook interceptPowerKeyDown OK");

            XposedBridge.hookAllMethods(pwm, "interceptPowerKeyUp", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int seq = sSeq;
                    XposedBridge.log(TAG + "[PWM] UP seq=" + seq);
                    if (seq != 2) return;
                    Context ctx = context(param.thisObject);
                    if (ctx == null) {
                        XposedBridge.log(TAG + "no context");
                        return;
                    }
                    toggle(ctx);
                    param.setResult(null);
                    sSeq = 0;
                    sLastDown = 0;
                    XposedBridge.log(TAG + "double press consumed -> torch");
                }
            });
            XposedBridge.log(TAG + "hook interceptPowerKeyUp OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook failed: " + t);
        }
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
            XposedBridge.log(TAG + "torch controller ready");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "init failed: " + t);
        }
    }

    private static void toggle(final Context ctx) {
        ensureInit(ctx);
        final Handler bg = sBg;
        if (bg == null || sCamera == null) return;
        bg.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String id = flashId();
                    if (id == null) {
                        XposedBridge.log(TAG + "no camera with flash");
                        return;
                    }
                    Boolean cur = sState.get(id);
                    boolean target = !(cur != null && cur);
                    long token = Binder.clearCallingIdentity();
                    try {
                        setTorch(id, target);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "toggle failed: " + t);
                }
            }
        });
    }


    // ---------------- 亮度 ----------------
    private static int sStrengthMax = -2;

    private static void setTorch(String id, boolean on) {
        try {
            if (!on) {
                sCamera.setTorchMode(id, false);
                XposedBridge.log(TAG + "torch off");
                return;
            }
            int level = strengthLevel(id);
            if (level > 0) {
                try {
                    java.lang.reflect.Method m = CameraManager.class.getMethod(
                            "turnOnTorchWithStrengthLevel", String.class, int.class);
                    m.invoke(sCamera, id, level);
                    XposedBridge.log(TAG + "torch on strength=" + level);
                    return;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "strength api failed: " + t);
                }
            }
            sCamera.setTorchMode(id, true);
            XposedBridge.log(TAG + "torch on default(32)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "setTorch failed: " + t);
        }
    }

    private static int strengthLevel(String id) {
        if (sStrengthMax == -2) {
            sStrengthMax = -1;
            try {
                CameraCharacteristics c = sCamera.getCameraCharacteristics(id);
                Integer v = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                if (v != null) sStrengthMax = v;
                XposedBridge.log(TAG + "strength max=" + sStrengthMax);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "strength probe failed: " + t);
            }
        }
        if (sStrengthMax <= 0) return -1;
        return Math.min(500, sStrengthMax);
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
}
