package org.telegram.ui.Components.chat.buttons;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * "Tide": the unread counter of the page-down button as a glass of water.
 * Water level follows the unread count, the surface sloshes with the phone tilt (accelerometer),
 * new messages drop in as droplets, and dragging the button up pulls the water into a stretchy
 * metaball column that is poured out (scrolling down) when released.
 *
 * The view is larger than the button and draws outside of it, so its parents must not clip children.
 * It never handles touches itself; {@link ChatActivityBlurredRoundPageDownButton} drives the pull.
 * No allocations in draw.
 */
@SuppressLint("ViewConstructor")
public class TideView extends View implements SensorEventListener {
    public static final int VIEW_W_DP = 140;
    public static final int VIEW_H_DP = 56 + 250;
    public static final int RADIUS_DP = 26;
    public static final int PULL_MAX_DP = 205;
    public static final int PULL_ARM_DP = 92;

    private static final int N = 30;
    private static final int MAX_P = 44;
    private static final int BUBBLES = 5;
    private static final float STEP = 1f / 120f;

    private final Theme.ResourcesProvider resourcesProvider;

    // geometry
    private float cx, cy, R;
    private final Path circle = new Path();
    private final Path water = new Path();
    private final Path hull = new Path();
    private final RectF rectA = new RectF();
    private final RectF rectB = new RectF();

    // paints
    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hullPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int baseColor;
    private int lightColor;

    // water state
    private float level, levelVel, levelTarget;
    private float slope, slopeVel, slopeTarget;
    private final float[] h = new float[N + 1];
    private final float[] v = new float[N + 1];
    private float drain; // 0..1, water temporarily taken by the poured column
    private float glint;

    // pull state
    private boolean pulling;
    private float pullTarget; // px, finger driven
    private float blobY, blobVel; // px above water center
    private boolean armed;
    private boolean flying;
    private float flyTime;
    private float sinceRelease = 10f;

    // particles: 0 = falling into the glass, 1 = flying out
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

    // entry animation: opening a chat with unread messages fills the empty flask, one drop per message
    public interface EntryListener {
        void onProgress(int landed, int total);
    }
    private EntryListener entryListener;
    private boolean entryEnabled = true;
    private boolean entryPlayed;
    private boolean entryArmed, entryActive;
    private int entryTotal, entrySpawned, entryLanded;
    private float entryT;
    private int lastCount;
    private long lastTickTime;
    private float ringT = 1f;

    private long lastTime;
    private float acc;
    private int rng = 0x2545F491;
    private boolean everDrawn;

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
        lightColor = ColorUtils.blendARGB(baseColor, Color.WHITE, 0.45f);
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

