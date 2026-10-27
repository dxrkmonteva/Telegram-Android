package org.telegram.ui.Components.chat.buttons;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.util.Log;
import android.view.TextureView;

/**
 * OpenGL ES 3.0 renderer for the Tide flask. It only draws: the physics lives in {@link TideView}, which pushes a
 * snapshot of the state (surface heights, spray particles, the pulled column, colours) every frame.
 * The shader draws the glass, the layered water, and the liquid outside the glass as a metaball field, so the
 * column, its neck, the blob and every flying droplet merge into one body of liquid with lit normals.
 *
 * If EGL, the context or the shader fail for any reason, {@link #isReady()} stays false (or turns false) and
 * {@link TideView} keeps drawing with the Canvas.
 */
public class TideGLView extends TextureView implements TextureView.SurfaceTextureListener {
    public static final int N1 = 37;   // surface nodes (TideView.N + 1)
    public static final int MP = 96;   // max particles
    public static final int MC = 10;   // column nodes
    public static final int MB = 6;    // bubbles
    // misc layout
    public static final int M_GEO = 0, M_LIGHT = 4, M_BASE = 7, M_DEEP = 10, M_FX = 13, M_BLOB = 17, M_LEVEL = 21, M_BBOX = 22, M_DP = 26, M_SIZE = 32;

    private static final String TAG = "TideGL";

    /** Shared state, written by the UI thread under {@link #lock}. */
    public final Object lock = new Object();
    public final float[] ys = new float[N1];
    public final float[] yb = new float[N1];
    public final float[] pp = new float[MP * 3];
    public final float[] cc = new float[MC * 3];
    public final float[] bb = new float[MB * 3];
    public final float[] misc = new float[M_SIZE];
    public int pn, cn;

    private boolean dirty;
    private volatile boolean running;
    private volatile boolean ready;
    private volatile boolean failed;
    private Thread thread;

