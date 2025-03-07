/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "Surface"
#define ATRACE_TAG ATRACE_TAG_GRAPHICS
//#define LOG_NDEBUG 0

#include <android/native_window.h>

#include <binder/Parcel.h>

#include <utils/Log.h>
#include <utils/Trace.h>
#include <utils/NativeHandle.h>

#include <ui/Fence.h>

#include <gui/IProducerListener.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/GLConsumer.h>
#include <gui/Surface.h>

#include <private/gui/ComposerService.h>

#include <hardware/rga.h>
namespace android {

Surface::Surface(
        const sp<IGraphicBufferProducer>& bufferProducer,
        bool controlledByApp)
    : mGraphicBufferProducer(bufferProducer)
{
    // Initialize the ANativeWindow function pointers.
    ANativeWindow::setSwapInterval  = hook_setSwapInterval;
    ANativeWindow::dequeueBuffer    = hook_dequeueBuffer;
    ANativeWindow::cancelBuffer     = hook_cancelBuffer;
    ANativeWindow::queueBuffer      = hook_queueBuffer;
    ANativeWindow::query            = hook_query;
    ANativeWindow::perform          = hook_perform;

    ANativeWindow::dequeueBuffer_DEPRECATED = hook_dequeueBuffer_DEPRECATED;
    ANativeWindow::cancelBuffer_DEPRECATED  = hook_cancelBuffer_DEPRECATED;
    ANativeWindow::lockBuffer_DEPRECATED    = hook_lockBuffer_DEPRECATED;
    ANativeWindow::queueBuffer_DEPRECATED   = hook_queueBuffer_DEPRECATED;

    const_cast<int&>(ANativeWindow::minSwapInterval) = 0;
    const_cast<int&>(ANativeWindow::maxSwapInterval) = 1;

    mReqWidth = 0;
    mReqHeight = 0;
    mReqFormat = 0;
    mReqUsage = 0;
    mTimestamp = NATIVE_WINDOW_TIMESTAMP_AUTO;
    mCrop.clear();
    mDirtyRect.clear();
    mScalingMode = NATIVE_WINDOW_SCALING_MODE_FREEZE;
    mTransform = 0;
    mStickyTransform = 0;
    mDefaultWidth = 0;
    mDefaultHeight = 0;
    mUserWidth = 0;
    mUserHeight = 0;
    mTransformHint = 0;
    mConsumerRunningBehind = false;
    mConnectedToCpu = false;
    mProducerControlledByApp = controlledByApp;
    mSwapIntervalZero = false;
}

Surface::~Surface() {
    if (mConnectedToCpu) {
        Surface::disconnect(NATIVE_WINDOW_API_CPU);
    }
}

sp<IGraphicBufferProducer> Surface::getIGraphicBufferProducer() const {
    return mGraphicBufferProducer;
}

void Surface::setSidebandStream(const sp<NativeHandle>& stream) {
    mGraphicBufferProducer->setSidebandStream(stream);
}

void Surface::allocateBuffers() {
    uint32_t reqWidth = mReqWidth ? mReqWidth : mUserWidth;
    uint32_t reqHeight = mReqHeight ? mReqHeight : mUserHeight;
    mGraphicBufferProducer->allocateBuffers(mSwapIntervalZero, mReqWidth,
            mReqHeight, mReqFormat, mReqUsage);
}

int Surface::hook_setSwapInterval(ANativeWindow* window, int interval) {
    Surface* c = getSelf(window);
    return c->setSwapInterval(interval);
}

int Surface::hook_dequeueBuffer(ANativeWindow* window,
        ANativeWindowBuffer** buffer, int* fenceFd) {
    Surface* c = getSelf(window);
    return c->dequeueBuffer(buffer, fenceFd);
}

int Surface::hook_cancelBuffer(ANativeWindow* window,
        ANativeWindowBuffer* buffer, int fenceFd) {
    Surface* c = getSelf(window);
    return c->cancelBuffer(buffer, fenceFd);
}

int Surface::hook_queueBuffer(ANativeWindow* window,
        ANativeWindowBuffer* buffer, int fenceFd) {
    Surface* c = getSelf(window);
    return c->queueBuffer(buffer, fenceFd);
}

int Surface::hook_dequeueBuffer_DEPRECATED(ANativeWindow* window,
        ANativeWindowBuffer** buffer) {
    Surface* c = getSelf(window);
    ANativeWindowBuffer* buf = NULL;
    int fenceFd = -1;
    int result = c->dequeueBuffer(&buf, &fenceFd);

    if (result != NO_ERROR) return result;

    sp<Fence> fence(new Fence(fenceFd));
    int waitResult = fence->waitForever("dequeueBuffer_DEPRECATED");
    if (waitResult != OK) {
        ALOGE("dequeueBuffer_DEPRECATED: Fence::wait returned an error: %d",
                waitResult);
        c->cancelBuffer(buf, -1);
        return waitResult;
    }
    *buffer = buf;
    return result;
}

int Surface::hook_cancelBuffer_DEPRECATED(ANativeWindow* window,
        ANativeWindowBuffer* buffer) {
    Surface* c = getSelf(window);
    return c->cancelBuffer(buffer, -1);
}

int Surface::hook_lockBuffer_DEPRECATED(ANativeWindow* window,
        ANativeWindowBuffer* buffer) {
    Surface* c = getSelf(window);
    return c->lockBuffer_DEPRECATED(buffer);
}

int Surface::hook_queueBuffer_DEPRECATED(ANativeWindow* window,
        ANativeWindowBuffer* buffer) {
    Surface* c = getSelf(window);
    return c->queueBuffer(buffer, -1);
}

int Surface::hook_query(const ANativeWindow* window,
                                int what, int* value) {
    const Surface* c = getSelf(window);
    return c->query(what, value);
}

int Surface::hook_perform(ANativeWindow* window, int operation, ...) {
    va_list args;
    va_start(args, operation);
    Surface* c = getSelf(window);
    return c->perform(operation, args);
}

status_t Surface::setDirtyRect(const Rect* dirtyRect) {
    Mutex::Autolock lock(mMutex);
    mDirtyRect = *dirtyRect;
    return NO_ERROR;
}

int Surface::setSwapInterval(int interval) {
    ATRACE_CALL();
    // EGL specification states:
    //  interval is silently clamped to minimum and maximum implementation
    //  dependent values before being stored.

    if (interval < minSwapInterval)
        interval = minSwapInterval;

    if (interval > maxSwapInterval)
        interval = maxSwapInterval;

    mSwapIntervalZero = (interval == 0);

    return NO_ERROR;
}

int Surface::dequeueBuffer(android_native_buffer_t** buffer, int* fenceFd) {
    ATRACE_CALL();
    ALOGV("Surface::dequeueBuffer");

    int reqW;
    int reqH;
    bool swapIntervalZero;
    uint32_t reqFormat;
    uint32_t reqUsage;

    {
        Mutex::Autolock lock(mMutex);

        reqW = mReqWidth ? mReqWidth : mUserWidth;
        reqH = mReqHeight ? mReqHeight : mUserHeight;

        swapIntervalZero = mSwapIntervalZero;
        reqFormat = mReqFormat;
        reqUsage = mReqUsage;
    } // Drop the lock so that we can still touch the Surface while blocking in IGBP::dequeueBuffer

    int buf = -1;
    sp<Fence> fence;
    status_t result = mGraphicBufferProducer->dequeueBuffer(&buf, &fence, swapIntervalZero,
            reqW, reqH, reqFormat, reqUsage);

    if (result < 0) {
        ALOGV("dequeueBuffer: IGraphicBufferProducer::dequeueBuffer(%d, %d, %d, %d, %d)"
             "failed: %d", swapIntervalZero, reqW, reqH, reqFormat, reqUsage,
             result);
        return result;
    }

    Mutex::Autolock lock(mMutex);

    sp<GraphicBuffer>& gbuf(mSlots[buf].buffer);

    // this should never happen
    ALOGE_IF(fence == NULL, "Surface::dequeueBuffer: received null Fence! buf=%d", buf);

    if (result & IGraphicBufferProducer::RELEASE_ALL_BUFFERS) {
        freeAllBuffers();
    }

    if ((result & IGraphicBufferProducer::BUFFER_NEEDS_REALLOCATION) || gbuf == 0) {
        result = mGraphicBufferProducer->requestBuffer(buf, &gbuf);
        if (result != NO_ERROR) {
            ALOGE("dequeueBuffer: IGraphicBufferProducer::requestBuffer failed: %d", result);
            mGraphicBufferProducer->cancelBuffer(buf, fence);
            return result;
        }
    }

    if (fence->isValid()) {
        *fenceFd = fence->dup();
        if (*fenceFd == -1) {
            ALOGE("dequeueBuffer: error duping fence: %d", errno);
            // dup() should never fail; something is badly wrong. Soldier on
            // and hope for the best; the worst that should happen is some
            // visible corruption that lasts until the next frame.
        }
    } else {
        *fenceFd = -1;
    }

    *buffer = gbuf.get();
    return OK;
}

int Surface::cancelBuffer(android_native_buffer_t* buffer,
        int fenceFd) {
    ATRACE_CALL();
    ALOGV("Surface::cancelBuffer");
    Mutex::Autolock lock(mMutex);
    int i = getSlotFromBufferLocked(buffer);
    if (i < 0) {
        return i;
    }
    sp<Fence> fence(fenceFd >= 0 ? new Fence(fenceFd) : Fence::NO_FENCE);
    mGraphicBufferProducer->cancelBuffer(i, fence);
    return OK;
}

int Surface::getSlotFromBufferLocked(
        android_native_buffer_t* buffer) const {
    bool dumpedState = false;
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        if (mSlots[i].buffer != NULL &&
                mSlots[i].buffer->handle == buffer->handle) {
            return i;
        }
    }
    ALOGE("getSlotFromBufferLocked: unknown buffer: %p", buffer->handle);
    return BAD_VALUE;
}

