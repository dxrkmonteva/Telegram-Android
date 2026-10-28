package org.telegram.ui.Components.chat.buttons;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * "Tide": the unread counter of the page-down button as a glass of water.
 *
 * - Water level follows the unread count; opening a chat with unread messages fills the empty glass drop by drop.
 * - The surface sloshes with the phone tilt (accelerometer): area-conserving tilted plane + damped wave field,
 *   a second lagging layer, wall meniscus, foam on fast waves, rising bubbles and a moving caustic.
 * - Dragging the glass up pulls the water into a living column: a chain of masses that lags and whips behind the
 *   finger (also sideways), with travelling width ripples, a pinching neck that sheds satellite drops, and a
 *   blob at the tip. Release past the mark: the column shatters into spray and the chat scrolls down.
 *
 * The view is larger than the button and draws outside of it, so its parents must not clip children.
 * It never handles touches itself; {@link ChatActivityBlurredRoundPageDownButton} drives the pull.
 * No allocations in draw.
 */
@SuppressLint("ViewConstructor")
public class TideView extends View implements SensorEventListener {
    public static final int VIEW_W_DP = 240;
    public static final int VIEW_H_DP = 68 + 250;
    public static final int RADIUS_DP = 33;
    public static final int PULL_MAX_DP = 205;
    public static final int PULL_ARM_DP = 92;
    public static final int SIDE_MAX_DP = 90;

    private static final int N = 36;      // surface nodes
    private static final int M = 10;      // column nodes
    private static final int MAX_P = 96;
    private static final int BUBBLES = 6;
    private static final float STEP = 1f / 120f;

    public interface EntryListener {
        void onProgress(int landed, int total);
    }

    private final Theme.ResourcesProvider resourcesProvider;

    // geometry
    private float cx, cy, R;
    private final Path circle = new Path();
    private final Path water = new Path();
    private final Path back = new Path();
    private final Path strip = new Path();
    private final RectF rect = new RectF();

    // paints
    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint columnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int baseColor, lightColor, deepColor;

    // water state
    private float level, levelVel, levelTarget;
    private float slope, slopeVel, slopeTarget;
    private final float[] h = new float[N + 1];
    private final float[] v = new float[N + 1];
    private float drain;
    private float glint;
    private float time;
    private float ringT = 1f;

    // pull / column state
    private boolean pulling;
    private float pullTarget;     // px, rubber banded finger distance
    private float sideTarget;     // px, finger lateral offset
    private float blobY, blobVel; // px above water surface
    private float blobX, blobXVel;
    private final float[] offX = new float[M];
    private final float[] offV = new float[M];
    private final float[] colW = new float[M];
    private final float[] colY = new float[M];
    private float rippleAmp;
    private boolean armed;
    private boolean flying;
    private float flyTime;
    private int lastTickBand;

    // particles: 0 = falls into the glass (message), 1 = free spray, 2 = entry drop
    private final float[] px = new float[MAX_P];
    private final float[] py = new float[MAX_P];
    private final float[] pvx = new float[MAX_P];
    private final float[] pvy = new float[MAX_P];
    private final float[] pr = new float[MAX_P];
    private final float[] plife = new float[MAX_P];
    private final int[] ptype = new int[MAX_P];
    private int pCount;

    private final float[] bx = new float[BUBBLES];
    private final float[] by = new float[BUBBLES];
    private final float[] bs = new float[BUBBLES];

    // sensor
    private SensorManager sensorManager;
    private Sensor accel;
    private float lax, lay, lmag = 9.81f;
    private boolean sensorOn;

    // entry animation: opening a chat with unread messages fills the empty glass, one drop per message
    private EntryListener entryListener;
    private boolean entryEnabled = true;
    private boolean entryPlayed, entryArmed, entryActive;
    private int entryTotal, entrySpawned, entryLanded;
    private float entryT;
    private int lastCount;
    private long lastTickTime;

    private TideGLView gl;

    private long lastTime;
    private float acc;
    private int rng = 0x2545F491;

