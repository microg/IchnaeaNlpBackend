LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
LOCAL_PACKAGE_NAME := IchnaeaNlpBackend
LOCAL_CERTIFICATE := platform
LOCAL_SRC_FILES := $(call all-java-files-under, java)
LOCAL_STATIC_JAVA_LIBRARIES := \
	UnifiedNlpApi

include $(BUILD_PACKAGE)
