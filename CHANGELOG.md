# 更新日志

## v0.8.0

- 闪存类型三级探测（修复 Android 8+「闪存类型不可用」）：
  ① `ro.boot.boot_devices`/`ro.boot.bootdevice` 系统属性（含 ufshc/mmc/nvme 判型）
  ② `/proc/partitions` 块设备名（mmcblk→eMMC、sda/sdb→UFS、nvme0n→NVMe，正则已在本机 NVMe 环境实测）
  ③ sysfs 直读细项（老 ROM 可用时兼拿厂商/型号）
- 新增「判定依据」行展示来源路径

## v0.7.3

- 图标缩小：黄色圆环四边留白 20px@xxxhdpi（矢量 63dp 落入自适应图标 66dp 安全区；位图五档重绘并像素级验证）
- APK 更名 `asi-z-vX.Y.Z.apk`

## v0.7.2

- 长值断行排版：构建指纹按 `/` 拆行（9 项断言测试全过）、GPU 图形 API 括号段拆行、WebView 版本号另起一行
- 单值点击复制适配多行值

## v0.7.1

- 按反馈删行：设备组去掉「品牌」「产品代码」，内存组去掉「内存吃紧」

## v0.7.0

- 「复制全部」按钮：整份报告（组名+键值）一键进剪贴板
- 单值点击复制 + 高亮反馈，全局 toast 提示
- 卡片标题可点击折叠/展开
- 图标补 API 21-25 位图回退（五档密度 PNG）

## v0.6.0

- 电池组：电量/状态/健康/技术/电压/温度/设计容量/满充容量（含健康度计算）
- 其他组：传感器数量与代表传感器名、WebView 版本、语言、时区

## v0.5.0

- CPU 组：架构/核心数/ABI/各集群（按 cpuinfo_max_freq 分桶）+ MIDR 解码核心型号
- GPU 组：EGL 离屏 pbuffer 直读 GL_RENDERER / GL_VERSION（无需 NDK）

## v0.4.0

- 系统组：Android 版本/API 等级/安全补丁/内核版本/构建指纹/构建标签/构建时间/开机时长

## v0.3.0

- 显示组：分辨率/像素密度/物理密度/屏幕尺寸估算（xdpi/ydpi）/刷新率
- 存储组：数据分区总容量/可用/已用（StatFs）

## v0.2.0

- 内存组：RAM 总量/当前可用/低内存阈值（ActivityManager.MemoryInfo）
- 闪存组：类型探测（UFS sysfs；eMMC mmcblk + CID 厂商解码，表源 mmc-utils lsmmc.c）

## v0.1.0

- 项目骨架：单 Activity + WebView + JS 桥（NativeInfo），内嵌 HTML 展示
- 设备识别：品牌 / 厂商 / 型号 / 内部代号 / 产品代码 / 主板平台 / 设备昵称
- 版本号自 git tag 派生，APK 命名自动带版本
- 修复：桥类改 static（d8 8.2.2 解析非静态内部类合成类 NPE）