int Surface::lockBuffer_DEPRECATED(android_native_buffer_t* buffer __attribute__((unused))) {
    ALOGV("Surface::lockBuffer");
    Mutex::Autolock lock(mMutex);
    return OK;
}

int Surface::queueBuffer(android_native_buffer_t* buffer, int fenceFd) {
    ATRACE_CALL();
    ALOGV("Surface::queueBuffer");
    Mutex::Autolock lock(mMutex);
    int64_t timestamp;
    bool isAutoTimestamp = false;
    if (mTimestamp == NATIVE_WINDOW_TIMESTAMP_AUTO) {
        timestamp = systemTime(SYSTEM_TIME_MONOTONIC);
        isAutoTimestamp = true;
        ALOGV("Surface::queueBuffer making up timestamp: %.2f ms",
            timestamp / 1000000.f);
    } else {
        timestamp = mTimestamp;
    }
    int i = getSlotFromBufferLocked(buffer);
    if (i < 0) {
        return i;
    }


    // Make sure the crop rectangle is entirely inside the buffer.
    Rect crop;
    mCrop.intersect(Rect(buffer->width, buffer->height), &crop);

    Rect dirtyRect = mDirtyRect;
    if(dirtyRect.isEmpty()) {
        int drWidth = mUserWidth ? mUserWidth : mDefaultWidth;
        int drHeight = mUserHeight ? mUserHeight : mDefaultHeight;
        dirtyRect = Rect(drWidth, drHeight);
    }

    sp<Fence> fence(fenceFd >= 0 ? new Fence(fenceFd) : Fence::NO_FENCE);
    IGraphicBufferProducer::QueueBufferOutput output;
    IGraphicBufferProducer::QueueBufferInput input(timestamp, isAutoTimestamp,
            crop, dirtyRect, mScalingMode, mTransform ^ mStickyTransform, mSwapIntervalZero,
            fence, mStickyTransform);
    status_t err = mGraphicBufferProducer->queueBuffer(i, input, &output);
    if (err != OK)  {
        ALOGE("queueBuffer: error queuing buffer to SurfaceTexture, %d", err);
    }
    uint32_t numPendingBuffers = 0;
    uint32_t hint = 0;
    output.deflate(&mDefaultWidth, &mDefaultHeight, &hint,
            &numPendingBuffers);

    // Disable transform hint if sticky transform is set.
    if (mStickyTransform == 0) {
        mTransformHint = hint;
    }

    mConsumerRunningBehind = (numPendingBuffers >= 2);
    mDirtyRect.clear();
    return err;
}

