LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_CFLAGS := -Oz -fvisibility=hidden -ffunction-sections -fdata-sections -fno-stack-protector
LOCAL_CPPFLAGS := -Oz -fvisibility=hidden -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti -fno-stack-protector
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,--exclude-libs,ALL
LOCAL_LDLIBS := -llog
LOCAL_MODULE := local-socket
LOCAL_SRC_FILES := local-socket.cpp
include $(BUILD_SHARED_LIBRARY)
