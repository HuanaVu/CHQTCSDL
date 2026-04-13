package org.jdesktop.animation.timing;

public interface TimingTarget {

    default void begin() {
    }

    void timingEvent(float fraction);

    default void end() {
    }
}
