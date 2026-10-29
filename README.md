# DoubleTapTorch

在 ColorOS / 一加（OnePlus）设备上，把「双击电源键 = 召唤快捷卡包」改成「双击电源键 = 开关手电筒」的 LSPosed 模块。

不改 ROM、不替换系统文件，只 hook `PhoneWindowManager` 的电源键处理入口。

## 背景

AOSP 从 Android 12 起把「双击电源键」的判定放在 `GestureLauncherService` 里，常见第三方模块（如 Pixel Suite）hook 的也是那里。

但 ColorOS 16（Android 17）实测：**那条链完全不参与电源键处理**。日志：

```
[PWM] POWER action=0        ← 按键确实进到 system_server
（PowerKeyRule#onMultiPress 无输出）
（GestureLauncherService 全部无输出）
```

ColorOS 使用自己的实现，落在 `PhoneWindowManager.interceptPowerKeyDown / interceptPowerKeyUp`。本模块 hook 这两个方法。

## 功能

- **双击电源键** → 开关手电筒（卡包不再弹出）
- **开 / 关手电筒时** → 灵动岛显示当前状态
- **单击电源键** → 完全交给系统，零延迟（灭屏 / 亮屏均为原生速度）
- **长按电源键** → 完全交给系统，电源菜单正常
- **灭屏后立刻再按** → 屏幕亮起，灯不亮、卡包不弹（v1.1.0 起）
- 双击判定窗口 **500 ms**（可调，见下）

## 手势判定

仅用时间窗口不足。系统把「亮屏 → 单击灭屏 → 立刻再按」视为同一次双击（实测卡包 100% 弹出），但此序列的预期结果是**亮屏**，不是手电筒。

v1.1.0 在时间判定之上增加屏幕状态判定：

```
第二次按下时比较两个状态：
  A = 第一次按下时屏幕是否亮着   (interceptPowerKeyDown 的第二个参数)
  B = 第二次按下时屏幕是否亮着

A 亮 && B 灭  →  第一下已执行过灭屏，此序列属于"亮屏"
                 → 消费手势（卡包不弹）+ 调用 PowerManager.wakeUp()
  其它情况    →  按双击处理，开手电筒
```

结果对照：

| 操作 | 结果 |
|---|---|
| 亮屏 + 间隔 <500ms 双击 | 开手电筒 |
| 亮屏单击灭屏 → 立刻再按 | 屏幕亮起，灯不亮，卡包不弹 |
| 间隔 >500ms | 完全放行给系统 |

双击窗口不能小于系统窗口：系统的双击判定发生在输入层，只认时间戳，与屏幕状态无关。窗口偏小会让中间区间漏给系统，卡包仍会弹出。

## 已知限制

- 仅在 **ColorOS 16 / Android 17 / 一加 13（PJZ110）** 上实测通过，其它 ROM 未验证
- 息屏状态下双击开灯时，**第一次按下仍会短暂点亮屏幕**（该方案不拦截按下事件）。消除该现象需改为「延迟单击」，代价是每次单击延迟，未采用
- 手电筒亮度受 `CameraManager` 强度档位限制：实测设备 `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL = 4`，最高档对应 `sysfs` 值 50，节点上限为 500。达到 500 需 root 直接写 `/sys/class/leds/led:torch_0/brightness`，未内置

## 安装

1. 设备已 root 并安装 LSPosed（Zygisk 版本）
2. 安装 `DoubleTapTorch.apk`
3. LSPosed → 模块 → 启用 `DoubleTapTorch`
4. 作用域勾选 **系统框架**（`android`）和 **系统界面**（`com.android.systemui`），两个都要
5. 重启

## 调整双击窗口

`src/com/jcy/doubletorch/MainHook.java`：

```java
private static final long DOUBLE_GAP_MS = 500L;
```

实测按键间隔：最快 130 ms，常见 140~170 ms，较慢 240~370 ms，偶发 450 ms。500 覆盖全部实测区间。调大可覆盖更慢的双击，代价是「快速连按两次」更容易被判为手电筒。

## 构建

不使用 Gradle，采用 Android SDK 命令行工具手工构建。模块仅一个类，且 `aapt2` 在 arm64 上不可用，二进制清单由 `tools/mkaxml.py` 生成。

依赖：

- JDK 17
- Android SDK `android.jar`（任意 API ≥ 33）
- Android build-tools（`d8`、`apksigner`）
- Xposed API jar（`de.robv.android.xposed:api:82`）

```bash
# 1. 生成二进制 AndroidManifest.xml
python3 tools/mkaxml.py out/AndroidManifest.xml

# 2. 编译
javac -encoding UTF-8 -source 8 -target 8 \
      -bootclasspath "$ANDROID_JAR" \
      -cp xposed-api-82.jar \
      -d out/classes \
      src/com/jcy/doubletorch/MainHook.java

# 3. 转 dex
d8 --lib "$ANDROID_JAR" --min-api 26 --output out/dex out/classes/com/jcy/doubletorch/*.class

# 4. 打包（AndroidManifest.xml + classes.dex + assets/xposed_init）
# 5. 签名
apksigner sign --ks your.keystore --out DoubleTapTorch.apk out/unsigned.apk
```

`build.sh` 封装上述步骤，`ANDROID_JAR` / `XPOSED_API` 等路径通过脚本顶部变量配置。

## 工作原理

```java
XposedBridge.hookAllMethods(pwm, "interceptPowerKeyDown", ...)  // 记录按下间隔与屏幕状态
XposedBridge.hookAllMethods(pwm, "interceptPowerKeyUp", ...)    // 第二下抬起时消费手势
```

- 两次 `DOWN` 间隔 ≤ `DOUBLE_GAP_MS` → 判定双击
- 第二次 `UP` 时执行 `setResult(null)` 消费手势（卡包不弹），并调用 `CameraManager.setTorchMode()` 开关灯
- 单击（`seq == 1`）不干预，`param` 原样放行

## 许可证

MIT，见 [LICENSE](LICENSE)。

## 反馈

Issue 中请注明设备型号、ROM 版本，以及 LSPosed 日志中是否出现 `[PWM] DOWN`。若 `PowerKeyRule#onMultiPress` 被调用，说明该设备走 AOSP 路径，hook 点需要调整。
