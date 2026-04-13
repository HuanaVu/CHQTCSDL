package org.jdesktop.animation.timing;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.Timer;

public class Animator {

    private final int durationMs;
    private final TimingTarget target;
    private Timer timer;
    private int resolutionMs = 16;
    private float acceleration;
    private float deceleration;
    private long startTime;

    public Animator(int duration, TimingTarget target) {
        this.durationMs = Math.max(duration, 1);
        this.target = target;
    }

    public void setResolution(int resolution) {
        if (resolution <= 0) {
            this.resolutionMs = 16;
            return;
        }
        this.resolutionMs = resolution;
    }

    public void setAcceleration(float acceleration) {
        this.acceleration = clamp01(acceleration);
    }

    public void setDeceleration(float deceleration) {
        this.deceleration = clamp01(deceleration);
    }

    public boolean isRunning() {
        return timer != null && timer.isRunning();
    }

    public void start() {
        stop();
        target.begin();
        startTime = System.currentTimeMillis();

        timer = new Timer(resolutionMs, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                long elapsed = System.currentTimeMillis() - startTime;
                float rawFraction = Math.min(1f, elapsed / (float) durationMs);
                float fraction = ease(rawFraction);
                target.timingEvent(fraction);
                if (rawFraction >= 1f) {
                    stop();
                    target.end();
                }
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    public void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    private float ease(float t) {
        if (acceleration <= 0f && deceleration <= 0f) {
            return t;
        }

        float a = acceleration;
        float d = deceleration;
        float mid = 1f - a - d;
        if (mid < 0f) {
            float sum = a + d;
            a = a / sum;
            d = d / sum;
            mid = 0f;
        }

        if (t < a && a > 0f) {
            float x = t / a;
            return 0.5f * a * x * x;
        }

        if (t <= a + mid || mid == 0f) {
            float start = 0.5f * a;
            float x = (t - a) / Math.max(mid, 1e-6f);
            return start + x * mid;
        }

        float u = (t - a - mid) / d;
        float start = 0.5f * a + mid;
        return start + (d * (1f - (1f - u) * (1f - u)) * 0.5f);
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
