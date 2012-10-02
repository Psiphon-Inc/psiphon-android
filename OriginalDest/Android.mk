LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := OriginalDest

LOCAL_SRC_FILES := OriginalDest.c

include $(BUILD_SHARED_LIBRARY)
