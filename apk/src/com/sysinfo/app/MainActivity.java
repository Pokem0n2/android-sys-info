package com.sysinfo.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
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
