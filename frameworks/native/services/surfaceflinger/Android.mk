LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_CLANG := true

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk
LOCAL_SRC_FILES:= \
    Client.cpp \
    DisplayDevice.cpp \
    DispSync.cpp \
    EventControlThread.cpp \
    EventThread.cpp \
    FrameTracker.cpp \
    Layer.cpp \
    LayerDim.cpp \
    MessageQueue.cpp \
    MonitoredProducer.cpp \
    SurfaceFlinger.cpp \
    SurfaceFlingerConsumer.cpp \
    Transform.cpp \
    DisplayHardware/FramebufferSurface.cpp \
    DisplayHardware/HWComposer.cpp \
    DisplayHardware/PowerHAL.cpp \
    DisplayHardware/VirtualDisplaySurface.cpp \
    Effects/Daltonizer.cpp \
    EventLog/EventLogTags.logtags \
    EventLog/EventLog.cpp \
    RenderEngine/Description.cpp \
    RenderEngine/Mesh.cpp \
    RenderEngine/Program.cpp \
    RenderEngine/ProgramCache.cpp \
    RenderEngine/GLExtensions.cpp \
    RenderEngine/RenderEngine.cpp \
    RenderEngine/Texture.cpp \
    RenderEngine/GLES10RenderEngine.cpp \
    RenderEngine/GLES11RenderEngine.cpp \
    RenderEngine/GLES20RenderEngine.cpp


LOCAL_CFLAGS:= -DLOG_TAG=\"SurfaceFlinger\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

ifeq ($(strip $(ENABLE_STEREO_DEFORM)),true)
    LOCAL_CFLAGS += -DENABLE_STEREO_AND_DEFORM
endif

ifeq ($(TARGET_BOARD_PLATFORM),omap4)
	LOCAL_CFLAGS += -DHAS_CONTEXT_PRIORITY
endif
ifeq ($(TARGET_BOARD_PLATFORM),s5pc110)
	LOCAL_CFLAGS += -DHAS_CONTEXT_PRIORITY
endif

ifeq ($(TARGET_DISABLE_TRIPLE_BUFFERING),true)
	LOCAL_CFLAGS += -DTARGET_DISABLE_TRIPLE_BUFFERING
endif

ifeq ($(TARGET_FORCE_HWC_FOR_VIRTUAL_DISPLAYS),true)
    LOCAL_CFLAGS += -DFORCE_HWC_COPY_FOR_VIRTUAL_DISPLAYS
endif

ifneq ($(NUM_FRAMEBUFFER_SURFACE_BUFFERS),)
  LOCAL_CFLAGS += -DNUM_FRAMEBUFFER_SURFACE_BUFFERS=$(NUM_FRAMEBUFFER_SURFACE_BUFFERS)
endif

ifeq ($(TARGET_RUNNING_WITHOUT_SYNC_FRAMEWORK),true)
    LOCAL_CFLAGS += -DRUNNING_WITHOUT_SYNC_FRAMEWORK
else
	ifneq ($(strip $(TARGET_BOARD_PLATFORM)),rk3288)
		ifneq ($(strip $(TARGET_BOARD_PLATFORM)),rk3368)
			LOCAL_CFLAGS += -DUSE_PREPARE_FENCE
		endif
	endif
endif
ifeq ($(strip $(TARGET_BOARD_PLATFORM)),rk3288)
	LOCAL_CFLAGS += -DUSE_LCDC_COMPOSER -DFORCE_SCALE_FULLSCREEN
endif
ifeq ($(strip $(TARGET_BOARD_PLATFORM)),rk3368)
	LOCAL_CFLAGS += -DUSE_LCDC_COMPOSER -DFORCE_SCALE_FULLSCREEN
endif

ifeq ($(strip $(TARGET_BOARD_PLATFORM)),sofia3gr)
    LOCAL_CFLAGS += -DUSE_SOFIA3GR
endif

ifeq ($(strip $(TARGET_BOARD_PLATFORM)),rk30xxb)
    LOCAL_CFLAGS += -DTARGET_BOARD_PLATFORM_RK30XXB
endif

ifeq ($(strip $(TARGET_BOARD_PLATFORM_GPU)),G6110)
	LOCAL_CFLAGS += -DGPU_G6110
