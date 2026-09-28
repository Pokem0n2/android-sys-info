# android-sys-info

离线 Android 硬件信息查看器。单个 Activity + WebView + `@JavascriptInterface` 桥，
原生侧只负责采集数据（Build / StatFs / sysfs / EGL），展示层全部在一张内嵌 HTML 里完成。

- 无网络权限、无任何敏感权限，完全离线运行
- APK 体积 ~50KB（无第三方依赖，单 DEX）
- 在 aarch64 主机上用 cmdline-tools 手动工具链构建（javac → d8 → aapt2 → apksigner）

## 信息项

| 分组 | 内容 |
|------|------|
| 设备 | 品牌、厂商、型号、内部代号、设备昵称、主板平台 |
| 内存 | RAM 总量、当前可用、低内存标志 |
| 闪存 | 类型探测（UFS / eMMC）、厂商解码、颗粒编号 |
| 显示 | 分辨率、像素密度、刷新率、物理尺寸估算 |
| 存储 | 内部存储总容量 / 可用 / 已用占比 |
| 系统 | Android 版本、API 等级、安全补丁、内核版本、构建指纹 |
| 处理器 | 架构、ABI、核心数、各集群核心型号与最高频率 |
| 图形 | GPU 渲染器（EGL 离屏读取）、OpenGL ES 版本 |
| 电池 | 电量、状态、健康度、技术类型、设计容量、温度 |
| 其他 | 传感器数量、WebView 版本、开机时长 |

## 构建

```bash
# 依赖: JDK 21, Android SDK (build-tools 34.0.0 + platforms;android-34), aarch64 需 box64
bash apk/build.sh
# 产物: android-sys-info-v<git tag>.apk
```

版本号取自最近一次 git tag，versionName / versionCode 自动注入 manifest。

## 版本历史

见 [CHANGELOG.md](CHANGELOG.md)。
