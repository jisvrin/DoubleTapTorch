# DoubleTapTorch

双击电源键开关手电筒，替代 ColorOS 默认的快捷卡包。

## 功能

- 双击电源键：开 / 关手电筒
- 亮屏双击：屏幕不变，不影响当前操作
- 灭屏后半秒内再按：屏幕亮起，不误开手电筒
- 单击电源键：系统原生，零延迟
- 长按电源键：系统原生，电源菜单正常

## 使用前提

卡包 App → 我的 → 设置 → 双击电源键唤起快捷服务

必须开启。该选项关闭时系统不启用双击判定，模块不生效。

开启后卡包的双击唤起功能会被模块接管。

## 安装

1. 设备已 root 并安装 LSPosed（Zygisk 版本）
2. 安装 APK
3. LSPosed → 模块 → 启用 `DoubleTapTorch`
4. 作用域仅勾选「系统框架」
5. 重启

## 适配

一加 13（PJZ110）/ ColorOS 16 / Android 17

其它 ColorOS 机型未验证。

## 已知限制

- 息屏双击开灯时，第一次按下会短暂点亮屏幕
- 手电筒亮度受相机 API 档位限制，最高对应 sysfs 值 50（节点上限 500）

## 下载

[Releases](https://github.com/jisvrin/DoubleTapTorch/releases)

## 许可证

MIT
