package com.sysinfo.app;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

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
    }
}