int Surface::query(int what, int* value) const {
    ATRACE_CALL();
    ALOGV("Surface::query");
    { // scope for the lock
        Mutex::Autolock lock(mMutex);
        switch (what) {
            case NATIVE_WINDOW_FORMAT:
                if (mReqFormat) {
                    *value = mReqFormat;
                    return NO_ERROR;
                }
                break;
            case NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER: {
                sp<ISurfaceComposer> composer(
                        ComposerService::getComposerService());
                if (composer->authenticateSurfaceTexture(mGraphicBufferProducer)) {
                    *value = 1;
                } else {
                    *value = 0;
                }
                return NO_ERROR;
            }
            case NATIVE_WINDOW_CONCRETE_TYPE:
                *value = NATIVE_WINDOW_SURFACE;
                return NO_ERROR;
            case NATIVE_WINDOW_DEFAULT_WIDTH:
                *value = mUserWidth ? mUserWidth : mDefaultWidth;
                return NO_ERROR;
            case NATIVE_WINDOW_DEFAULT_HEIGHT:
                *value = mUserHeight ? mUserHeight : mDefaultHeight;
                return NO_ERROR;
            case NATIVE_WINDOW_TRANSFORM_HINT:
                *value = mTransformHint;
                return NO_ERROR;
            case NATIVE_WINDOW_CONSUMER_RUNNING_BEHIND: {
                status_t err = NO_ERROR;
                if (!mConsumerRunningBehind) {
                    *value = 0;
                } else {
                    err = mGraphicBufferProducer->query(what, value);
                    if (err == NO_ERROR) {
                        mConsumerRunningBehind = *value;
                    }
                }
                return err;
            }
        }
    }
    return mGraphicBufferProducer->query(what, value);
}