    public TideView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        glassPaint.setStyle(Paint.Style.STROKE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < BUBBLES; i++) {
            bx[i] = rnd() * 2f - 1f;
            by[i] = rnd();
            bs[i] = 0.5f + rnd();
        }
        updateColors();
    }

    // ---------------------------------------------------------------- public API

    public void updateColors() {
        baseColor = Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider);
        lightColor = ColorUtils.blendARGB(baseColor, Color.WHITE, 0.5f);
        deepColor = ColorUtils.blendARGB(baseColor, Color.BLACK, 0.32f);
        rebuildShaders();
        invalidate();
    }

    private static float levelFor(int count) {
        return count <= 0 ? 0.06f : 0.06f + 0.92f * (1f - (float) Math.exp(-count / 14f));
    }

    private static float entrySeconds(int n) {
        return Math.min(7f, 2.0f + 0.605f * (float) (Math.log(n + 1) / Math.log(2)));
    }

    public void setEntryEnabled(boolean enabled) {
        entryEnabled = enabled;
    }

    public void setEntryListener(EntryListener l) {
        entryListener = l;
    }

    public boolean isEntryPending() {
        return entryArmed || entryActive;
    }

    public void setCount(int count, boolean animated) {
        int prev = lastCount;
        lastCount = count;
        if (entryArmed || entryActive) {
            entryTotal = Math.max(entryTotal, count);
            return;
        }
        if (!entryPlayed && entryEnabled && count >= 2) {
            entryPlayed = true;
            entryArmed = true;
            entryTotal = count;
            entrySpawned = 0;
            entryLanded = 0;
            entryT = 0;
            level = 0;
            levelVel = 0;
            levelTarget = levelFor(0);
            invalidate();
            return;
        }
        float target = levelFor(count);
        boolean grew = count > prev && target > levelTarget + 0.001f;
        levelTarget = target;
        if (!animated) {
            level = target;
            levelVel = 0;
        } else if (grew && count > 0) {
            spawnDrop(0);
        }
        if (count <= 0) {
            drain = 0;
        }
        invalidate();
    }

    /** Called while the finger drags the button up. dx / dy in px relative to the touch start (dy > 0 = up). */
    public void setPull(float dx, float dy) {
        float max = dp(PULL_MAX_DP);
        pulling = true;
        pullTarget = max * (1f - (float) Math.exp(-Math.max(0, dy) / dp(150)));
        float side = dp(SIDE_MAX_DP);
        sideTarget = Math.max(-side, Math.min(side, dx));
        boolean nowArmed = pullTarget >= dp(PULL_ARM_DP);
        if (nowArmed != armed) {
            armed = nowArmed;
            if (armed) {
                glint = 1f;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        int band = (int) (pullTarget / dp(26));
        if (band != lastTickBand) {
            lastTickBand = band;
            if (!armed) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        invalidate();
    }

    /** @return true if the column was poured out (caller should trigger the scroll). */
    public boolean release(boolean cancel) {
        pulling = false;
        boolean pour = armed && !cancel;
        armed = false;
        lastTickBand = 0;
        sideTarget = 0;
        if (pour) {
            flying = true;
            flyTime = 0;
            blobVel = dp(1250);
            glint = 1f;
            shatterColumn();
            try {
                performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.KEYBOARD_TAP);
            } catch (Throwable ignore) {}
        } else {
            pullTarget = 0;
            for (int i = 0; i <= N; i++) {
                v[i] += dp(110) * (1f - Math.abs(2f * i / N - 1f));
            }
        }
        invalidate();
        return pour;
    }

    public void setGL(TideGLView g) {
        gl = g;
    }

    public boolean isPulling() {
        return pulling;
    }

    public void splash(float strength) {
        for (int i = 0; i <= N; i++) {
            v[i] += (rnd() - 0.5f) * dp(120) * strength;
        }
        invalidate();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startSensor();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSensor();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) {
            startSensor();
        } else {
            stopSensor();
        }
    }

    private void startSensor() {
        if (sensorOn) {
            return;
        }
        try {
            sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (accel != null) {
                    sensorOn = sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
                }
            }
        } catch (Throwable ignore) {
            sensorOn = false;
        }
        lastTime = 0;
    }

    private void stopSensor() {
        if (sensorOn && sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Throwable ignore) {}
        }
        sensorOn = false;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float ax = e.values[0], ay = e.values[1];
        float mag = (float) Math.sqrt(ax * ax + ay * ay + e.values[2] * e.values[2]);
        lax += (ax - lax) * 0.35f;
        lay += (ay - lay) * 0.35f;
        float dm = mag - lmag;
        lmag += (mag - lmag) * 0.08f;
        float upright = Math.max(0f, Math.min(1f, (lay - 2f) / 4f));
        float t = (lax / Math.max(lay, 2f)) * upright;
        slopeTarget = Math.max(-0.85f, Math.min(0.85f, t));
        if (Math.abs(dm) > 1.6f && level > 0.02f) {
            float k = Math.min(Math.abs(dm), 14f) * dp(7);
            for (int i = 0; i <= N; i++) {
                v[i] += (rnd() - 0.5f) * k;
            }
            levelVel += Math.signum(dm) * Math.min(Math.abs(dm), 10f) * 0.06f;
        }
        if (getVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onSizeChanged(int w, int h2, int oldw, int oldh) {
        super.onSizeChanged(w, h2, oldw, oldh);
        R = dp(RADIUS_DP);
        cx = w / 2f;
        cy = h2 - dp(34);
        circle.reset();
        circle.addCircle(cx, cy, R, Path.Direction.CW);
        rebuildShaders();
    }

    private void rebuildShaders() {
        if (R <= 0) {
            return;
        }
        int top = ColorUtils.setAlphaComponent(lightColor, 214);
        int mid = ColorUtils.setAlphaComponent(baseColor, 228);
        int bottom = ColorUtils.setAlphaComponent(deepColor, 242);
        waterPaint.setShader(new LinearGradient(0, cy - R, 0, cy + R, new int[]{top, mid, bottom}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        backPaint.setShader(new LinearGradient(0, cy - R, 0, cy + R, ColorUtils.setAlphaComponent(lightColor, 150), ColorUtils.setAlphaComponent(baseColor, 160), Shader.TileMode.CLAMP));
        columnPaint.setShader(new LinearGradient(0, cy - dp(PULL_MAX_DP), 0, cy + R, new int[]{ColorUtils.setAlphaComponent(lightColor, 235), ColorUtils.setAlphaComponent(baseColor, 235), ColorUtils.setAlphaComponent(deepColor, 245)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        // inner edge darkening gives the glass its thickness
        depthPaint.setShader(new RadialGradient(cx, cy, R, new int[]{0x00000000, 0x00000000, 0x55000000}, new float[]{0f, 0.68f, 1f}, Shader.TileMode.CLAMP));
    }

    // ---------------------------------------------------------------- helpers

    private float rnd() {
        rng ^= rng << 13;
        rng ^= rng >>> 17;
        rng ^= rng << 5;
        return (rng & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Level of the water surface at the glass centre; area is conserved when the plane tilts. */
    private float surfaceCenterY() {
        float s = Math.min(1f, blobY / dp(PULL_MAX_DP));
        float eff = Math.max(0f, level * (1f - 0.5f * drain) - 0.25f * s * level);
        float flat = cy + R - eff * 2f * R;
        float d = (flat - cy) * (float) Math.sqrt(1f + slope * slope);
        return Math.max(cy - R * 0.98f, Math.min(cy + R * 0.98f, cy + d));
    }

    private float surfaceAt(float x, float yc, int i, float ambient) {
        float dx = (x - cx) / R;
        float men = dp(1.7f) * (float) Math.pow(Math.min(1f, Math.abs(dx)), 6);
        return yc + slope * (x - cx) + h[i] + ambient - men;
    }

    private boolean spawnDrop(int type) {
        if (pCount >= MAX_P) {
            return false;
        }
        int i = pCount++;
        px[i] = cx + (rnd() - 0.5f) * R * 0.9f;
        py[i] = cy - R - dp(30) - rnd() * dp(40);
        pvx[i] = 0;
        pvy[i] = dp(120);
        pr[i] = dp(2.2f) + rnd() * dp(1.4f);
        plife[i] = 1f;
        ptype[i] = type;
        return true;
    }

    private void addSpray(float x, float y, float vx, float vy, float r, float life) {
        if (pCount >= MAX_P) {
            return;
        }
        int i = pCount++;
        px[i] = x;
        py[i] = y;
        pvx[i] = vx;
        pvy[i] = vy;
        pr[i] = r;
        plife[i] = life;
        ptype[i] = 1;
    }

    private void burst(float x, float y, int count, float speed) {
        for (int k = 0; k < count; k++) {
            float a = (float) (-Math.PI / 2 + (rnd() - 0.5f) * 2.5f);
            float s = speed * (0.35f + rnd() * 0.75f);
            addSpray(x + (rnd() - 0.5f) * dp(6), y, (float) Math.cos(a) * s, (float) Math.sin(a) * s, dp(1.2f) + rnd() * dp(2.8f), 1f);
        }
    }

    /** Small crown: a few droplets thrown up and out where a message drop lands. */
    private void crown(float x, float y) {
        for (int k = 0; k < 5; k++) {
            float side = (k - 2) / 2f;
            addSpray(x + side * dp(2), y - dp(1), side * dp(130) + (rnd() - 0.5f) * dp(40), -dp(250) - rnd() * dp(200) + Math.abs(side) * dp(80), dp(0.9f) + rnd() * dp(1.1f), 0.6f);
        }
    }

    private void removeParticle(int i) {
        pCount--;
        px[i] = px[pCount];
        py[i] = py[pCount];
        pvx[i] = pvx[pCount];
        pvy[i] = pvy[pCount];
        pr[i] = pr[pCount];
        plife[i] = plife[pCount];
        ptype[i] = ptype[pCount];
    }

    private void shatterColumn() {
        float yc = surfaceCenterY();
        float len = Math.max(dp(8), blobY);
        for (int i = 1; i < M - 1; i++) {
            float t = i / (float) (M - 1);
            float x = cx + offX[i];
            float y = yc - len * t;
            float w = Math.max(dp(1.4f), colW[i]);
            addSpray(x, y, offV[i] * 0.6f + (rnd() - 0.5f) * dp(260), -dp(500) * t - rnd() * dp(260), w * 0.8f, 1f);
            addSpray(x, y, (rnd() - 0.5f) * dp(380), -dp(380) * t - rnd() * dp(200), w * 0.5f, 0.9f);
        }
    }

    private void finishEntry() {
        entryActive = false;
        levelTarget = levelFor(Math.max(entryTotal, lastCount));
        if (entryListener != null) {
            entryListener.onProgress(Math.max(entryTotal, lastCount), entryTotal);
        }
        for (int k = 0; k <= N; k++) {
            v[k] += dp(320) * (1f - Math.abs(2f * k / N - 1f) * 0.5f) * (k % 2 == 0 ? 1f : -0.6f);
        }
        glint = 1f;
        ringT = 0f;
        burst(cx, cy - R, 16, dp(540));
        try {
            performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Throwable ignore) {}
    }

    // ---------------------------------------------------------------- simulation

    private boolean step(float dt) {
        boolean alive = false;
        time += dt;

        // entry animation: drops fall one after another, accelerating a little
        if (entryActive) {
            entryT += dt;
            float total = entrySeconds(entryTotal);
            while (entrySpawned < entryTotal) {
                float f = entrySpawned / (float) Math.max(1, entryTotal - 1);
                float when = 0.3f + total * (1f - (float) Math.pow(1f - f, 1.25f));
                if (entryT < when || !spawnDrop(2)) {
                    break;
                }
                entrySpawned++;
            }
            alive = true;
        }

        // level spring
        levelVel += (70f * (levelTarget - level) - 9f * levelVel) * dt;
        level += levelVel * dt;
        if (level < 0) {
            level = 0;
        }
        if (Math.abs(levelTarget - level) > 0.002f || Math.abs(levelVel) > 0.01f) {
            alive = true;
        }

        // water taken by the poured column
        if (flying) {
            drain = Math.min(1f, drain + 1.8f * dt);
            alive = true;
        } else if (drain > 0f) {
            drain = Math.max(0f, drain - dt * 0.9f);
            alive = true;
        }

        // tilt: damped spring so the water overshoots and sloshes
        float prevSlopeVel = slopeVel;
        slopeVel += (110f * (slopeTarget - slope) - 5.5f * slopeVel) * dt;
        slope += slopeVel * dt;
        float slosh = (slopeVel - prevSlopeVel) / Math.max(dt, 1e-4f);
        if (Math.abs(slopeTarget - slope) > 0.003f || Math.abs(slopeVel) > 0.02f) {
            alive = true;
        }

        // surface waves (damped 1D wave equation) driven by the sloshing
        float amp = level > 0.01f ? 1f : 0f;
        for (int i = 0; i <= N; i++) {
            float l = h[i > 0 ? i - 1 : i];
            float r = h[i < N ? i + 1 : i];
            float a = 5600f * (l + r - 2f * h[i]) - 36f * h[i] - 3.0f * v[i];
            a += -slosh * (2f * i / N - 1f) * dp(2.4f) * amp;
            v[i] += a * dt;
        }
        float maxAmp = dp(5.5f);
        for (int i = 0; i <= N; i++) {
            h[i] += v[i] * dt;
            if (h[i] > maxAmp) {
                h[i] = maxAmp;
                v[i] *= 0.5f;
            } else if (h[i] < -maxAmp) {
                h[i] = -maxAmp;
                v[i] *= 0.5f;
            }
            if (Math.abs(h[i]) > 0.02f || Math.abs(v[i]) > 0.3f) {
                alive = true;
            }
        }

        // column: pulled blob + chain of masses that lags and whips sideways
        if (flying) {
            flyTime += dt;
            blobY += blobVel * dt;
            blobVel -= dp(520) * dt;
            if (flyTime > 0.17f) {
                flying = false;
                float yTop = surfaceCenterY() - blobY;
                burst(cx + blobX, yTop, 24, dp(840));
                burst(cx + blobX, yTop, 12, dp(430));
                pullTarget = 0;
                blobY = 0;
                blobVel = 0;
                for (int i = 0; i <= N; i++) {
                    v[i] += (rnd() - 0.5f) * dp(170);
                }
            }
            alive = true;
        } else {
            float target = pulling || pullTarget > 0 ? pullTarget : 0f;
            float k = pulling ? 260f : 150f;
            float d = pulling ? 26f : 10f;
            blobVel += (k * (target - blobY) - d * blobVel) * dt;
            blobY += blobVel * dt;
            if (!pulling && pullTarget == 0f && Math.abs(blobY) < dp(0.6f) && Math.abs(blobVel) < dp(8)) {
                blobY = 0;
                blobVel = 0;
            }
            if (blobY < 0) {
                blobY = 0;
                if (blobVel < 0) {
                    float hit = -blobVel / dp(400);
                    for (int i = 0; i <= N; i++) {
                        v[i] += dp(260) * Math.min(hit, 1.5f) * (1f - Math.abs(2f * i / N - 1f) * 0.6f);
                    }
                    blobVel = 0;
                }
            }
            if (pulling || blobY > 0 || Math.abs(blobVel) > 0) {
                alive = true;
            }
        }
        // lateral blob and chain
        float sideT = pulling ? sideTarget : 0f;
        blobXVel += (320f * (sideT - blobX) - 22f * blobXVel) * dt;
        blobX += blobXVel * dt;
        if (blobY > dp(1f) || Math.abs(blobX) > dp(0.5f)) {
            offX[0] = 0;
            offV[0] = 0;
            for (int i = 1; i < M; i++) {
                float t = i / (float) (M - 1);
                float desired = blobX * (float) Math.pow(t, 1.4f);
                float nl = offX[i - 1];
                float nr = offX[i < M - 1 ? i + 1 : i];
                float a = 260f * (desired - offX[i]) + 520f * ((nl + nr) * 0.5f - offX[i]) - 9f * offV[i];
                offV[i] += a * dt;
            }
            for (int i = 1; i < M; i++) {
                offX[i] += offV[i] * dt;
            }
            offX[M - 1] = blobX;
            alive = true;
        }
        float ra = Math.min(0.22f, Math.abs(blobVel) / dp(2400));
        rippleAmp += (ra - rippleAmp) * Math.min(1f, dt * 14f);

        // a thin neck sheds satellite droplets
        float s = Math.min(1f, blobY / dp(PULL_MAX_DP));
        if (s > 0.55f && (pulling || blobY > dp(30)) && rnd() < dt * (s - 0.4f) * 14f) {
            float yc = surfaceCenterY();
            float t = 0.3f + rnd() * 0.4f;
            addSpray(cx + offX[Math.min(M - 1, (int) (t * (M - 1)))], yc - blobY * t, (rnd() - 0.5f) * dp(60), dp(30), dp(0.9f) + rnd() * dp(1.0f), 0.9f);
        }

        // particles
        float g = dp(1900);
        for (int i = pCount - 1; i >= 0; i--) {
            pvy[i] += g * dt;
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            if (ptype[i] != 1) {
                float yc = surfaceCenterY();
                float sy = surfaceAt(px[i], yc, 0, 0);
                if (py[i] >= sy - pr[i] && py[i] < cy + R) {
                    int idx = Math.max(0, Math.min(N, Math.round((px[i] - (cx - R)) / (2f * R) * N)));
                    for (int k2 = -3; k2 <= 3; k2++) {
                        int j = idx + k2;
                        if (j >= 0 && j <= N) {
                            v[j] += dp(210) * (1f - Math.abs(k2) / 4f);
                        }
                    }
                    boolean entryDrop = ptype[i] == 2;
                    levelVel += entryDrop ? 0.03f : 0.12f;
                    glint = Math.max(glint, 0.6f);
                    crown(px[i], sy);
                    removeParticle(i);
                    if (entryDrop && entryActive) {
                        entryLanded++;
                        levelTarget = levelFor(entryLanded);
                        if (entryListener != null) {
                            entryListener.onProgress(entryLanded, entryTotal);
                        }
                        long t = SystemClock.uptimeMillis();
                        if (t - lastTickTime > 70) {
                            lastTickTime = t;
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        if (entryLanded >= entryTotal) {
                            finishEntry();
                        }
                    }
                    continue;
                }
            } else {
                plife[i] -= dt * 1.1f;
                if (plife[i] <= 0 || py[i] > cy + dp(80)) {
                    removeParticle(i);
                    continue;
                }
                // spray falling back into the glass makes small rings
                if (py[i] > cy - R && py[i] < cy + R * 0.6f && Math.abs(px[i] - cx) < R * 0.8f && pvy[i] > 0 && level > 0.05f) {
                    float sy = surfaceAt(px[i], surfaceCenterY(), 0, 0);
                    if (py[i] > sy) {
                        int idx = Math.max(0, Math.min(N, Math.round((px[i] - (cx - R)) / (2f * R) * N)));
                        v[idx] += dp(70);
                        removeParticle(i);
                        continue;
                    }
                }
            }
            alive = true;
        }
        if (glint > 0) {
            glint = Math.max(0f, glint - dt * 2.2f);
            alive = true;
        }
        if (level > 0.12f) {
            for (int i = 0; i < BUBBLES; i++) {
                by[i] -= dt * 0.2f * bs[i];
                if (by[i] < 0) {
                    by[i] = 1f;
                    bx[i] = rnd() * 2f - 1f;
                }
            }
        }
        return alive;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (R <= 0) {
            return;
        }
        if (entryArmed && getParent() instanceof View && ((View) getParent()).getAlpha() > 0.6f) {
            entryArmed = false;
            entryActive = true;
            entryT = 0;
            lastTime = 0;
            if (entryListener != null) {
                entryListener.onProgress(0, entryTotal);
            }
        }
        long now = SystemClock.uptimeMillis();
        if (lastTime == 0 || now - lastTime > 100) {
            lastTime = now - 8;
        }
        acc += Math.min(0.05f, (now - lastTime) / 1000f);
        lastTime = now;
        boolean alive = false;
        int guard = 0;
        while (acc >= STEP && guard++ < 8) {
            alive |= step(STEP);
            acc -= STEP;
        }

        float yc = surfaceCenterY();
        boolean column = blobY > dp(1.2f) || flying;

        if (gl != null && !gl.hasFailed()) {
            pushGL(yc, column);
            if (gl.isReady()) {
                if (alive || level > 0.02f || sensorOn && Math.abs(slopeTarget - slope) > 0.0005f || ringT < 1f) {
                    if (ringT < 1f) {
                        ringT = Math.min(1f, ringT + 0.016f * 1.1f);
                    }
                    postInvalidateOnAnimation();
                }
                return;
            }
        }

        // 1. glass inner tint
        fillPaint.setColor(ColorUtils.setAlphaComponent(Color.BLACK, 40));
        canvas.drawCircle(cx, cy, R, fillPaint);

        // 2. water body clipped to the glass
        if (level > 0.004f || drain > 0) {
            canvas.save();
            canvas.clipPath(circle);
            float left = cx - R;
            float st = 2f * R / N;

            // back layer: lags behind, ambient ripple keeps the water alive at rest
            back.reset();
            float amb = dp(0.7f);
            float yb0 = surfaceAt(left, yc, 0, amb * (float) Math.sin(time * 1.7f)) + dp(1.5f);
            back.moveTo(left, yb0);
            for (int i = 1; i <= N; i++) {
                float x = left + st * i;
                int ph = (i + N / 3) % (N + 1);
                float y = surfaceAt(x, yc, ph, amb * (float) Math.sin(x * 0.33f + time * 1.7f)) + dp(1.5f) - h[ph] * 0.35f;
                back.lineTo(x, y);
            }
            back.lineTo(cx + R, cy + R + dp(3));
            back.lineTo(left, cy + R + dp(3));
            back.close();
            canvas.drawPath(back, backPaint);

            // front layer
            water.reset();
            float ya0 = surfaceAt(left, yc, 0, amb * (float) Math.sin(time * 2.3f + 1f));
            water.moveTo(left, ya0);
            for (int i = 1; i <= N; i++) {
                float x = left + st * i;
                float y = surfaceAt(x, yc, i, amb * (float) Math.sin(x * 0.42f + time * 2.3f + 1f));
                float mx = x - st / 2f;
                float my = (y + surfaceAt(mx, yc, i - 1, 0)) * 0.5f;
                water.quadTo(mx, my, x, y);
            }
            water.lineTo(cx + R, cy + R + dp(3));
            water.lineTo(left, cy + R + dp(3));
            water.close();
            canvas.drawPath(water, waterPaint);

            // light just below the surface
            fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 36));
            for (int band = 0; band < 3; band++) {
                float off = dp(1.5f + band * 2.4f);
                linePaint.setStrokeWidth(dp(2.4f));
                linePaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 30 - band * 8));
                for (int i = 0; i < N; i += 2) {
                    float x1 = left + st * i, x2 = x1 + st * 2;
                    canvas.drawLine(x1, surfaceAt(x1, yc, i, 0) + off, x2, surfaceAt(x2, yc, Math.min(N, i + 2), 0) + off, linePaint);
                }
            }

            // bright surface line and foam on fast waves
            linePaint.setStrokeWidth(dp(1.3f));
            linePaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 170));
            for (int i = 0; i < N; i++) {
                float x1 = left + st * i, x2 = x1 + st;
                canvas.drawLine(x1, surfaceAt(x1, yc, i, 0), x2, surfaceAt(x2, yc, i + 1, 0), linePaint);
            }
            for (int i = 0; i <= N; i += 2) {
                float sp = Math.abs(v[i]);
                if (sp > dp(70)) {
                    float x = left + st * i;
                    fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) Math.min(200, sp / dp(1.2f))));
                    canvas.drawCircle(x, surfaceAt(x, yc, i, 0) - dp(0.4f), dp(0.9f), fillPaint);
                }
            }

            // bubbles with highlight
            float depth = Math.max(0f, cy + R - yc);
            for (int i = 0; i < BUBBLES; i++) {
                float x = cx + bx[i] * R * 0.55f + (float) Math.sin(time * 3f + i * 2f) * dp(1.2f);
                float y = cy + R - by[i] * depth;
                float r = dp(0.8f + 0.55f * bs[i]);
                fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 90));
                canvas.drawCircle(x, y, r, fillPaint);
                fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 190));
                canvas.drawCircle(x - r * 0.3f, y - r * 0.3f, r * 0.3f, fillPaint);
            }

            // caustic on the bottom
            if (level > 0.1f) {
                float cxp = cx + (float) Math.sin(time * 0.9f) * R * 0.3f;
                fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 46));
                rect.set(cxp - R * 0.45f, cy + R * 0.72f, cxp + R * 0.45f, cy + R * 0.9f);
                canvas.drawOval(rect, fillPaint);
            }

            // glint travelling along the surface after an impact
            if (glint > 0) {
                float gx = cx + (0.5f - glint) * R * 1.4f;
                fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) (120 * glint)));
                float gy = surfaceAt(gx, yc, Math.max(0, Math.min(N, Math.round((gx - left) / st))), 0);
                rect.set(gx - dp(10), gy - dp(1.3f), gx + dp(10), gy + dp(1.3f));
                canvas.drawOval(rect, fillPaint);
            }
            canvas.restore();
        }

        // 3. glass: depth shading, rim, specular
        canvas.drawCircle(cx, cy, R, depthPaint);
        glassPaint.setStrokeWidth(dp(1.6f));
        glassPaint.setColor(ColorUtils.setAlphaComponent(Color.BLACK, 70));
        canvas.drawCircle(cx, cy, R - dp(0.2f), glassPaint);
        glassPaint.setStrokeWidth(dp(1.1f));
        glassPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, armed ? 235 : 105));
        canvas.drawCircle(cx, cy, R - dp(1.1f), glassPaint);
        glassPaint.setStrokeWidth(dp(2.2f));
        glassPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 150));
        rect.set(cx - R + dp(4), cy - R + dp(4), cx + R - dp(4), cy + R - dp(4));
        canvas.drawArc(rect, 198, 52, false, glassPaint);
        glassPaint.setStrokeWidth(dp(1.4f));
        glassPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 70));
        canvas.drawArc(rect, 20, 34, false, glassPaint);
        fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 170));
        canvas.drawCircle(cx - R * 0.5f, cy - R * 0.62f, dp(1.5f), fillPaint);

        // 4. the pulled column
        if (column) {
            drawColumn(canvas, yc);
        }

        // 5. impact ring + completion ring
        if (ringT < 1f) {
            ringT = Math.min(1f, ringT + 0.016f * 1.1f);
            glassPaint.setStrokeWidth(dp(2f) * (1f - ringT) + dp(0.6f));
            glassPaint.setColor(ColorUtils.setAlphaComponent(lightColor, (int) (230 * (1f - ringT))));
            canvas.drawCircle(cx, cy, R + dp(2) + dp(20) * ringT, glassPaint);
            alive = true;
        }

        // 6. droplets as small teardrops stretched along their velocity
        for (int i = 0; i < pCount; i++) {
            float a = ptype[i] == 1 ? Math.max(0f, Math.min(1f, plife[i] * 1.6f)) : 1f;
            float sp = (float) Math.sqrt(pvx[i] * pvx[i] + pvy[i] * pvy[i]);
            float stretch = 1f + Math.min(1.1f, sp / dp(1600));
            float ang = (float) Math.toDegrees(Math.atan2(pvy[i], pvx[i])) - 90f;
            canvas.save();
            canvas.translate(px[i], py[i]);
            canvas.rotate(ang);
            fillPaint.setColor(ColorUtils.setAlphaComponent(lightColor, (int) (232 * a)));
            rect.set(-pr[i], -pr[i] * stretch, pr[i], pr[i] * 0.9f);
            canvas.drawOval(rect, fillPaint);
            canvas.restore();
            fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) (170 * a)));
            canvas.drawCircle(px[i] - pr[i] * 0.3f, py[i] - pr[i] * 0.3f, pr[i] * 0.33f, fillPaint);
        }

        if (alive || level > 0.02f || sensorOn && Math.abs(slopeTarget - slope) > 0.0005f) {
            if (column || pCount > 0 || ringT < 1f) {
                postInvalidateOnAnimation();
            } else {
                int pad = dp(8);
                postInvalidateOnAnimation((int) (cx - R) - pad, (int) (cy - R) - pad - dp(40), (int) (cx + R) + pad, (int) (cy + R) + pad);
            }
        }
    }

    private static void rgb(float[] out, int off, int color) {
        out[off] = Color.red(color) / 255f;
        out[off + 1] = Color.green(color) / 255f;
        out[off + 2] = Color.blue(color) / 255f;
    }

    /** Snapshot of the state for the GL renderer. */
    private void pushGL(float yc, boolean column) {
        float left = cx - R;
        float st = 2f * R / N;
        float amb = dp(0.7f);
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        synchronized (gl.lock) {
            for (int i = 0; i <= N; i++) {
                float x = left + st * i;
                gl.ys[i] = surfaceAt(x, yc, i, amb * (float) Math.sin(x * 0.42f + time * 2.3f + 1f));
                int ph = (i + N / 3) % (N + 1);
                gl.yb[i] = surfaceAt(x, yc, ph, amb * (float) Math.sin(x * 0.33f + time * 1.7f)) + dp(1.5f) - h[ph] * 0.35f;
            }
            int cnt = 0;
            if (column) {
                layoutColumn(yc);
                for (int i = 0; i < M - 1; i++) {
                    gl.cc[i * 3] = cx + offX[i];
                    gl.cc[i * 3 + 1] = colY[i];
                    gl.cc[i * 3 + 2] = colW[i];
                    x0 = Math.min(x0, cx + offX[i] - colW[i]);
                    x1 = Math.max(x1, cx + offX[i] + colW[i]);
                    y0 = Math.min(y0, colY[i] - colW[i]);
                    y1 = Math.max(y1, colY[i] + colW[i]);
                }
                gl.cc[(M - 1) * 3] = colBX;
                gl.cc[(M - 1) * 3 + 1] = colBY;
                gl.cc[(M - 1) * 3 + 2] = colRB;
                x0 = Math.min(x0, colBX - colRB);
                x1 = Math.max(x1, colBX + colRB);
                y0 = Math.min(y0, colBY - colRB);
                y1 = Math.max(y1, colBY + colRB);
                cnt = M;
            }
            gl.cn = cnt;
            gl.pn = pCount;
            for (int i = 0; i < pCount; i++) {
                float a = ptype[i] == 1 ? Math.max(0.15f, Math.min(1f, plife[i] * 1.6f)) : 1f;
                gl.pp[i * 3] = px[i];
                gl.pp[i * 3 + 1] = py[i];
                gl.pp[i * 3 + 2] = pr[i] * a;
                x0 = Math.min(x0, px[i] - pr[i] * 3f);
                x1 = Math.max(x1, px[i] + pr[i] * 3f);
                y0 = Math.min(y0, py[i] - pr[i] * 3f);
                y1 = Math.max(y1, py[i] + pr[i] * 3f);
            }
            float depth = Math.max(0f, cy + R - yc);
            for (int i = 0; i < BUBBLES; i++) {
                boolean on = level > 0.12f;
                gl.bb[i * 3] = cx + bx[i] * R * 0.55f + (float) Math.sin(time * 3f + i * 2f) * dp(1.2f);
                gl.bb[i * 3 + 1] = cy + R - by[i] * depth;
                gl.bb[i * 3 + 2] = on ? dp(0.8f + 0.55f * bs[i]) : 0f;
            }
            float[] m = gl.misc;
            m[TideGLView.M_GEO] = cx;
            m[TideGLView.M_GEO + 1] = cy;
            m[TideGLView.M_GEO + 2] = R;
            m[TideGLView.M_GEO + 3] = yc;
            rgb(m, TideGLView.M_LIGHT, lightColor);
            rgb(m, TideGLView.M_BASE, baseColor);
            rgb(m, TideGLView.M_DEEP, deepColor);
            m[TideGLView.M_FX] = time;
            m[TideGLView.M_FX + 1] = glint;
            m[TideGLView.M_FX + 2] = ringT;
            m[TideGLView.M_FX + 3] = (armed || flying) && column ? 1f : 0f;
            m[TideGLView.M_BLOB] = colBX;
            m[TideGLView.M_BLOB + 1] = colBY;
            m[TideGLView.M_BLOB + 2] = colRB;
            m[TideGLView.M_BLOB + 3] = 0.5f + 0.5f * (float) Math.sin(time * 14f);
            m[TideGLView.M_LEVEL] = (level > 0.004f || drain > 0) ? (level > 0.1f ? 2f : 1f) : 0f;
            if (x0 > x1) {
                x0 = y0 = x1 = y1 = 0;
            }
            m[TideGLView.M_BBOX] = x0 - dp(4);
            m[TideGLView.M_BBOX + 1] = y0 - dp(4);
            m[TideGLView.M_BBOX + 2] = x1 + dp(4);
            m[TideGLView.M_BBOX + 3] = y1 + dp(4);
            m[TideGLView.M_DP] = dp(1);
        }
        gl.commit();
    }

    private float colBX, colBY, colRB;

    /** Computes the column nodes (positions and widths); shared by the Canvas and the GL paths. */
    private void layoutColumn(float yc) {
        float max = dp(PULL_MAX_DP);
        float s = Math.max(0f, Math.min(1f, blobY / max));
        float vol = Math.max(0.12f, level);
        float chord = (float) Math.sqrt(Math.max(0f, R * R - (yc - cy) * (yc - cy)));
        float rA = Math.max(dp(5), chord * 0.82f);
        float rB = dp(5.5f) + dp(9.5f) * (float) Math.sqrt(vol) * (1f - 0.38f * s) * (flying ? 1f - Math.min(0.5f, flyTime * 3f) : 1f);
        float wMid = Math.min(rA, rB) * 0.92f * (1f - 0.86f * s) + dp(1.1f);
        float len = Math.max(dp(2), blobY);

        // width profile (quadratic Bezier through the neck) with travelling ripples
        for (int i = 0; i < M; i++) {
            float t = i / (float) (M - 1);
            float u = 1f - t;
            float w = u * u * rA + 2f * u * t * wMid + t * t * rB;
            w *= 1f + rippleAmp * (float) Math.sin(t * 9f - time * 16f) * (1f - 0.4f * t);
            colW[i] = Math.max(dp(0.8f), w);
            colY[i] = yc - len * t;
        }
        colRB = rB;
        colBX = cx + offX[M - 1];
        colBY = colY[M - 1];
    }

    private void drawColumn(Canvas canvas, float yc) {
        layoutColumn(yc);
        float rB = colRB, bxx = colBX, byy = colBY;

        canvas.save();
        canvas.clipRect(0, 0, getWidth(), yc + dp(0.5f));
        strip.reset();
        strip.moveTo(cx + offX[0] - colW[0], colY[0]);
        for (int i = 1; i < M; i++) {
            float x0 = cx + offX[i - 1] - colW[i - 1], y0 = colY[i - 1];
            float x1 = cx + offX[i] - colW[i], y1 = colY[i];
            strip.quadTo(x0, (y0 + y1) * 0.5f, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f);
        }
        strip.lineTo(cx + offX[M - 1] - colW[M - 1], colY[M - 1]);
        strip.lineTo(cx + offX[M - 1] + colW[M - 1], colY[M - 1]);
        for (int i = M - 1; i >= 1; i--) {
            float x0 = cx + offX[i] + colW[i], y0 = colY[i];
            float x1 = cx + offX[i - 1] + colW[i - 1], y1 = colY[i - 1];
            strip.quadTo(x0, (y0 + y1) * 0.5f, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f);
        }
        strip.lineTo(cx + offX[0] + colW[0], colY[0]);
        strip.close();
        canvas.drawPath(strip, columnPaint);
        canvas.drawCircle(bxx, byy, rB, columnPaint);

        // darker edge for volume, inner glow, rim light along the left side
        linePaint.setStrokeWidth(dp(1f));
        linePaint.setColor(ColorUtils.setAlphaComponent(deepColor, 110));
        canvas.drawPath(strip, linePaint);
        for (int i = 0; i < M - 1; i++) {
            float x1 = cx + offX[i] - colW[i] * 0.55f, y1 = colY[i];
            float x2 = cx + offX[i + 1] - colW[i + 1] * 0.55f, y2 = colY[i + 1];
            linePaint.setStrokeWidth(Math.max(dp(0.8f), colW[i] * 0.22f));
            linePaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 105));
            canvas.drawLine(x1, y1, x2, y2, linePaint);
        }
        fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 40));
        canvas.drawCircle(bxx, byy + rB * 0.15f, rB * 0.6f, fillPaint);
        fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 160));
        rect.set(bxx - rB * 0.64f, byy - rB * 0.66f, bxx - rB * 0.1f, byy - rB * 0.22f);
        canvas.drawOval(rect, fillPaint);
        if (armed || flying) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(time * 14f);
            glassPaint.setStrokeWidth(dp(1.4f));
            glassPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) (150 + 80 * pulse)));
            canvas.drawCircle(bxx, byy, rB + dp(3) + dp(1.5f) * pulse, glassPaint);
        }
        canvas.restore();
    }
}