endif

# See build/target/board/generic/BoardConfig.mk for a description of this setting.
ifneq ($(VSYNC_EVENT_PHASE_OFFSET_NS),)
    LOCAL_CFLAGS += -DVSYNC_EVENT_PHASE_OFFSET_NS=$(VSYNC_EVENT_PHASE_OFFSET_NS)
else
    LOCAL_CFLAGS += -DVSYNC_EVENT_PHASE_OFFSET_NS=0
endif

# See build/target/board/generic/BoardConfig.mk for a description of this setting.
ifneq ($(SF_VSYNC_EVENT_PHASE_OFFSET_NS),)
    LOCAL_CFLAGS += -DSF_VSYNC_EVENT_PHASE_OFFSET_NS=$(SF_VSYNC_EVENT_PHASE_OFFSET_NS)
else
    LOCAL_CFLAGS += -DSF_VSYNC_EVENT_PHASE_OFFSET_NS=0
endif

ifneq ($(PRESENT_TIME_OFFSET_FROM_VSYNC_NS),)
    LOCAL_CFLAGS += -DPRESENT_TIME_OFFSET_FROM_VSYNC_NS=$(PRESENT_TIME_OFFSET_FROM_VSYNC_NS)
else
    LOCAL_CFLAGS += -DPRESENT_TIME_OFFSET_FROM_VSYNC_NS=0
endif

ifeq ($(strip $(BOARD_USE_LCDC_COMPOSER)),true)
    LOCAL_CFLAGS += -DUSE_LCDC_COMPOSER
    ifeq ($(strip $(BOARD_LCDC_COMPOSER_LANDSCAPE_ONLY)),false)
        LOCAL_CFLAGS += -DLCDC_COMPOSER_FULL_ANGLE
    endif
endif
ifeq ($(strip $(BOARD_ENABLE_WFD_SKIP_FRAME)),true)
    LOCAL_CFLAGS += -DENABLE_WFD_SKIP_FRAME
endif

ifneq ($(MAX_VIRTUAL_DISPLAY_DIMENSION),)
    LOCAL_CFLAGS += -DMAX_VIRTUAL_DISPLAY_DIMENSION=$(MAX_VIRTUAL_DISPLAY_DIMENSION)
else
    LOCAL_CFLAGS += -DMAX_VIRTUAL_DISPLAY_DIMENSION=0
endif

LOCAL_CFLAGS += -fvisibility=hidden -Werror=format
LOCAL_CFLAGS += -std=c++11

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	liblog \
	libdl \
	libhardware \
	libutils \
	libEGL \
	libGLESv1_CM \
	libGLESv2 \
	libbinder \
	libui \
	libgui \
	libpowermanager

LOCAL_MODULE:= libsurfaceflinger

include $(BUILD_SHARED_LIBRARY)

###############################################################
# build surfaceflinger's executable
include $(CLEAR_VARS)

LOCAL_LDFLAGS := -Wl,--version-script,art/sigchainlib/version-script.txt -Wl,--export-dynamic
LOCAL_CFLAGS:= -DLOG_TAG=\"SurfaceFlinger\"
LOCAL_CPPFLAGS:= -std=c++11

LOCAL_SRC_FILES:= \
	main_surfaceflinger.cpp

LOCAL_SHARED_LIBRARIES := \
	libsurfaceflinger \
	libcutils \
	liblog \
	libbinder \
	libutils \
	libdl

LOCAL_WHOLE_STATIC_LIBRARIES := libsigchain

LOCAL_MODULE:= surfaceflinger

ifdef TARGET_32_BIT_SURFACEFLINGER
LOCAL_32_BIT_ONLY := true
endif

include $(BUILD_EXECUTABLE)

###############################################################
# uses jni which may not be available in PDK
ifneq ($(wildcard libnativehelper/include),)
include $(CLEAR_VARS)
LOCAL_CFLAGS:= -DLOG_TAG=\"SurfaceFlinger\"

LOCAL_SRC_FILES:= \
    DdmConnection.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	liblog \
	libdl

LOCAL_MODULE:= libsurfaceflinger_ddmconnection

include $(BUILD_SHARED_LIBRARY)
endif # libnativehelper
