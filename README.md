# DoubleTapTorch

在 ColorOS / 一加（OnePlus）设备上，把「双击电源键 = 召唤快捷卡包」改成「双击电源键 = 开关手电筒」的 LSPosed 模块。

不改 ROM、不替换系统文件，只 hook `PhoneWindowManager` 的电源键处理入口。

## 为什么要做这个

AOSP 从 Android 12 起把「双击电源键」的判定放在 `GestureLauncherService` 里，常见第三方模块（如 Pixel Suite）也是 hook 那里。

但在 ColorOS 16（Android 17）上实测：**那条链完全不参与电源键处理**。日志证明：

```
[PWM] POWER action=0        ← 按键确实进到 system_server
（PowerKeyRule#onMultiPress 无输出）
（GestureLauncherService 全部无输出）
```

ColorOS 用的是自己的一套实现，落在 `PhoneWindowManager.interceptPowerKeyDown / interceptPowerKeyUp` 里。所以本模块直接挂这两个方法。

## 功能

- **双击电源键** → 开关手电筒（卡包不再弹出）
- **单击电源键** → 完全交给系统，零延迟（灭屏/亮屏都是原生速度）
- **长按电源键** → 完全交给系统，电源菜单正常
- 双击判定窗口 **501 ms**（可改，见下）

## 已知限制

- 只在 **ColorOS 16 / Android 17 / 一加 13（PJZ110）** 上实测通过，其它 ROM 未验证
- 息屏状态下双击开灯时，**第一次按下仍会短暂点亮屏幕**（因为本方案不拦截按下事件）。要消掉这个必须改成「延迟单击」，代价是每次单击都延迟，本项目没有采用
- 手电筒亮度受 `CameraManager` 强度档位限制：本机 `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL = 4`，最高档对应 `sysfs` 值 50，达不到节点上限 500。要 500 需要 root 直接写 `/sys/class/leds/led:torch_0/brightness`，本项目没有内置

## 安装

1. 设备已 root 并装好 LSPosed（Zygisk 版本）
2. 安装 `DoubleTapTorch.apk`
3. LSPosed → 模块 → 启用 `DoubleTapTorch`
4. 作用域只勾 **系统框架**（`android`），其它都不要勾
5. 重启手机

## 调整双击窗口

`src/com/jcy/doubletorch/MainHook.java`：

```java
private static final long DOUBLE_GAP_MS = 501L;
```

实测该设备的按键间隔分布在 148~568 ms 之间，501 能覆盖绝大部分。调大可以吃下更慢的双击，但会让「快速连按两次」更容易被误判成手电筒。

## 构建

本项目不用 Gradle，用 Android SDK 的命令行工具手工构建。原因是模块本身极小（一个类），且 `aapt2` 在 arm64 上不可用，二进制清单由 `tools/mkaxml.py` 生成。

需要：

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

`build.sh` 封装了上述步骤，改一下里面的 `ANDROID_JAR` / `XPOSED_API` 路径即可。

## 工作原理

```java
XposedBridge.hookAllMethods(pwm, "interceptPowerKeyDown", ...)  // 记录按下间隔
XposedBridge.hookAllMethods(pwm, "interceptPowerKeyUp", ...)    // 第二下抬起时消费手势
```

- 两次 `DOWN` 间隔 ≤ `DOUBLE_GAP_MS` → 判定双击
- 第二次 `UP` 时执行 `setResult(null)` 吃掉手势（卡包不会弹），并调用 `CameraManager.setTorchMode()` 开关灯
- 单击（`seq == 1`）不干预，`param` 原样放行

## 许可证

MIT，见 [LICENSE](LICENSE)。

## 贡献

欢迎提 Issue 报告其它机型上的表现。如果你的设备上 `PowerKeyRule#onMultiPress` 是被调用的，那说明走的是 AOSP 路径，本模块的 hook 点需要调整。
