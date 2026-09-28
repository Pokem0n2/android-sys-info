package com.sysinfo.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 系统信息 —— 离线硬件信息查看器。
 *
 * 架构：单个 Activity 持有一个 WebView，原生侧通过 @JavascriptInterface
 * 桥（JS 侧全局对象 NativeInfo）暴露只读的数据采集方法；
 * 界面渲染全部在内嵌的 assets/index.html 中完成。
 *
 * 桥类必须是 static 内部类并持有显式 Activity 引用：
 * 本机构建的 d8 8.2.2 (build-tools 34.0.0) 解析非静态内部类的
 * 合成访问类（MainActivity$1）会 NPE（tookies 项目已踩过，勿回退）。
 */
public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setTextZoom(100); // 锁定 100%，避免系统字体缩放破坏排版
        webView.addJavascriptInterface(new DeviceBridge(this), "NativeInfo");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    /** 暴露给 JS 的桥。每个方法返回一个 JSON 字符串，键缺失时 JS 侧显示「不可用」。 */
    public static class DeviceBridge {

        private final Activity activity;

        DeviceBridge(Activity activity) {
            this.activity = activity;
        }

        /** 应用自身版本（来自 PackageManager，与 APK 的 versionName 一致）。 */
        @JavascriptInterface
        public String getAppVersion() {
            try {
                return activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "0.0.0";
            }
        }

        /** 设备识别：品牌 / 厂商 / 型号 / 内部代号 / 主板平台 / 设备昵称。 */
        @JavascriptInterface
        public String getDeviceInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("brand", Build.BRAND);                // 品牌，如 samsung / Xiaomi
                o.put("manufacturer", Build.MANUFACTURER);  // 厂商全称
                o.put("model", Build.MODEL);                // 市场型号名（营销名）
                o.put("device", Build.DEVICE);              // 内部设备代号
                o.put("product", Build.PRODUCT);            // 产品代码
                o.put("board", Build.BOARD);                // 主板/平台代号，如 kalama
                // 设备昵称：Settings.Global 的 device_name 键（部分 ROM 不可读，返回空串）
                String name = Settings.Global.getString(
                        activity.getContentResolver(), "device_name");
                o.put("deviceName", name == null ? "" : name);
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        /** 内存：总量 / 当前可用 / 阈值 / 低内存标志（ActivityManager.MemoryInfo）。 */
        @JavascriptInterface
        public String getMemoryInfo() {
            JSONObject o = new JSONObject();
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                ActivityManager am = (ActivityManager) activity
                        .getSystemService(Context.ACTIVITY_SERVICE);
                am.getMemoryInfo(mi);
                o.put("total", mi.totalMem);       // 物理内存总量（字节）
                o.put("avail", mi.availMem);       // 当前可用（字节）
                o.put("threshold", mi.threshold);  // 系统判定「内存吃紧」的阈值
                o.put("low", mi.lowMemory);        // 当前是否已吃紧
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        /** 显示：分辨率 / 密度 / 刷新率 / 物理尺寸估算（WindowManager + DisplayMetrics）。 */
        @JavascriptInterface
        public String getDisplayInfo() {
            JSONObject o = new JSONObject();
            try {
                // getRealMetrics 反映整块面板的物理像素（不受状态栏/挖孔裁剪影响）
                DisplayMetrics rm = new DisplayMetrics();
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(rm);
                o.put("width", rm.widthPixels);
                o.put("height", rm.heightPixels);
                o.put("densityDpi", rm.densityDpi);
                o.put("density", rm.density);
                o.put("xdpi", rm.xdpi);
                o.put("ydpi", rm.ydpi);
                float refresh = activity.getWindowManager()
                        .getDefaultDisplay().getRefreshRate();
                o.put("refresh", refresh);
                // 对角线物理尺寸：像素数 ÷ 面板报告的 xdpi/ydpi（部分面板报告值不准，仅供参考）
                double inches = Math.sqrt(
                        Math.pow(rm.widthPixels / (double) rm.xdpi, 2)
                                + Math.pow(rm.heightPixels / (double) rm.ydpi, 2));
                o.put("inches", Math.round(inches * 100) / 100.0);
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        /** 存储容量：数据分区的总容量 / 可用 / 已用（StatFs）。 */
        @JavascriptInterface
        public String getStorageStat() {
            JSONObject o = new JSONObject();
            try {
                // 应用私有目录挂在数据分区上，对它取 StatFs 即得用户实际可用的主存储
                File dataDir = activity.getFilesDir().getParentFile();
                StatFs stat = new StatFs(dataDir.getAbsolutePath());
                long total = stat.getTotalBytes();
                long avail = stat.getAvailableBytes();
                o.put("total", total);
                o.put("avail", avail);
                o.put("used", total - avail);
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        /** 系统：Android 版本 / API 等级 / 安全补丁 / 内核版本 / 构建指纹 / 启动时长。 */
        @JavascriptInterface
        public String getSystemInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("release", Build.VERSION.RELEASE);          // 13 / 14 / 15
                o.put("sdk", Build.VERSION.SDK_INT);              // API 等级（精确整数）
                o.put("codename", Build.VERSION.CODENAME);        // REL = 正式版
                o.put("incremental", Build.VERSION.INCREMENTAL);
                if (Build.VERSION.SDK_INT >= 23) {
                    o.put("securityPatch", Build.VERSION.SECURITY_PATCH);
                }
                // 内核版本：System.getProperty("os.version") 即 uname -r
                o.put("kernel", System.getProperty("os.version"));
                o.put("fingerprint", Build.FINGERPRINT);          // 品牌/设备/构建唯一指纹
                o.put("host", Build.HOST);                        // 构建机主机名
                o.put("tags", Build.TAGS);                        // release-keys / test-keys
                o.put("bootUptime", SystemClock.elapsedRealtime()); // 本次开机以来的毫秒数
                o.put("buildTime", Build.TIME);                   // epoch 毫秒
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        /** CPU：架构 / ABI 列表 / 核心数 / 各集群（按最高频率分桶 + MIDR 解码）。 */
        @JavascriptInterface
        public String getCpuInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("abis", joinStrings(Build.SUPPORTED_ABIS));
                // 首选 ABI 即当前运行架构（arm64-v8a / armeabi-v7a / x86_64）
                String arch = Build.SUPPORTED_ABIS.length > 0
                        ? Build.SUPPORTED_ABIS[0] : "";
                o.put("arch", arch);
                o.put("cores", Runtime.getRuntime().availableProcessors());
                o.put("clusters", readCpuClusters());
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        /** 读 /sys/devices/system/cpu/，按「cpuinfo_max_freq」分桶聚合集群，
         *  并读取每个集群首个核心的 MIDR（regs/id/midr_el1）解码出核心型号。 */
        private static JSONArray readCpuClusters() {
            JSONArray arr = new JSONArray();
            java.util.LinkedHashMap<Long, java.util.ArrayList<Integer>> buckets =
                    new java.util.LinkedHashMap<>();
            for (int i = 0; i < 64; i++) {
                File cpuDir = new File("/sys/devices/system/cpu/cpu" + i);
                if (!cpuDir.isDirectory()) break;
                String max = readOneLine(new File(cpuDir, "cpufreq/cpuinfo_max_freq"));
                long m = -1;
                if (max != null) {
                    try { m = Long.parseLong(max); } catch (NumberFormatException ignored) {}
                }
                java.util.ArrayList<Integer> list = buckets.get(m);
                if (list == null) {
                    list = new java.util.ArrayList<>();
                    buckets.put(m, list);
                }
                list.add(i);
            }
            for (java.util.Map.Entry<Long, java.util.ArrayList<Integer>> e : buckets.entrySet()) {
                JSONObject c = new JSONObject();
                try {
                    c.put("cpus", joinInts(e.getValue()));
                    c.put("maxFreq", e.getKey());   // kHz
                    int first = e.getValue().get(0);
                    // ARM64 内核暴露每核 MIDR（含 implementer/part）；32 位无此文件
                    String midr = readOneLine(new File(
                            "/sys/devices/system/cpu/cpu" + first + "/regs/id/midr_el1"));
                    if (midr != null) {
                        c.put("midr", midr);
                        String core = decodeMidr(midr);
                        if (core != null) c.put("core", core);
                    }
                } catch (Exception ignored) {
                }
                arr.put(c);
            }
            return arr;
        }

        /** MIDR_EL1 → 人类可读核心型号。字段布局见 ARM ARM (DDI 0487) MIDR 描述。 */
        private static String decodeMidr(String midrHex) {
            try {
                long v = Long.parseUnsignedLong(midrHex.trim().replaceFirst("^0[xX]", ""), 16);
                int implementer = (int) ((v >> 24) & 0xff);
                int part = (int) ((v >> 4) & 0xfff);
                String vendor;
                switch (implementer) {
                    case 0x41: vendor = "ARM"; break;
                    case 0x51: vendor = "Qualcomm"; break;
                    case 0x53: vendor = "Samsung"; break;
                    case 0x48: vendor = "HiSilicon"; break;
                    case 0x4e: vendor = "NVIDIA"; break;
                    case 0x69: vendor = "Intel"; break;
                    default:   vendor = String.format("impl 0x%02x", implementer); break;
                }
                String core = armCoreName(part);
                return core != null ? vendor + " " + core
                        : String.format("%s part 0x%03x", vendor, part);
            } catch (Exception e) {
                return null;
            }
        }

        /** ARM 公版核心 part number（见各核心 TRM）；非公版（骁龙 X 系自研等）返回 null。 */
        private static String armCoreName(int part) {
            switch (part) {
                case 0xd01: return "Cortex-A32";
                case 0xd03: return "Cortex-A53";
                case 0xd04: return "Cortex-A35";
                case 0xd05: return "Cortex-A55";
                case 0xd07: return "Cortex-A57";
                case 0xd08: return "Cortex-A72";
                case 0xd09: return "Cortex-A73";
                case 0xd0a: return "Cortex-A75";
                case 0xd0b: return "Cortex-A76";
                case 0xd0c: return "Neoverse-N1";
                case 0xd0d: return "Cortex-A77";
                case 0xd0e: return "Cortex-A76AE";
                case 0xd40: return "Neoverse-V1";
                case 0xd41: return "Cortex-A78";
                case 0xd44: return "Cortex-X1";
                case 0xd46: return "Cortex-A510";
                case 0xd47: return "Cortex-A710";
                case 0xd48: return "Cortex-X2";
                case 0xd49: return "Neoverse-N2";
                case 0xd4d: return "Cortex-A715";
                case 0xd4e: return "Cortex-X3";
                case 0xd80: return "Cortex-A520";
                case 0xd81: return "Cortex-A720";
                case 0xd82: return "Cortex-X4";
                case 0xd87: return "Cortex-A725";
                case 0xd88: return "Cortex-X925";
                default: return null;
            }
        }

        /** GPU 信息：离屏 EGL surface 上调 glGetString 读渲染器与版本（见 GpuProbe）。 */
        @JavascriptInterface
        public String getGpuInfo() {
            JSONObject o = new JSONObject();
            try {
                String[] gl = GpuProbe.query();
                if (gl != null) {
                    o.put("renderer", gl[0]);
                    o.put("version", gl[1]);
                }
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        private static String joinStrings(String[] arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(" / ");
                sb.append(arr[i]);
            }
            return sb.toString();
        }

        private static String joinInts(java.util.ArrayList<Integer> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(list.get(i));
            }
            return sb.toString();
        }

        /** 电池：电量 / 状态 / 健康 / 技术 / 电压 / 温度 / 设计容量（BATTERY_CHANGED 粘性广播）。 */
        @JavascriptInterface
        public String getBatteryInfo() {
            JSONObject o = new JSONObject();
            try {
                IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent i = activity.registerReceiver(null, f); // null receiver = 只读 sticky，不注册
                if (i != null) {
                    int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (level >= 0 && scale > 0) {
                        o.put("level", level * 100 / scale);
                    }
                    o.put("status", batteryStatusText(
                            i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)));
                    o.put("health", batteryHealthText(
                            i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)));
                    String tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                    if (tech != null) o.put("technology", tech);
                    int volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                    if (volt > 0) o.put("voltage", volt);            // mV
                    int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                    if (temp > 0) o.put("temperature", temp);        // 0.1°C
                    // 剩余容量（µAh，API 21+）；由此与设计容量可算健康度
                    BatteryManager bm = (BatteryManager) activity
                            .getSystemService(Context.BATTERY_SERVICE);
                    long charge = bm.getLongProperty(
                            BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                    if (charge > 0) o.put("chargeCounter", charge);
                }
                // 设计/满充容量：优先 API 34 广播 extra（Intent.EXTRA_ 循环计数），读不到走 sysfs
                long design = i != null ? i.getLongExtra("design_capacity", -1) : -1;
                if (design <= 0) design = readSysfsLong("/sys/class/power_supply/battery/charge_full_design");
                if (design > 0) o.put("designCapacity", design);     // µAh
                long full = readBatteryExtra(i, "charge_full");
                if (full <= 0) full = readSysfsLong("/sys/class/power_supply/battery/charge_full");
                if (full > 0) o.put("fullCapacity", full);           // µAh
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        private static long readBatteryExtra(Intent i, String extra) {
            if (i == null) return -1;
            try {
                return i.getLongExtra(extra, -1);
            } catch (Exception ignored) {
            }
            return -1;
        }

        private static long readSysfsLong(String path) {
            String s = readOneLine(new File(path));
            if (s == null) return -1;
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static String batteryStatusText(int status) {
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING:     return "充电中";
                case BatteryManager.BATTERY_STATUS_DISCHARGING:  return "放电中";
                case BatteryManager.BATTERY_STATUS_FULL:         return "已充满";
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "未充电";
                default: return null;
            }
        }

        private static String batteryHealthText(int health) {
            switch (health) {
                case BatteryManager.BATTERY_HEALTH_GOOD:         return "良好";
                case BatteryManager.BATTERY_HEALTH_OVERHEAT:     return "过热";
                case BatteryManager.BATTERY_HEALTH_DEAD:         return "已报废";
                case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "过压";
                case BatteryManager.BATTERY_HEALTH_COLD:         return "过冷";
                default: return null;
            }
        }

        /** 其他：传感器数量 / WebView 版本 / 语言时区。 */
        @JavascriptInterface
        public String getMiscInfo() {
            JSONObject o = new JSONObject();
            try {
                SensorManager sm = (SensorManager) activity
                        .getSystemService(Context.SENSOR_SERVICE);
                java.util.List<Sensor> sensors = sm.getSensorList(Sensor.TYPE_ALL);
                o.put("sensorCount", sensors.size());
                // 首选各类型的代表传感器（有则显示名）
                addSensor(o, "accel", Sensor.TYPE_ACCELEROMETER, sensors, sm);
                addSensor(o, "gyro", Sensor.TYPE_GYROSCOPE, sensors, sm);
                addSensor(o, "mag", Sensor.TYPE_MAGNETIC_FIELD, sensors, sm);
                addSensor(o, "light", Sensor.TYPE_LIGHT, sensors, sm);

                // WebView 版本：当前提供者（系统 WebView / Chrome / 三星 Internet…）
                if (Build.VERSION.SDK_INT >= 26) {
                    PackageInfo pkg = android.webkit.WebView.getCurrentWebViewPackage();
                    if (pkg != null) {
                        o.put("webview", pkg.packageName + " " + pkg.versionName);
                    }
                } else {
                    String v = findWebViewVersionPre26();
                    if (v != null) o.put("webview", v);
                }
                o.put("locale", java.util.Locale.getDefault().toString());
                o.put("timezone", java.util.TimeZone.getDefault().getID());
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        private void addSensor(JSONObject o, String key, int type,
                               java.util.List<Sensor> all, SensorManager sm) {
            try {
                for (Sensor s : all) {
                    if (s.getType() == type) {
                        o.put(key, s.getName());
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        /** API 21-25：从已知 WebView 提供包里翻版本号。 */
        private String findWebViewVersionPre26() {
            String[] pkgs = {
                    "com.google.android.webview",
                    "com.android.webview",
                    "com.android.chrome",
                    "com.sec.android.app.sbrowser",
            };
            for (String p : pkgs) {
                try {
                    return p + " " + activity.getPackageManager()
                            .getPackageInfo(p, 0).versionName;
                } catch (Exception ignored) {
                }
            }
            return null;
        }

        /** 闪存类型探测：优先 UFS（sysfs ufshc 目录），退回 eMMC（mmcblk，含厂商解码）。 */
        @JavascriptInterface
        public String getStorageInfo() {
            JSONObject o = new JSONObject();
            try {
                detectUfs(o);
                if (o.length() == 0) {
                    detectEmmc(o);
                }
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        /** UFS：内核把主控信息放在 /sys/class/ufshc/ 下（每控制器一目录），
         *  路径随版本有差异，逐个候选目录探测；目录名以 ufshc 开头即认定。
         *  读不到细项也至少给出类型。 */
        private void detectUfs(JSONObject o) throws Exception {
            File[] candidates = {
                    new File("/sys/class/ufshc"),
                    new File("/sys/class/misc"),
                    new File("/sys/devices/platform"),
            };
            for (File root : candidates) {
                File[] children = root.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    if (!child.isDirectory()) continue;
                    if (!child.getName().startsWith("ufshc")) continue;
                    o.put("type", "UFS");
                    // 标准厂商/产品名（若内核暴露）
                    String std = readOneLine(new File(child, "device_descriptor"));
                    // 厂商自定义字符串（若内核暴露）
                    String sub = readOneLine(new File(child, "strings_subtype"));
                    if (std != null && !std.isEmpty()) o.put("desc", std);
                    if (sub != null && !sub.isEmpty()) o.put("sub", sub);
                    return;
                }
            }
        }

        /** eMMC：/sys/block/mmcblk0 的 device 子目录下有 cid / name。 */
        private void detectEmmc(JSONObject o) throws Exception {
            File[] roots = {new File("/sys/block"), new File("/sys/class/block")};
            for (File root : roots) {
                File[] children = root.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    if (!child.getName().startsWith("mmcblk")) continue;
                    File dev = new File(child, "device");
                    String cid = readOneLine(new File(dev, "cid"));
                    if (cid == null) continue;
                    o.put("type", "eMMC");
                    // cid 首字节为厂商 ID；表来自 mmc-utils lsmmc.c 的 mmc_database
                    String man = emmcManufacturer(cid);
                    if (man != null) o.put("manufacturer", man);
                    String pname = readOneLine(new File(dev, "name"));
                    if (pname != null && !pname.isEmpty()) o.put("product", pname);
                    String manid = readOneLine(new File(dev, "manid"));
                    if (manid != null && !manid.isEmpty()) o.put("manid", "0x" + manid);
                    return;
                }
            }
        }

        /** eMMC CID 首字节 → 厂商名。表来源：mmc-utils lsmmc.c（JEDEC JEP106 派生）。 */
        private static String emmcManufacturer(String cidHex) {
            if (cidHex == null) return null;
            String h = cidHex.trim();
            if (h.length() < 2) return null;
            final int id;
            try {
                id = Integer.parseInt(h.substring(0, 2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
            switch (id) {
                case 0x00: return "SanDisk";
                case 0x02: return "Kingston/SanDisk";
                case 0x03: return "Toshiba";
                case 0x11: return "Toshiba";
                case 0x13: return "Micron";
                case 0x15: return "Samsung/SanDisk/LG";
                case 0x2c: return "Kingston";
                case 0x37: return "KingMax";
                case 0x44: return "ATP";
                case 0x45: return "SanDisk Corporation";
                case 0x70: return "Kingston";
                case 0xfe: return "Micron";
                default:   return null;  // 未知 ID：不猜，JS 侧显示原始 manid
            }
        }

        /** 读 sysfs 单行文件，失败返回 null（不抛异常）。 */
        private static String readOneLine(File f) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(f));
                String line = br.readLine();
                if (line != null) line = line.trim();
                if (line != null && line.isEmpty()) line = null;
                return line;
            } catch (Exception e) {
                return null;
            } finally {
                if (br != null) try { br.close(); } catch (Exception ignored) {}
            }
        }
    }
}
