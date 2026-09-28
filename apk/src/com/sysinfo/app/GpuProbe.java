package com.sysinfo.app;

import java.lang.reflect.Method;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * 离屏 EGL 探针：不依赖 NDK，纯 Java 通过 Android 自带的
 * javax.microedition.khronos EGL 绑定创建 1x1 pbuffer，然后在
 * 该上下文里 glGetString 读 GPU 渲染器与 GL 版本。
 *
 * 返回 [renderer, version]，任一步失败返回 null（由 JS 侧显示「不可用」）。
 */
final class GpuProbe {

    private GpuProbe() {}

    static String[] query() {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (display == null || display == EGL10.EGL_NO_DISPLAY) return null;
        int[] version = new int[2];
        if (!egl.eglInitialize(display, version)) return null;

        try {
            // 只要求 RGB + EGL_PBUFFER_TYPE，任何 GPU 都能命中一个 config
            int[] cfgSpec = {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_RENDERABLE_TYPE, 4,  // EGL_OPENGL_ES2_BIT
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!egl.eglChooseConfig(display, cfgSpec, configs, 1, num)
                    || num[0] < 1) {
                return null;
            }
            int[] surfSpec = { EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE };
            EGLSurface surface = egl.eglCreatePbufferSurface(display, configs[0], surfSpec);
            if (surface == null || surface == EGL10.EGL_NO_SURFACE) return null;

            // 0x3098 = EGL_CONTEXT_CLIENT_VERSION（EGL14 常量，EGL10 未收录）
            int[] ctxSpec = { 0x3098, 2, EGL10.EGL_NONE };
            EGLContext context = egl.eglCreateContext(display, configs[0],
                    EGL10.EGL_NO_CONTEXT, ctxSpec);
            if (context == null || context == EGL10.EGL_NO_CONTEXT) {
                egl.eglDestroySurface(display, surface);
                return null;
            }
            if (!egl.eglMakeCurrent(display, surface, surface, context)) {
                egl.eglDestroyContext(display, context);
                egl.eglDestroySurface(display, surface);
                return null;
            }
            try {
                String renderer = glGetString(GL_RENDERER_INT);
                String versionStr = glGetString(GL_VERSION_INT);
                if (renderer == null) return null;
                return new String[]{ renderer, versionStr };
            } finally {
                egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE,
                        EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                egl.eglDestroyContext(display, context);
                egl.eglDestroySurface(display, surface);
            }
        } finally {
            egl.eglTerminate(display);
        }
    }

    // glGetString 常量（GLES2 头文件里的值）
    private static final int GL_RENDERER_INT = 0x1F01;
    private static final int GL_VERSION_INT = 0x1F02;

    /** 通过反射调 GLES20.glGetString（避免编译期依赖 android.opengl.GLES20 注入问题，
     *  同时兼容仅实现 GLES1 的老设备——按类名依次尝试）。 */
    private static String glGetString(int name) {
        try {
            Class<?> gles;
            try {
                gles = Class.forName("android.opengl.GLES20");
            } catch (ClassNotFoundException e) {
                gles = Class.forName("android.opengl.GLES11");
            }
            Method m = gles.getMethod("glGetString", int.class);
            Object r = m.invoke(null, name);
            return r instanceof String ? (String) r : null;
        } catch (Exception e) {
            return null;
        }
    }
}
