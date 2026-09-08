LOCAL_PATH := $(call my-dir)
PSIPHON_JNI_PATH := $(LOCAL_PATH)

BADVPN_PATH := $(LOCAL_PATH)/badvpn
include $(BADVPN_PATH)/Android.mk

include $(CLEAR_VARS)
LOCAL_PATH := $(PSIPHON_JNI_PATH)
LOCAL_MODULE := crashreporter
LOCAL_SRC_FILES := crashreporter.c
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