    /** Called while the finger drags the button up; distance in px (can be raw, we rubber-band it). */
    public void setPull(float rawDy) {
        float max = dp(PULL_MAX_DP);
        pulling = true;
        pullTarget = max * (1f - (float) Math.exp(-Math.max(0, rawDy) / dp(150)));
        boolean nowArmed = pullTarget >= dp(PULL_ARM_DP);
        if (nowArmed != armed) {
            armed = nowArmed;
            if (armed) {
                glint = 1f;
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        invalidate();
    }

    /** @return true if the column was poured out (caller should trigger the scroll). */
    public boolean release(boolean cancel) {
        pulling = false;
        boolean pour = armed && !cancel;
        armed = false;
        sinceRelease = 0;
        if (pour) {
            flying = true;
            flyTime = 0;
            blobVel = dp(1100);
            glint = 1f;
            try {
                performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                    ? android.view.HapticFeedbackConstants.CONFIRM
                    : android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            } catch (Throwable ignore) {}
        } else {
            pullTarget = 0;
            // snap back: kick the surface
            for (int i = 0; i < N; i++) {
                v[i] += dp(90) * (1f - Math.abs(2f * i / N - 1f));
            }
        }
        invalidate();
        return pour;
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
        cy = h2 - dp(28);
        circle.reset();
        circle.addCircle(cx, cy, R, Path.Direction.CW);
        rebuildShaders();
    }

    private void rebuildShaders() {
        if (R <= 0) {
            return;
        }
        int top = ColorUtils.setAlphaComponent(lightColor, 205);
        int bottom = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(baseColor, Color.BLACK, 0.18f), 236);
        waterPaint.setShader(new LinearGradient(0, cy - R, 0, cy + R, top, bottom, Shader.TileMode.CLAMP));
        hullPaint.setShader(new LinearGradient(0, cy - dp(PULL_MAX_DP), 0, cy + R, top, bottom, Shader.TileMode.CLAMP));
    }

    // ---------------------------------------------------------------- simulation

    private float rnd() {
        rng ^= rng << 13;
        rng ^= rng >>> 17;
        rng ^= rng << 5;
        return (rng & 0xFFFFFF) / (float) 0x1000000;
    }

    private float surfaceCenterY() {
        float eff = level * (1f - 0.5f * drain) - 0.25f * (pullTarget > 0 ? Math.min(1f, blobY / dp(PULL_MAX_DP)) : 0f) * level;
        eff = Math.max(0f, eff);
        return cy + R - eff * 2f * R;
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

    private void burst(float x, float y, int count, float speed) {
        for (int k = 0; k < count && pCount < MAX_P; k++) {
            int i = pCount++;
            float a = (float) (-Math.PI / 2 + (rnd() - 0.5f) * 2.4f);
            float s = speed * (0.35f + rnd() * 0.75f);
            px[i] = x + (rnd() - 0.5f) * dp(6);
            py[i] = y;
            pvx[i] = (float) Math.cos(a) * s;
            pvy[i] = (float) Math.sin(a) * s;
            pr[i] = dp(1.3f) + rnd() * dp(2.8f);
            plife[i] = 1f;
            ptype[i] = 1;
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

    private boolean step(float dt) {
        boolean alive = false;

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
        float acc2 = (slopeVel - prevSlopeVel) / Math.max(dt, 1e-4f);
        if (Math.abs(slopeTarget - slope) > 0.003f || Math.abs(slopeVel) > 0.02f) {
            alive = true;
        }

        // surface waves (damped 1D wave equation) driven by the sloshing
        float c2 = 5200f, damp = 3.2f, rest = 36f;
        float amp = level > 0.01f ? 1f : 0f;
        for (int i = 0; i <= N; i++) {
            float l = h[i > 0 ? i - 1 : i];
            float r = h[i < N ? i + 1 : i];
            float a = c2 * (l + r - 2f * h[i]) - rest * h[i] - damp * v[i];
            float xn = (2f * i / N - 1f);
            a += -acc2 * xn * dp(2.4f) * amp;
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

        // pull blob
        if (flying) {
            flyTime += dt;
            blobY += blobVel * dt;
            blobVel -= dp(500) * dt;
            if (flyTime > 0.17f) {
                flying = false;
                float yTop = surfaceCenterY() - blobY;
                burst(cx, yTop, 22, dp(820));
                burst(cx, yTop, 10, dp(420));
                pullTarget = 0;
                blobY = 0;
                blobVel = 0;
                for (int i = 0; i <= N; i++) {
                    v[i] += (rnd() - 0.5f) * dp(160);
                }
            }
            alive = true;
        } else {
            float target = pulling || pullTarget > 0 ? pullTarget : 0f;
            float k = pulling ? 260f : 150f;
            float d = pulling ? 26f : 11f;
            blobVel += (k * (target - blobY) - d * blobVel) * dt;
            blobY += blobVel * dt;
            if (!pulling && pullTarget == 0f && Math.abs(blobY) < dp(0.6f) && Math.abs(blobVel) < dp(8)) {
                blobY = 0;
                blobVel = 0;
            }
            if (blobY < 0) {
                blobY = 0;
                if (blobVel < 0) {
                    // blob re-enters the glass: splash
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
        sinceRelease += dt;

        // particles
        float g = dp(1900);
        for (int i = pCount - 1; i >= 0; i--) {
            pvy[i] += g * dt;
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            if (ptype[i] != 1) {
                float sy = surfaceCenterY() + slope * (px[i] - cx);
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
                    burst2(px[i], sy);
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
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        }
                        if (entryLanded >= entryTotal) {
                            finishEntry();
                        }
                    }
                    continue;
                }
            } else {
                plife[i] -= dt * 1.15f;
                if (plife[i] <= 0 || py[i] > cy + dp(80)) {
                    removeParticle(i);
                    continue;
                }
            }
            alive = true;
        }
        if (glint > 0) {
            glint = Math.max(0f, glint - dt * 2.2f);
            alive = true;
        }
        // bubbles
        if (level > 0.12f) {
            for (int i = 0; i < BUBBLES; i++) {
                by[i] -= dt * 0.22f * bs[i];
                if (by[i] < 0) {
                    by[i] = 1f;
                    bx[i] = rnd() * 2f - 1f;
                }
            }
            alive = true;
        }
        return alive;
    }

    private void finishEntry() {
        entryActive = false;
        levelTarget = levelFor(Math.max(entryTotal, lastCount));
        if (entryListener != null) {
            entryListener.onProgress(Math.max(entryTotal, lastCount), entryTotal);
        }
        // "full flask" flourish: surge across the surface, ring around the glass, sparkles
        for (int k = 0; k <= N; k++) {
            v[k] += dp(300) * (1f - Math.abs(2f * k / N - 1f) * 0.5f) * (k % 2 == 0 ? 1f : -0.6f);
        }
        glint = 1f;
        ringT = 0f;
        burst(cx, cy - R, 14, dp(520));
        try {
            performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                ? android.view.HapticFeedbackConstants.CONFIRM
                : android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Throwable ignore) {}
    }

    private void burst2(float x, float y) {
        for (int k = 0; k < 3 && pCount < MAX_P; k++) {
            int i = pCount++;
            px[i] = x;
            py[i] = y - dp(1);
            pvx[i] = (rnd() - 0.5f) * dp(150);
            pvy[i] = -dp(260) - rnd() * dp(220);
            pr[i] = dp(0.9f) + rnd() * dp(1.2f);
            plife[i] = 0.6f;
            ptype[i] = 1;
        }
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
        if (!everDrawn) {
            everDrawn = true;
            alive = true;
        }

        float yc = surfaceCenterY();

        // 1. glass inner tint
        fillPaint.setColor(ColorUtils.setAlphaComponent(Color.BLACK, 38));
        canvas.drawCircle(cx, cy, R, fillPaint);

        // 2. water body clipped to the glass
        if (level > 0.004f || drain > 0) {
            canvas.save();
            canvas.clipPath(circle);
            water.reset();
            float left = cx - R;
            float step = 2f * R / N;
            float y0 = yc + slope * (left - cx) + h[0];
            water.moveTo(left, y0);
            for (int i = 1; i <= N; i++) {
                float x = left + step * i;
                float y = yc + slope * (x - cx) + h[i];
                float mx = x - step / 2f;
                float my = yc + slope * (mx - cx) + (h[i] + h[i - 1]) / 2f;
                water.quadTo(mx, my, x, y);
            }
            water.lineTo(cx + R, cy + R + dp(3));
            water.lineTo(left, cy + R + dp(3));
            water.close();
            canvas.drawPath(water, waterPaint);

            // surface highlight
            linePaint.setStrokeWidth(dp(1.4f));
            linePaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 150));
            for (int i = 0; i < N; i++) {
                float x1 = left + step * i, x2 = x1 + step;
                float y1 = yc + slope * (x1 - cx) + h[i];
                float y2 = yc + slope * (x2 - cx) + h[i + 1];
                canvas.drawLine(x1, y1, x2, y2, linePaint);
            }

            // bubbles
            fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 120));
            float depth = Math.max(0f, cy + R - yc);
            for (int i = 0; i < BUBBLES; i++) {
                float x = cx + bx[i] * R * 0.55f;
                float y = cy + R - by[i] * depth;
                canvas.drawCircle(x, y, dp(0.8f + 0.5f * bs[i]), fillPaint);
            }

            // caustic glint travelling along the surface after a drop
            if (glint > 0) {
                float gx = cx + (0.5f - glint) * R * 1.4f;
                fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) (110 * glint)));
                canvas.drawOval(gx - dp(9), yc - dp(1.2f) + slope * (gx - cx), gx + dp(9), yc + dp(1.2f) + slope * (gx - cx), fillPaint);
            }
            canvas.restore();
        }

        // 3. glass rim + specular
        glassPaint.setStrokeWidth(dp(1.2f));
        glassPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, armed ? 210 : 95));
        canvas.drawCircle(cx, cy, R - dp(0.6f), glassPaint);
        glassPaint.setStrokeWidth(dp(2f));
        glassPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 120));
        rectA.set(cx - R + dp(4), cy - R + dp(4), cx + R - dp(4), cy + R - dp(4));
        canvas.drawArc(rectA, 200, 50, false, glassPaint);

        // 4. pulled column
        if (blobY > dp(1.2f) || flying) {
            drawColumn(canvas, yc);
        }

        if (ringT < 1f) {
            ringT = Math.min(1f, ringT + 0.016f * 1.1f);
            glassPaint.setStrokeWidth(dp(2f) * (1f - ringT) + dp(0.6f));
            glassPaint.setColor(ColorUtils.setAlphaComponent(lightColor, (int) (230 * (1f - ringT))));
            canvas.drawCircle(cx, cy, R + dp(2) + dp(20) * ringT, glassPaint);
            postInvalidateOnAnimation();
        }

        // 5. droplets
        for (int i = 0; i < pCount; i++) {
            float a = ptype[i] == 1 ? Math.max(0f, Math.min(1f, plife[i] * 1.6f)) : 1f;
            fillPaint.setColor(ColorUtils.setAlphaComponent(lightColor, (int) (230 * a)));
            canvas.drawCircle(px[i], py[i], pr[i], fillPaint);
            fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, (int) (150 * a)));
            canvas.drawCircle(px[i] - pr[i] * 0.3f, py[i] - pr[i] * 0.3f, pr[i] * 0.35f, fillPaint);
        }

        if (alive || sensorOn && Math.abs(slopeTarget - slope) > 0.0005f) {
            postInvalidateOnAnimation();
        }
    }

    private void drawColumn(Canvas canvas, float yc) {
        float max = dp(PULL_MAX_DP);
        float s = Math.max(0f, Math.min(1f, blobY / max));
        float vol = Math.max(0.12f, level);
        float chord = (float) Math.sqrt(Math.max(0f, R * R - (yc - cy) * (yc - cy)));
        float rA = Math.max(dp(5), chord * 0.82f);
        float yA = yc;
        float rB = dp(5.5f) + dp(9.5f) * (float) Math.sqrt(vol) * (1f - 0.38f * s) * (flying ? 1f - Math.min(0.5f, flyTime * 3f) : 1f);
        float yB = yA - blobY;
        float d = yA - yB;
        if (d < 1f) {
            return;
        }
        float wMid = Math.min(rA, rB) * 0.92f * (1f - 0.86f * s) + dp(1.1f);
        float ym = (yA + yB) / 2f;
        hull.reset();
        hull.moveTo(cx - rA, yA);
        hull.cubicTo(cx - rA, yA - d * 0.38f, cx - wMid, ym + d * 0.16f, cx - wMid, ym);
        hull.cubicTo(cx - wMid, ym - d * 0.16f, cx - rB, yB + d * 0.38f, cx - rB, yB);
        rectB.set(cx - rB, yB - rB, cx + rB, yB + rB);
        hull.arcTo(rectB, 180, 180);
        hull.cubicTo(cx + rB, yB + d * 0.38f, cx + wMid, ym - d * 0.16f, cx + wMid, ym);
        hull.cubicTo(cx + wMid, ym + d * 0.16f, cx + rA, yA - d * 0.38f, cx + rA, yA);
        hull.close();
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), yA + dp(0.5f));
        canvas.drawPath(hull, hullPaint);
        // body highlight along the left edge of the column
        linePaint.setStrokeWidth(dp(1.2f));
        linePaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 110));
        canvas.drawLine(cx - wMid * 0.55f, ym + d * 0.28f, cx - wMid * 0.55f, ym - d * 0.28f, linePaint);
        // blob specular
        fillPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 150));
        canvas.drawOval(cx - rB * 0.62f, yB - rB * 0.62f, cx - rB * 0.12f, yB - rB * 0.2f, fillPaint);
        if (armed || flying) {
            glassPaint.setStrokeWidth(dp(1.4f));
            glassPaint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, 190));
            canvas.drawCircle(cx, yB, rB + dp(3), glassPaint);
        }
        canvas.restore();
    }
}
