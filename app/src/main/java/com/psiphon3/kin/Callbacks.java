package com.psiphon3.kin;

public interface Callbacks<T> {
    void onSuccess(T value);

    void onFailure(Exception e);
}
