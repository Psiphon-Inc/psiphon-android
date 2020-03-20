package com.psiphon3.psicash;

public interface CountDownListener {
    void onCountDownTick(long interval, long l);
    void onCountDownFinish();
}
