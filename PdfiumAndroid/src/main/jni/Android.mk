LOCAL_PATH := $(call my-dir)
PDFIUM_PREBUILT_DIR ?= $(LOCAL_PATH)/../libs

#Prebuilt libraries
include $(CLEAR_VARS)
LOCAL_MODULE := aospPdfium

ARCH_PATH = $(TARGET_ARCH_ABI)

LOCAL_SRC_FILES := $(PDFIUM_PREBUILT_DIR)/$(ARCH_PATH)/libmodpdfium.so

include $(PREBUILT_SHARED_LIBRARY)

#libmodft2
include $(CLEAR_VARS)
LOCAL_MODULE := libmodft2

LOCAL_SRC_FILES := $(PDFIUM_PREBUILT_DIR)/$(ARCH_PATH)/libmodft2.so

include $(PREBUILT_SHARED_LIBRARY)

#libmodpng
include $(CLEAR_VARS)
LOCAL_MODULE := libmodpng

LOCAL_SRC_FILES := $(PDFIUM_PREBUILT_DIR)/$(ARCH_PATH)/libmodpng.so

include $(PREBUILT_SHARED_LIBRARY)

#Main JNI library
include $(CLEAR_VARS)
LOCAL_MODULE := jniPdfium

LOCAL_CFLAGS += -DHAVE_PTHREADS
LOCAL_C_INCLUDES += $(LOCAL_PATH)/include
LOCAL_SHARED_LIBRARIES += aospPdfium
LOCAL_LDLIBS += -llog -landroid -ljnigraphics -ldl -lz

LOCAL_SRC_FILES := src/mainJNILib.cpp src/sourceSync.cpp synctex/synctex_parser.c synctex/synctex_parser_utils.c

include $(BUILD_SHARED_LIBRARY)