int Surface::perform(int operation, va_list args)
{
    int res = NO_ERROR;
    switch (operation) {
    case NATIVE_WINDOW_CONNECT:
        // deprecated. must return NO_ERROR.
        break;
    case NATIVE_WINDOW_DISCONNECT:
        // deprecated. must return NO_ERROR.
        break;
    case NATIVE_WINDOW_SET_USAGE:
        res = dispatchSetUsage(args);
        break;
    case NATIVE_WINDOW_SET_CROP:
        res = dispatchSetCrop(args);
        break;
    case NATIVE_WINDOW_SET_BUFFER_COUNT:
        res = dispatchSetBufferCount(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_GEOMETRY:
        res = dispatchSetBuffersGeometry(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_TRANSFORM:
        res = dispatchSetBuffersTransform(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_STICKY_TRANSFORM:
        res = dispatchSetBuffersStickyTransform(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_TIMESTAMP:
        res = dispatchSetBuffersTimestamp(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_DIMENSIONS:
        res = dispatchSetBuffersDimensions(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_USER_DIMENSIONS:
        res = dispatchSetBuffersUserDimensions(args);
        break;
    case NATIVE_WINDOW_SET_BUFFERS_FORMAT:
        res = dispatchSetBuffersFormat(args);
        break;
    case NATIVE_WINDOW_LOCK:
        res = dispatchLock(args);
        break;
    case NATIVE_WINDOW_UNLOCK_AND_POST:
        res = dispatchUnlockAndPost(args);
        break;
    case NATIVE_WINDOW_SET_SCALING_MODE:
        res = dispatchSetScalingMode(args);
        break;
    case NATIVE_WINDOW_API_CONNECT:
        res = dispatchConnect(args);
        break;
    case NATIVE_WINDOW_API_DISCONNECT:
        res = dispatchDisconnect(args);
        break;
    case NATIVE_WINDOW_SET_SIDEBAND_STREAM:
        res = dispatchSetSidebandStream(args);
        break;
    default:
        res = NAME_NOT_FOUND;
        break;
    }
    return res;
}

int Surface::dispatchConnect(va_list args) {
    int api = va_arg(args, int);
    return connect(api);
}

int Surface::dispatchDisconnect(va_list args) {
    int api = va_arg(args, int);
    return disconnect(api);
}

int Surface::dispatchSetUsage(va_list args) {
    int usage = va_arg(args, int);
    return setUsage(usage);
}

int Surface::dispatchSetCrop(va_list args) {
    android_native_rect_t const* rect = va_arg(args, android_native_rect_t*);
    return setCrop(reinterpret_cast<Rect const*>(rect));
}

int Surface::dispatchSetBufferCount(va_list args) {
    size_t bufferCount = va_arg(args, size_t);
    return setBufferCount(bufferCount);
}

int Surface::dispatchSetBuffersGeometry(va_list args) {
    int w = va_arg(args, int);
    int h = va_arg(args, int);
    int f = va_arg(args, int);
    int err = setBuffersDimensions(w, h);
    if (err != 0) {
        return err;
    }
    return setBuffersFormat(f);
}

int Surface::dispatchSetBuffersDimensions(va_list args) {
    int w = va_arg(args, int);
    int h = va_arg(args, int);
    return setBuffersDimensions(w, h);
}

int Surface::dispatchSetBuffersUserDimensions(va_list args) {
    int w = va_arg(args, int);
    int h = va_arg(args, int);
    return setBuffersUserDimensions(w, h);
}

int Surface::dispatchSetBuffersFormat(va_list args) {
    int f = va_arg(args, int);
    return setBuffersFormat(f);
}

int Surface::dispatchSetScalingMode(va_list args) {
    int m = va_arg(args, int);
    return setScalingMode(m);
}

int Surface::dispatchSetBuffersTransform(va_list args) {
    int transform = va_arg(args, int);
    return setBuffersTransform(transform);
}

int Surface::dispatchSetBuffersStickyTransform(va_list args) {
    int transform = va_arg(args, int);
    return setBuffersStickyTransform(transform);
}

int Surface::dispatchSetBuffersTimestamp(va_list args) {
    int64_t timestamp = va_arg(args, int64_t);
    return setBuffersTimestamp(timestamp);
}

int Surface::dispatchLock(va_list args) {
    ANativeWindow_Buffer* outBuffer = va_arg(args, ANativeWindow_Buffer*);
    ARect* inOutDirtyBounds = va_arg(args, ARect*);
    return lock(outBuffer, inOutDirtyBounds);
}

int Surface::dispatchUnlockAndPost(va_list args __attribute__((unused))) {
    return unlockAndPost();
}

int Surface::dispatchSetSidebandStream(va_list args) {
    native_handle_t* sH = va_arg(args, native_handle_t*);
    sp<NativeHandle> sidebandHandle = NativeHandle::create(sH, false);
    setSidebandStream(sidebandHandle);
    return OK;
}

int Surface::connect(int api) {
    ATRACE_CALL();
    ALOGV("Surface::connect");
    static sp<IProducerListener> listener = new DummyProducerListener();
    Mutex::Autolock lock(mMutex);
    IGraphicBufferProducer::QueueBufferOutput output;
    int err = mGraphicBufferProducer->connect(listener, api, mProducerControlledByApp, &output);
    if (err == NO_ERROR) {
        uint32_t numPendingBuffers = 0;
        uint32_t hint = 0;
        output.deflate(&mDefaultWidth, &mDefaultHeight, &hint,
                &numPendingBuffers);

        // Disable transform hint if sticky transform is set.
        if (mStickyTransform == 0) {
            mTransformHint = hint;
        }

        mConsumerRunningBehind = (numPendingBuffers >= 2);
    }
    if (!err && api == NATIVE_WINDOW_API_CPU) {
        mConnectedToCpu = true;
    }
    return err;
}


int Surface::disconnect(int api) {
    ATRACE_CALL();
    ALOGV("Surface::disconnect");
    Mutex::Autolock lock(mMutex);
    freeAllBuffers();
    int err = mGraphicBufferProducer->disconnect(api);
    if (!err) {
        mReqFormat = 0;
        mReqWidth = 0;
        mReqHeight = 0;
        mReqUsage = 0;
        mCrop.clear();
        mScalingMode = NATIVE_WINDOW_SCALING_MODE_FREEZE;
        mTransform = 0;
        mStickyTransform = 0;

        if (api == NATIVE_WINDOW_API_CPU) {
            mConnectedToCpu = false;
        }
    }
    return err;
}

int Surface::setUsage(uint32_t reqUsage)
{
    ALOGV("Surface::setUsage");
    Mutex::Autolock lock(mMutex);
    mReqUsage = reqUsage;
    return OK;
}

int Surface::setCrop(Rect const* rect)
{
    ATRACE_CALL();

    Rect realRect;
    if (rect == NULL || rect->isEmpty()) {
        realRect.clear();
    } else {
        realRect = *rect;
    }

    ALOGV("Surface::setCrop rect=[%d %d %d %d]",
            realRect.left, realRect.top, realRect.right, realRect.bottom);

    Mutex::Autolock lock(mMutex);
    mCrop = realRect;
    return NO_ERROR;
}

int Surface::setBufferCount(int bufferCount)
{
    ATRACE_CALL();
    ALOGV("Surface::setBufferCount");
    Mutex::Autolock lock(mMutex);

    status_t err = mGraphicBufferProducer->setBufferCount(bufferCount);
    ALOGE_IF(err, "IGraphicBufferProducer::setBufferCount(%d) returned %s",
            bufferCount, strerror(-err));

    if (err == NO_ERROR) {
        freeAllBuffers();
    }

    return err;
}

int Surface::setBuffersDimensions(int w, int h)
{
    ATRACE_CALL();
    ALOGV("Surface::setBuffersDimensions");

    if (w<0 || h<0)
        return BAD_VALUE;

    if ((w && !h) || (!w && h))
        return BAD_VALUE;

    Mutex::Autolock lock(mMutex);
    mReqWidth = w;
    mReqHeight = h;
    return NO_ERROR;
}

int Surface::setBuffersUserDimensions(int w, int h)
{
    ATRACE_CALL();
    ALOGV("Surface::setBuffersUserDimensions");

    if (w<0 || h<0)
        return BAD_VALUE;

    if ((w && !h) || (!w && h))
        return BAD_VALUE;

    Mutex::Autolock lock(mMutex);
    mUserWidth = w;
    mUserHeight = h;
    return NO_ERROR;
}

int Surface::setBuffersFormat(int format)
{
    ALOGV("Surface::setBuffersFormat");

    if (format<0)
        return BAD_VALUE;

    Mutex::Autolock lock(mMutex);
    mReqFormat = format;
    return NO_ERROR;
}

int Surface::setScalingMode(int mode)
{
    ATRACE_CALL();
    ALOGV("Surface::setScalingMode(%d)", mode);

    switch (mode) {
        case NATIVE_WINDOW_SCALING_MODE_FREEZE:
        case NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW:
        case NATIVE_WINDOW_SCALING_MODE_SCALE_CROP:
            break;
        case 300:	// close stereo
        case 301:	// left-right
		case 302:	// up-bottom
            break;
        default:
            ALOGE("unknown scaling mode: %d", mode);
            return BAD_VALUE;
    }

    Mutex::Autolock lock(mMutex);
    if(300==mode || 301==mode || 302==mode) {
		if(300==mode)   mScalingMode &= ~0x300;
        if(301==mode)   {mScalingMode &= ~0x300;mScalingMode |= 0x100;}
		if(302==mode)   {mScalingMode &= ~0x300;mScalingMode |= 0x200;}
    } else {
        mScalingMode &= ~0xff;
        mScalingMode |= mode;
    }
    return NO_ERROR;
}

int Surface::setBuffersTransform(int transform)
{
    ATRACE_CALL();
    ALOGV("Surface::setBuffersTransform");
    Mutex::Autolock lock(mMutex);
    mTransform = transform;
    return NO_ERROR;
}

int Surface::setBuffersStickyTransform(int transform)
{
    ATRACE_CALL();
    ALOGV("Surface::setBuffersStickyTransform");
    Mutex::Autolock lock(mMutex);
    mStickyTransform = transform;
    return NO_ERROR;
}

int Surface::setBuffersTimestamp(int64_t timestamp)
{
    ALOGV("Surface::setBuffersTimestamp");
    Mutex::Autolock lock(mMutex);
    mTimestamp = timestamp;
    return NO_ERROR;
}

void Surface::freeAllBuffers() {
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        mSlots[i].buffer = 0;
    }
}

// ----------------------------------------------------------------------
// the lock/unlock APIs must be used from the same thread
static int fd_rga=-1;

int hwChangeRgaFormat( int fmt )
{
    switch (fmt)
    {
    case HAL_PIXEL_FORMAT_RGB_565:
        return RK_FORMAT_RGB_565;
    case HAL_PIXEL_FORMAT_RGB_888:
        return RK_FORMAT_RGB_888;
    case HAL_PIXEL_FORMAT_RGBA_8888:
        return RK_FORMAT_RGBA_8888;
    case HAL_PIXEL_FORMAT_RGBX_8888:
        return RK_FORMAT_RGBX_8888;
    case HAL_PIXEL_FORMAT_BGRA_8888:
        return RK_FORMAT_BGRA_8888;
    case HAL_PIXEL_FORMAT_YCrCb_NV12:
        return RK_FORMAT_YCbCr_420_SP;
	case HAL_PIXEL_FORMAT_YCrCb_NV12_VIDEO:
	   return RK_FORMAT_YCbCr_420_SP;
    default:
        return -1;
    }
}

#ifndef USE_RGA_COPYBLT

static status_t copyBlt(
        const sp<GraphicBuffer>& dst,
        const sp<GraphicBuffer>& src,
        const Region& reg)
{
    // src and dst with, height and format must be identical. no verification
    // is done here.
    status_t err;
    uint8_t const * src_bits = NULL;
    err = src->lock(GRALLOC_USAGE_SW_READ_OFTEN, reg.bounds(), (void**)&src_bits);
    ALOGE_IF(err, "error locking src buffer %s", strerror(-err));

    uint8_t* dst_bits = NULL;
    err = dst->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, reg.bounds(), (void**)&dst_bits);
    ALOGE_IF(err, "error locking dst buffer %s", strerror(-err));

#ifndef USE_LCDC_COMPOSER
    Region::const_iterator head(reg.begin());
    Region::const_iterator tail(reg.end());
    if (head != tail && src_bits && dst_bits) {
        const size_t bpp = bytesPerPixel(src->format);
        const size_t dbpr = dst->stride * bpp;
        const size_t sbpr = src->stride * bpp;

        while (head != tail) {
            const Rect& r(*head++);
            ssize_t h = r.height();
            if (h <= 0) continue;
            size_t size = r.width() * bpp;
            uint8_t const * s = src_bits + (r.left + src->stride * r.top) * bpp;
            uint8_t       * d = dst_bits + (r.left + dst->stride * r.top) * bpp;
            if (dbpr==sbpr && size==sbpr) {
                size *= h;
                h = 1;
            }
            do {
                memcpy(d, s, size);
                d += dbpr;
                s += sbpr;
            } while (--h > 0);
        }
    }

#else
    int32_t vir_w = 0, vir_h = 0;
    Region::const_iterator head_(reg.begin());
    Region::const_iterator tail_(reg.end());
    if (head_ != tail_ && src_bits && dst_bits) {
        while (head_ != tail_) {
            const Rect& r(*head_++);
            ssize_t h_ = r.height();
            if (h_ <= 0) continue;
            int32_t tmp_w = r.right;
            int32_t tmp_h = r.bottom;
            vir_w = (vir_w >= tmp_w) ? vir_w : tmp_w;
            vir_h = (vir_h >= tmp_h) ? vir_h : tmp_h;
        }
    }
    head_ = reg.begin();
    if (head_ != tail_ && src_bits && dst_bits) {
        //const size_t bpp = bytesPerPixel(src->format);
        bool needsync = false;
        struct rga_req  Rga_Request;
        if(fd_rga < 0){
            property_set("sys.gui.version", "1.04");
            fd_rga = open("/dev/rga",O_RDWR,0);
            if(fd_rga < 0){
                ALOGE(" rga open err");
                goto ERROR;
            }
        }
        while (head_ != tail_) {
            if(needsync){
                if(ioctl(fd_rga, RGA_BLIT_ASYNC, &Rga_Request) != 0){
                    ALOGE("              rga ioctl  RGA_BLIT_ASYNC  err !!!!!!    fd_rga = %d", fd_rga);
                    goto ERROR;
                }
            }
            const Rect& r(*head_++);
            ssize_t h = r.height();
            if (h <= 0) continue;

            memset(&Rga_Request,0x0,sizeof(Rga_Request));
            //set src info
            Rga_Request.src.yrgb_addr =  (int)src_bits; //(int)s;
            Rga_Request.src.uv_addr  = 0;
            Rga_Request.src.v_addr   =  Rga_Request.src.uv_addr;
            Rga_Request.src.vir_w = src->stride;
            Rga_Request.src.vir_h = vir_h;
            Rga_Request.src.format = hwChangeRgaFormat(src->format);               // RK_FORMAT_RGBA_8888
            Rga_Request.src.act_w = r.width();
            Rga_Request.src.act_h = r.height();
            Rga_Request.src.x_offset = r.left;
            Rga_Request.src.y_offset = r.top;

            //set dst info
            Rga_Request.dst.yrgb_addr = (int)dst_bits;  //(int)d;
            Rga_Request.dst.uv_addr  = 0;
            Rga_Request.dst.v_addr   = Rga_Request.dst.uv_addr;
            Rga_Request.dst.vir_w = dst->stride;
            Rga_Request.dst.vir_h = vir_h;
            Rga_Request.dst.act_w = r.width();
            Rga_Request.dst.act_h = r.height();
            Rga_Request.clip.xmin = 0;
            Rga_Request.clip.xmax = Rga_Request.dst.vir_w - 1;
            Rga_Request.clip.ymin = 0;
            Rga_Request.clip.ymax = Rga_Request.dst.vir_h - 1;
            Rga_Request.dst.x_offset = r.left;
            Rga_Request.dst.y_offset = r.top;
            
            Rga_Request.sina = 0;
            Rga_Request.cosa = 0x10000;
            Rga_Request.dst.format = Rga_Request.src.format;               // RK_FORMAT_RGBA_8888

            Rga_Request.alpha_rop_flag |= (1 << 5);

            Rga_Request.mmu_info.mmu_en    = 1;
            Rga_Request.mmu_info.mmu_flag  = ((2 & 0x3) << 4) | 1;

            needsync = true;
        }
        if(needsync){
            if(ioctl(fd_rga, RGA_BLIT_ASYNC, &Rga_Request) != 0){
                ALOGE("          %s(%d):  RGA_BLIT_ASYNC Failed  err !!!!!!     fd_rga = %d ", __FUNCTION__, __LINE__, fd_rga);
                ALOGE("          src info: yrgb_addr=%x, uv_addr=%x,v_addr=%x,"
                 "vir_w=%d,vir_h=%d,format=%d,"
                 "act_x_y_w_h [%d,%d,%d,%d] ",
                                Rga_Request.src.yrgb_addr, Rga_Request.src.uv_addr ,Rga_Request.src.v_addr,
                                Rga_Request.src.vir_w ,Rga_Request.src.vir_h ,Rga_Request.src.format ,
                                Rga_Request.src.x_offset ,
                                Rga_Request.src.y_offset,
                                Rga_Request.src.act_w ,
                                Rga_Request.src.act_h

                );

                ALOGE("          dst info: yrgb_addr=%x, uv_addr=%x,v_addr=%x,"
                 "vir_w=%d,vir_h=%d,format=%d,"
                 "clip[%d,%d,%d,%d], "
                 "act_x_y_w_h [%d,%d,%d,%d] ",
                                Rga_Request.dst.yrgb_addr, Rga_Request.dst.uv_addr ,Rga_Request.dst.v_addr,
                                Rga_Request.dst.vir_w ,Rga_Request.dst.vir_h ,Rga_Request.dst.format,
                                Rga_Request.clip.xmin,
                                Rga_Request.clip.xmax,
                                Rga_Request.clip.ymin,
                                Rga_Request.clip.ymax,
                                Rga_Request.dst.x_offset ,
                                Rga_Request.dst.y_offset,
                                Rga_Request.dst.act_w ,
                                Rga_Request.dst.act_h

                );
               goto ERROR;
            }
        }
    }
ERROR:
#endif
    if (src_bits)
        src->unlock();

    if (dst_bits)
        dst->unlock();

    return err;
}
#else
/*
 * RgaCopybltThread add by yzq, because RGA_BLIT_ASYNC would use 3ms,
 * use thread let the work noblock main thread.
 */
class RgaCopybltThread : public Thread {
	static const int MAX_REQ = 5;
	struct rga_req  mRga_Request[MAX_REQ];
	int req_pend;
	public:
	RgaCopybltThread():Thread(false) {
		req_pend = 0;
		if(fd_rga<0){
			property_set("sys.gui.version", "1.05");
			fd_rga = open("/dev/rga",O_RDWR,0);
			if(fd_rga < 0){
				ALOGE(" rga open err");
			}
		}
	}

	virtual ~RgaCopybltThread() {
	}

	bool threadLoop() {
		while(req_pend){
			int tmp_pend = req_pend;
			int i=0;
			for(i=0;i<MAX_REQ;i++){
				if(( tmp_pend>>i ) & 0x1){
					if(ioctl(fd_rga, RGA_BLIT_ASYNC, &mRga_Request[i]) != 0){
						ALOGE("              rga ioctl  RGA_BLIT_ASYNC  err !!!!!!    fd_rga = %d", fd_rga);
					}
					req_pend &= ~(1<<i);
				}	
			}
		}
		return false;
	}
	bool setRgaReq(struct rga_req *req){
		int i=0;
		if(fd_rga < 0){
			ALOGE("can't not setRgaReq,fd_rga = %d",fd_rga);
			return false;
		}

		for(i=0;i<MAX_REQ;i++){
			if(!((req_pend>>i) & 0x1)){
				req_pend |= (1<<i);
				memcpy(&mRga_Request[i],req,sizeof(struct rga_req));
				break;
			}
		}

		if(i==MAX_REQ){
			if(ioctl(fd_rga, RGA_BLIT_ASYNC, req) != 0){
				ALOGE("              rga ioctl  RGA_BLIT_ASYNC  err !!!!!!    fd_rga = %d", fd_rga);
			}
		}

		run();
		return true;
	}
};
static status_t copyBlt(
        const sp<GraphicBuffer>& dst,
        const sp<GraphicBuffer>& src,
        const Region& reg)
{
    // src and dst with, height and format must be identical. no verification
    // is done here.
    status_t err;
    bool ioctl_fail = false;
    uint8_t const * src_bits = NULL;
    err = src->lock(GRALLOC_USAGE_SW_READ_OFTEN, reg.bounds(), (void**)&src_bits);
    ALOGE_IF(err, "error locking src buffer %s", strerror(-err));

    uint8_t* dst_bits = NULL;
    err = dst->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, reg.bounds(), (void**)&dst_bits);
    ALOGE_IF(err, "error locking dst buffer %s", strerror(-err));

    int32_t vir_w = 0, vir_h = 0;
    Region::const_iterator head_(reg.begin());
    Region::const_iterator tail_(reg.end());
    if (head_ != tail_ && src_bits && dst_bits) {
        while (head_ != tail_) {
            const Rect& r(*head_++);
            ssize_t h_ = r.height();
            if (h_ <= 0) continue;
            int32_t tmp_w = r.right;
            int32_t tmp_h = r.bottom;
            vir_w = (vir_w >= tmp_w) ? vir_w : tmp_w;
            vir_h = (vir_h >= tmp_h) ? vir_h : tmp_h;
        }
    }
	static sp<RgaCopybltThread> rgaCopyblt_t(new RgaCopybltThread());
    head_ = reg.begin();
    if (head_ != tail_ && src_bits && dst_bits) {
        //const size_t bpp = bytesPerPixel(src->format);
        bool needsync = false;
        struct rga_req  Rga_Request;
        while (head_ != tail_) {
            if(needsync){
				if(!rgaCopyblt_t->setRgaReq(&Rga_Request))
					goto ERROR;
			}
            const Rect& r(*head_++);
            ssize_t h = r.height();
            if (h <= 0) continue;

            memset(&Rga_Request,0x0,sizeof(Rga_Request));
            //set src info
            Rga_Request.src.yrgb_addr =  0; //(int)s;
            Rga_Request.src.uv_addr  = (int)src_bits;
            Rga_Request.src.v_addr   =  Rga_Request.src.uv_addr;
            Rga_Request.src.vir_w = src->stride;
            Rga_Request.src.vir_h = vir_h;
            Rga_Request.src.format = hwChangeRgaFormat(src->format);               // RK_FORMAT_RGBA_8888
            Rga_Request.src.act_w = r.width();
            Rga_Request.src.act_h = r.height();
            Rga_Request.src.x_offset = r.left;
            Rga_Request.src.y_offset = r.top;

            //set dst info
            Rga_Request.dst.yrgb_addr = 0;  //(int)d;
            Rga_Request.dst.uv_addr  = (int)dst_bits;
            Rga_Request.dst.v_addr   = Rga_Request.dst.uv_addr;
            Rga_Request.dst.vir_w = dst->stride;
            Rga_Request.dst.vir_h = vir_h;
            Rga_Request.dst.act_w = r.width();
            Rga_Request.dst.act_h = r.height();
            Rga_Request.clip.xmin = 0;
            Rga_Request.clip.xmax = Rga_Request.dst.vir_w - 1;
            Rga_Request.clip.ymin = 0;
            Rga_Request.clip.ymax = Rga_Request.dst.vir_h - 1;
            Rga_Request.dst.x_offset = r.left;
            Rga_Request.dst.y_offset = r.top;
            
            Rga_Request.sina = 0;
            Rga_Request.cosa = 0x10000;
            Rga_Request.dst.format =  Rga_Request.src.format;               // RK_FORMAT_RGBA_8888

            Rga_Request.alpha_rop_flag |= (1 << 5);

            Rga_Request.mmu_info.mmu_en    = 1;
            Rga_Request.mmu_info.mmu_flag  = ((2 & 0x3) << 4) | 1;            
            Rga_Request.mmu_info.mmu_flag |= ((1<<31) | (1 << 8) | (1<<10));

            needsync = true;
		}
		if(needsync){
			if(!rgaCopyblt_t->setRgaReq(&Rga_Request))
				goto ERROR;
		}
    }
ERROR:
    if(ioctl_fail){
    Region::const_iterator head(reg.begin());
    Region::const_iterator tail(reg.end());
    if (head != tail && src_bits && dst_bits) {
        const size_t bpp = bytesPerPixel(src->format);
        const size_t dbpr = dst->stride * bpp;
        const size_t sbpr = src->stride * bpp;

        while (head != tail) {
            const Rect& r(*head++);
            ssize_t h = r.height();
            if (h <= 0) continue;
            size_t size = r.width() * bpp;
            uint8_t const * s = src_bits + (r.left + src->stride * r.top) * bpp;
            uint8_t       * d = dst_bits + (r.left + dst->stride * r.top) * bpp;
            if (dbpr==sbpr && size==sbpr) {
                size *= h;
                h = 1;
            }
            do {
                memcpy(d, s, size);
                d += dbpr;
                s += sbpr;
            } while (--h > 0);
        }
    }
    }
 
    if (src_bits)
        src->unlock();

    if (dst_bits)
        dst->unlock();

    return err;
}
#endif		//endifdef USE_RGA_COPYBLT  just use in rk32xx   

// ----------------------------------------------------------------------------

status_t Surface::lock(
        ANativeWindow_Buffer* outBuffer, ARect* inOutDirtyBounds)
{
    if (mLockedBuffer != 0) {
        ALOGE("Surface::lock failed, already locked");
        return INVALID_OPERATION;
    }

    if (!mConnectedToCpu) {
        int err = Surface::connect(NATIVE_WINDOW_API_CPU);
        if (err) {
            return err;
        }
        // we're intending to do software rendering from this point
        setUsage(GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN);
    }

    ANativeWindowBuffer* out;
    int fenceFd = -1;
    status_t err = dequeueBuffer(&out, &fenceFd);
    ALOGE_IF(err, "dequeueBuffer failed (%s)", strerror(-err));
    if (err == NO_ERROR) {
        sp<GraphicBuffer> backBuffer(GraphicBuffer::getSelf(out));
        const Rect bounds(backBuffer->width, backBuffer->height);

        Region newDirtyRegion;
        if (inOutDirtyBounds) {
#ifdef USE_LCDC_COMPOSER
            int32_t tmp_l = inOutDirtyBounds->left;
            int32_t tmp_r = inOutDirtyBounds->right;
            int32_t tmp_t = inOutDirtyBounds->top;
            int32_t tmp_b = inOutDirtyBounds->bottom;
            if(mTransform == 4){
                inOutDirtyBounds->left = tmp_t;
                inOutDirtyBounds->right = tmp_b;
                inOutDirtyBounds->top = backBuffer->height - tmp_r;
                inOutDirtyBounds->bottom = backBuffer->height - tmp_l;
            }
            if(mTransform == 3){
                inOutDirtyBounds->left = backBuffer->width - tmp_r;
                inOutDirtyBounds->right = backBuffer->width - tmp_l;
                inOutDirtyBounds->top = backBuffer->height - tmp_b;
                inOutDirtyBounds->bottom = backBuffer->height - tmp_t;
            }
            if(mTransform == 7){
                inOutDirtyBounds->left = backBuffer->width - tmp_b;
                inOutDirtyBounds->right = backBuffer->width - tmp_t;;
                inOutDirtyBounds->top = tmp_l;
                inOutDirtyBounds->bottom = tmp_r;
            }
#endif

            newDirtyRegion.set(static_cast<Rect const&>(*inOutDirtyBounds));
            newDirtyRegion.andSelf(bounds);
        } else {
            newDirtyRegion.set(bounds);
        }

        // figure out if we can copy the frontbuffer back
        int backBufferSlot(getSlotFromBufferLocked(backBuffer.get()));
        const sp<GraphicBuffer>& frontBuffer(mPostedBuffer);
        const bool canCopyBack = (frontBuffer != 0 &&
                backBuffer->width  == frontBuffer->width &&
                backBuffer->height == frontBuffer->height &&
                backBuffer->format == frontBuffer->format);

        if (canCopyBack) {
            Mutex::Autolock lock(mMutex);
            Region oldDirtyRegion;
            for(int i = 0 ; i < NUM_BUFFER_SLOTS; i++ ) {
                if(i != backBufferSlot && !mSlots[i].dirtyRegion.isEmpty())
                    oldDirtyRegion.orSelf(mSlots[i].dirtyRegion);
            }
            const Region copyback(oldDirtyRegion.subtract(newDirtyRegion));
            if (!copyback.isEmpty())
                copyBlt(backBuffer, frontBuffer, copyback);
        } else {
            // if we can't copy-back anything, modify the user's dirty
            // region to make sure they redraw the whole buffer
            newDirtyRegion.set(bounds);
            Mutex::Autolock lock(mMutex);
            for (size_t i=0 ; i<NUM_BUFFER_SLOTS ; i++) {
                mSlots[i].dirtyRegion.clear();
            }
        }


        { // scope for the lock
            Mutex::Autolock lock(mMutex);
            if (backBufferSlot >= 0) {
               mSlots[backBufferSlot].dirtyRegion = newDirtyRegion;
            }
        }

        if (inOutDirtyBounds) {
            *inOutDirtyBounds = newDirtyRegion.getBounds();
        }

        void* vaddr;
        status_t res = backBuffer->lockAsync(
                GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN,
                newDirtyRegion.bounds(), &vaddr, fenceFd);

        ALOGW_IF(res, "failed locking buffer (handle = %p)",
                backBuffer->handle);

        if (res != 0) {
            err = INVALID_OPERATION;
        } else {
            mLockedBuffer = backBuffer;
            outBuffer->width  = backBuffer->width;
            outBuffer->height = backBuffer->height;
            outBuffer->stride = backBuffer->stride;
            outBuffer->format = backBuffer->format;
            outBuffer->bits   = vaddr;
        }
    }
    return err;
}

status_t Surface::unlockAndPost()
{
    if (mLockedBuffer == 0) {
        ALOGE("Surface::unlockAndPost failed, no locked buffer");
        return INVALID_OPERATION;
    }

    int fd = -1;
    status_t err = mLockedBuffer->unlockAsync(&fd);
    ALOGE_IF(err, "failed unlocking buffer (%p)", mLockedBuffer->handle);

    err = queueBuffer(mLockedBuffer.get(), fd);
    ALOGE_IF(err, "queueBuffer (handle=%p) failed (%s)",
            mLockedBuffer->handle, strerror(-err));

    mPostedBuffer = mLockedBuffer;
    mLockedBuffer = 0;
#if (defined USE_LCDC_COMPOSER) || (defined USE_RGA_COPYBLT)
    if(fd_rga != -1){
        if(ioctl(fd_rga, RGA_FLUSH, NULL) != 0) {
            ALOGE("unlockAndPost RGA_FLUSH err,   fd_rga: %d,  err: %d ", fd_rga, err);
        }
    }
#endif
    return err;
}

}; // namespace android
