LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := polipo

LOCAL_CFLAGS := -DHAS_STDINT_H -DNO_DISK_CACHE -DNO_SYSLOG -DANDROID

POLIPO_SRC := util.c event.c io.c chunk.c atom.c object.c log.c diskcache.c main.c \
              config.c local.c http.c client.c server.c auth.c tunnel.c \
              http_parse.c parse_time.c dns.c forbidden.c \
              md5.c fts_compat.c socks.c mingw.c split.c

LOCAL_SRC_FILES := $(POLIPO_SRC) psiphon-jni.c

include $(BUILD_SHARED_LIBRARY)