    public TideGLView(Context context) {
        super(context);
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    public boolean isReady() {
        return ready && !failed;
    }

    public boolean hasFailed() {
        return failed;
    }

    /** Call after filling the shared arrays (inside synchronized(lock) or right after). */
    public void commit() {
        synchronized (lock) {
            dirty = true;
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- surface lifecycle

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        if (failed) {
            return;
        }
        stopThread();
        running = true;
        final SurfaceTexture surface = st;
        final int w = width, h = height;
        thread = new Thread(() -> renderLoop(surface, w, h), "TideGL");
        thread.start();
        commit();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stopThread();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    private void stopThread() {
        running = false;
        ready = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException ignore) {}
        }
    }

    // ---------------------------------------------------------------- render thread

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            throw new RuntimeException("shader: " + log);
        }
        return s;
    }

    private void renderLoop(SurfaceTexture st, int w, int h) {
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface surf = EGL14.EGL_NO_SURFACE;
        int prog = 0;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) {
                throw new RuntimeException("eglInitialize");
            }
            int[] cfgAttr = {
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            };
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0) || num[0] < 1) {
                throw new RuntimeException("eglChooseConfig");
            }
            int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
                throw new RuntimeException("eglCreateContext");
            }
            surf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], st, new int[]{EGL14.EGL_NONE}, 0);
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
                throw new RuntimeException("eglCreateWindowSurface");
            }
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                throw new RuntimeException("eglMakeCurrent");
            }

            int vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX);
            int fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT);
            prog = GLES30.glCreateProgram();
            GLES30.glAttachShader(prog, vs);
            GLES30.glAttachShader(prog, fs);
            GLES30.glLinkProgram(prog);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(prog));
            }
            GLES30.glDeleteShader(vs);
            GLES30.glDeleteShader(fs);

            int uRes = GLES30.glGetUniformLocation(prog, "u_res");
            int uGeo = GLES30.glGetUniformLocation(prog, "u_geo");
            int uLight = GLES30.glGetUniformLocation(prog, "u_light");
            int uBase = GLES30.glGetUniformLocation(prog, "u_base");
            int uDeep = GLES30.glGetUniformLocation(prog, "u_deep");
            int uFx = GLES30.glGetUniformLocation(prog, "u_fx");
            int uBlob = GLES30.glGetUniformLocation(prog, "u_blob");
            int uBbox = GLES30.glGetUniformLocation(prog, "u_bbox");
            int uLevel = GLES30.glGetUniformLocation(prog, "u_level");
            int uDp = GLES30.glGetUniformLocation(prog, "u_dp");
            int uPn = GLES30.glGetUniformLocation(prog, "u_pn");
            int uCn = GLES30.glGetUniformLocation(prog, "u_cn");
            int uYs = GLES30.glGetUniformLocation(prog, "u_ys");
            int uYb = GLES30.glGetUniformLocation(prog, "u_yb");
            int uP = GLES30.glGetUniformLocation(prog, "u_p");
            int uC = GLES30.glGetUniformLocation(prog, "u_c");
            int uB = GLES30.glGetUniformLocation(prog, "u_b");

            float[] lys = new float[N1], lyb = new float[N1], lpp = new float[MP * 3], lcc = new float[MC * 3], lbb = new float[MB * 3], lm = new float[M_SIZE];
            int lpn, lcn;

            while (running) {
                synchronized (lock) {
                    while (!dirty && running) {
                        lock.wait();
                    }
                    if (!running) {
                        break;
                    }
                    dirty = false;
                    System.arraycopy(ys, 0, lys, 0, N1);
                    System.arraycopy(yb, 0, lyb, 0, N1);
                    System.arraycopy(pp, 0, lpp, 0, MP * 3);
                    System.arraycopy(cc, 0, lcc, 0, MC * 3);
                    System.arraycopy(bb, 0, lbb, 0, MB * 3);
                    System.arraycopy(misc, 0, lm, 0, M_SIZE);
                    lpn = pn;
                    lcn = cn;
                }
                GLES30.glViewport(0, 0, w, h);
                GLES30.glClearColor(0f, 0f, 0f, 0f);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                GLES30.glUseProgram(prog);
                GLES30.glUniform2f(uRes, w, h);
                GLES30.glUniform4f(uGeo, lm[M_GEO], lm[M_GEO + 1], lm[M_GEO + 2], lm[M_GEO + 3]);
                GLES30.glUniform3f(uLight, lm[M_LIGHT], lm[M_LIGHT + 1], lm[M_LIGHT + 2]);
                GLES30.glUniform3f(uBase, lm[M_BASE], lm[M_BASE + 1], lm[M_BASE + 2]);
                GLES30.glUniform3f(uDeep, lm[M_DEEP], lm[M_DEEP + 1], lm[M_DEEP + 2]);
                GLES30.glUniform4f(uFx, lm[M_FX], lm[M_FX + 1], lm[M_FX + 2], lm[M_FX + 3]);
                GLES30.glUniform4f(uBlob, lm[M_BLOB], lm[M_BLOB + 1], lm[M_BLOB + 2], lm[M_BLOB + 3]);
                GLES30.glUniform4f(uBbox, lm[M_BBOX], lm[M_BBOX + 1], lm[M_BBOX + 2], lm[M_BBOX + 3]);
                GLES30.glUniform1f(uLevel, lm[M_LEVEL]);
                GLES30.glUniform1f(uDp, lm[M_DP]);
                GLES30.glUniform1i(uPn, lpn);
                GLES30.glUniform1i(uCn, lcn);
                GLES30.glUniform1fv(uYs, N1, lys, 0);
                GLES30.glUniform1fv(uYb, N1, lyb, 0);
                GLES30.glUniform3fv(uP, MP, lpp, 0);
                GLES30.glUniform3fv(uC, MC, lcc, 0);
                GLES30.glUniform3fv(uB, MB, lbb, 0);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
                if (!EGL14.eglSwapBuffers(dpy, surf)) {
                    throw new RuntimeException("eglSwapBuffers");
                }
                int err = GLES30.glGetError();
                if (err != GLES30.GL_NO_ERROR) {
                    throw new RuntimeException("GL error " + err);
                }
                ready = true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Tide GL renderer disabled, falling back to Canvas", t);
            failed = true;
            ready = false;
        } finally {
            try {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (prog != 0) {
                        GLES30.glDeleteProgram(prog);
                    }
                    if (surf != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(dpy, surf);
                    }
                    if (ctx != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(dpy, ctx);
                    }
                    EGL14.eglTerminate(dpy);
                }
            } catch (Throwable ignore) {}
        }
    }

    // ---------------------------------------------------------------- shaders

    private static final String VERTEX =
        "#version 300 es\n" +
        "void main(){\n" +
        "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n" +
        "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n";

    private static final String FRAGMENT =
        "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform vec2 u_res;\n" +
        "uniform vec4 u_geo;\n" +     // cx, cy, R, yc (surface centre y)
        "uniform vec3 u_light;\n" +
        "uniform vec3 u_base;\n" +
        "uniform vec3 u_deep;\n" +
        "uniform vec4 u_fx;\n" +      // time, glint, ringT, armed
        "uniform vec4 u_blob;\n" +    // x, y, r, pulse
        "uniform vec4 u_bbox;\n" +    // liquid bounds
        "uniform float u_level;\n" +
        "uniform float u_dp;\n" +
        "uniform int u_pn;\n" +
        "uniform int u_cn;\n" +
        "uniform float u_ys[37];\n" +
        "uniform float u_yb[37];\n" +
        "uniform vec3 u_p[96];\n" +
        "uniform vec3 u_c[10];\n" +
        "uniform vec3 u_b[6];\n" +
        "out vec4 o;\n" +
        "\n" +
        "vec4 over(vec4 s, vec4 d){ return vec4(s.rgb * s.a + d.rgb * (1.0 - s.a), s.a + d.a * (1.0 - s.a)); }\n" +
        "\n" +
        "float surf(float x, float cx, float R, bool back){\n" +
        "  float fx = clamp((x - (cx - R)) / (2.0 * R), 0.0, 1.0) * 36.0;\n" +
        "  int i = int(min(floor(fx), 35.0));\n" +
        "  float f = fx - float(i);\n" +
        "  float a = back ? u_yb[i] : u_ys[i];\n" +
        "  float b = back ? u_yb[i + 1] : u_ys[i + 1];\n" +
        "  return mix(a, b, f);\n" +
        "}\n" +
        "\n" +
        "vec3 wgrad(float t){\n" +
        "  return t < 0.45 ? mix(u_light, u_base, t / 0.45) : mix(u_base, u_deep, (t - 0.45) / 0.55);\n" +
        "}\n" +
        "\n" +
        "void main(){\n" +
        "  vec2 p = vec2(gl_FragCoord.x, u_res.y - gl_FragCoord.y);\n" +
        "  float dp = u_dp;\n" +
        "  float cx = u_geo.x; float cy = u_geo.y; float R = u_geo.z; float yc = u_geo.w;\n" +
        "  float time = u_fx.x;\n" +
        "  vec2 q = p - vec2(cx, cy);\n" +
        "  float rl = length(q);\n" +
        "  float inGlass = 1.0 - smoothstep(R - 0.8, R + 0.5, rl);\n" +
        "  vec4 acc = vec4(0.0);\n" +
        "\n" +
        "  if (inGlass > 0.0) {\n" +
        "    vec4 g = vec4(0.0, 0.0, 0.0, 0.16);\n" +
        "    float t = clamp((p.y - (cy - R)) / (2.0 * R), 0.0, 1.0);\n" +
        "    if (u_level > 0.5) {\n" +
        "      float ys = surf(p.x, cx, R, false);\n" +
        "      float yb = surf(p.x, cx, R, true);\n" +
        "      float dd = p.y - ys;\n" +
        "      float wm = smoothstep(-0.7, 0.7, dd);\n" +
        "      float bm = smoothstep(-0.7, 0.7, p.y - yb);\n" +
        "      vec3 wc = wgrad(t);\n" +
        "      g = over(vec4(mix(u_light, u_base, t), bm * 0.55), g);\n" +
        "      g = over(vec4(wc, wm * 0.94), g);\n" +
        "      // light just under the surface\n" +
        "      float band = (1.0 - smoothstep(0.0, 8.0 * dp, dd)) * wm;\n" +
        "      g = over(vec4(1.0, 1.0, 1.0, band * 0.16), g);\n" +
        "      // bright surface line\n" +
        "      float line = exp(-dd * dd / (1.1 * dp * dp * 0.5 + 0.3));\n" +
        "      g = over(vec4(1.0, 1.0, 1.0, line * 0.68), g);\n" +
        "      // bubbles\n" +
        "      for (int i = 0; i < 6; i++) {\n" +
        "        vec3 b = u_b[i];\n" +
        "        if (b.z > 0.0) {\n" +
        "          float d = length(p - b.xy) - b.z;\n" +
        "          float a = (1.0 - smoothstep(-0.5, 0.8, d)) * 0.32 * wm;\n" +
        "          g = over(vec4(1.0, 1.0, 1.0, a), g);\n" +
        "          float hl = (1.0 - smoothstep(0.0, b.z * 0.35, length(p - b.xy + vec2(b.z * 0.3, b.z * 0.3)))) * 0.7 * wm;\n" +
        "          g = over(vec4(1.0, 1.0, 1.0, hl), g);\n" +
        "        }\n" +
        "      }\n" +
        "      // caustic on the bottom\n" +
        "      if (u_level > 1.5) {\n" +
        "        vec2 e = (p - vec2(cx + sin(time * 0.9) * R * 0.3, cy + R * 0.81)) / vec2(R * 0.45, R * 0.09);\n" +
        "        g = over(vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(0.55, 1.0, length(e))) * 0.2 * wm), g);\n" +
        "      }\n" +
        "      // glint travelling along the surface\n" +
        "      float gl = u_fx.y;\n" +
        "      if (gl > 0.0) {\n" +
        "        float gx = cx + (0.5 - gl) * R * 1.4;\n" +
        "        float ga = exp(-pow((p.x - gx) / (9.0 * dp), 2.0)) * exp(-dd * dd / (3.0 * dp * dp));\n" +
        "        g = over(vec4(1.0, 1.0, 1.0, ga * gl * 0.5), g);\n" +
        "      }\n" +
        "    }\n" +
        "    // glass thickness and reflections\n" +
        "    float rad = rl / R;\n" +
        "    g = over(vec4(0.0, 0.0, 0.0, smoothstep(0.66, 1.0, rad) * 0.36), g);\n" +
        "    g = over(vec4(1.0, 1.0, 1.0, smoothstep(0.86, 1.0, rad) * 0.1), g);\n" +
        "    float ang = atan(q.y, q.x);\n" +
        "    float rr = abs(rl - (R - 4.0 * dp));\n" +
        "    float arc1 = (1.0 - smoothstep(0.0, 1.2 * dp, rr)) * smoothstep(-2.78, -2.45, ang) * (1.0 - smoothstep(-2.0, -1.72, ang));\n" +
        "    g = over(vec4(1.0, 1.0, 1.0, arc1 * 0.62), g);\n" +
        "    float arc2 = (1.0 - smoothstep(0.0, 0.8 * dp, rr)) * smoothstep(0.3, 0.55, ang) * (1.0 - smoothstep(0.85, 1.1, ang));\n" +
        "    g = over(vec4(1.0, 1.0, 1.0, arc2 * 0.28), g);\n" +
        "    float dark = 1.0 - smoothstep(0.0, 1.0 * dp, abs(rl - (R - 0.5 * dp)));\n" +
        "    g = over(vec4(0.0, 0.0, 0.0, dark * 0.3), g);\n" +
        "    float rim = 1.0 - smoothstep(0.0, 0.8 * dp, abs(rl - (R - 1.4 * dp)));\n" +
        "    g = over(vec4(1.0, 1.0, 1.0, rim * (u_fx.w > 0.5 ? 0.92 : 0.42)), g);\n" +
        "    float sp = 1.0 - smoothstep(0.0, 1.6 * dp, length(p - vec2(cx - R * 0.5, cy - R * 0.62)));\n" +
        "    g = over(vec4(1.0, 1.0, 1.0, sp * 0.7), g);\n" +
        "    acc = vec4(g.rgb * inGlass, g.a * inGlass);\n" +
        "  }\n" +
        "\n" +
        "  // liquid outside the glass: one metaball field for the column, the blob and every droplet\n" +
        "  if (u_pn + u_cn > 0 && p.x > u_bbox.x && p.x < u_bbox.z && p.y > u_bbox.y && p.y < u_bbox.w) {\n" +
        "    float fc = 0.0; vec2 gc = vec2(0.0);\n" +
        "    for (int i = 0; i < 9; i++) {\n" +
        "      if (i >= u_cn - 1) { break; }\n" +
        "      vec3 A = u_c[i]; vec3 B = u_c[i + 1];\n" +
        "      vec2 ba = B.xy - A.xy;\n" +
        "      float h = clamp(dot(p - A.xy, ba) / max(dot(ba, ba), 0.001), 0.0, 1.0);\n" +
        "      vec2 d = p - (A.xy + ba * h);\n" +
        "      float r = mix(A.z, B.z, h);\n" +
        "      float dd = dot(d, d) + 1.0;\n" +
        "      float k = r * r / dd;\n" +
        "      if (k > fc) { fc = k; gc = -2.0 * k * d / dd; }\n" +
        "    }\n" +
        "    float f = fc; vec2 gr = gc;\n" +
        "    for (int i = 0; i < 96; i++) {\n" +
        "      if (i >= u_pn) { break; }\n" +
        "      vec3 c = u_p[i];\n" +
        "      vec2 d = p - c.xy;\n" +
        "      float dd = dot(d, d) + 1.0;\n" +
        "      float k = c.z * c.z / dd;\n" +
        "      f += k; gr += -2.0 * k * d / dd;\n" +
        "    }\n" +
        "    float cover = clamp((f - 1.0) / max(fwidth(f), 0.0001) + 0.5, 0.0, 1.0);\n" +
        "    cover *= 1.0 - smoothstep(yc - 0.5, yc + 1.0, p.y);\n" +
        "    if (cover > 0.0) {\n" +
        "      float e = clamp((f - 1.0) / 1.6, 0.0, 1.0);\n" +
        "      vec2 gn = gr / max(length(gr), 0.0001);\n" +
        "      vec3 n = normalize(vec3(-gn * (1.0 - e) * 0.95, 0.3 + e * 0.95));\n" +
        "      vec3 L = normalize(vec3(-0.5, -0.65, 0.7));\n" +
        "      float diff = max(dot(n, L), 0.0);\n" +
        "      float spec = pow(max(dot(n, normalize(L + vec3(0.0, 0.0, 1.0))), 0.0), 36.0);\n" +
        "      float fres = pow(1.0 - n.z, 2.0);\n" +
        "      float t = clamp((p.y - u_bbox.y) / max(yc - u_bbox.y, 1.0), 0.0, 1.0);\n" +
        "      vec3 base = wgrad(t * 0.8);\n" +
        "      vec3 col = base * (0.72 + 0.5 * diff) + vec3(1.0) * spec * 0.85 + u_light * fres * 0.4;\n" +
        "      float edge = (1.0 - e) * 0.18;\n" +
        "      col = mix(col, u_deep, edge);\n" +
        "      acc = over(vec4(col, cover * 0.96), acc);\n" +
        "    }\n" +
        "  }\n" +
        "\n" +
        "  // ring around the armed blob\n" +
        "  if (u_fx.w > 0.5) {\n" +
        "    float rr = abs(length(p - u_blob.xy) - (u_blob.z + 3.5 * dp + u_blob.w * 1.5 * dp));\n" +
        "    float a = (1.0 - smoothstep(0.0, 1.1 * dp, rr)) * (0.55 + 0.35 * u_blob.w);\n" +
        "    acc = over(vec4(1.0, 1.0, 1.0, a), acc);\n" +
        "  }\n" +
        "  // completion ring\n" +
        "  if (u_fx.z < 1.0) {\n" +
        "    float rr = abs(rl - (R + 2.0 * dp + 20.0 * dp * u_fx.z));\n" +
        "    float w = (2.0 * (1.0 - u_fx.z) + 0.6) * dp * 0.5;\n" +
        "    float a = (1.0 - smoothstep(0.0, w + 0.6, rr)) * (1.0 - u_fx.z) * 0.9;\n" +
        "    acc = over(vec4(u_light, a), acc);\n" +
        "  }\n" +
        "  o = acc;\n" +
        "}\n";
}
