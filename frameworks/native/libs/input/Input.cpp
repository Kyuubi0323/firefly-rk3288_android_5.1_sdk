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

#define LOG_TAG "Input"
//#define LOG_NDEBUG 0

#include <math.h>
#include <limits.h>

#include <input/Input.h>
#include <input/InputEventLabels.h>
#include <cutils/log.h>

#ifdef HAVE_ANDROID_OS
#include <binder/Parcel.h>
#endif

#define DEBUG_ZJY 0
#if DEBUG_ZJY
#undef XLOG
#define XLOG(...) android_printLog(ANDROID_LOG_DEBUG, "Input.cpp", __VA_ARGS__)
#else
#undef XLOG
#define XLOG(...)
#endif
#define DEBUG_ZJY_DEBUG 0
#if DEBUG_ZJY_DEBUG
#undef XLOGD
#define XLOGD(...) android_printLog(ANDROID_LOG_DEBUG, "Input.cpp", __VA_ARGS__)
#else
#undef XLOGD
#define XLOGD(...)
#endif

namespace android {

// --- InputEvent ---

void InputEvent::initialize(int32_t deviceId, int32_t source) {
    mDeviceId = deviceId;
    mSource = source;
}

void InputEvent::initialize(const InputEvent& from) {
    mDeviceId = from.mDeviceId;
    mSource = from.mSource;
}

// --- KeyEvent ---

const char* KeyEvent::getLabel(int32_t keyCode) {
    return getLabelByKeyCode(keyCode);
}

int32_t KeyEvent::getKeyCodeFromLabel(const char* label) {
    return getKeyCodeByLabel(label);
}

void KeyEvent::initialize(
        int32_t deviceId,
        int32_t source,
        int32_t action,
        int32_t flags,
        int32_t keyCode,
        int32_t scanCode,
        int32_t metaState,
        int32_t repeatCount,
        nsecs_t downTime,
        nsecs_t eventTime) {
    InputEvent::initialize(deviceId, source);
    mAction = action;
    mFlags = flags;
    mKeyCode = keyCode;
    mScanCode = scanCode;
    mMetaState = metaState;
    mRepeatCount = repeatCount;
    mDownTime = downTime;
    mEventTime = eventTime;
}

void KeyEvent::initialize(const KeyEvent& from) {
    InputEvent::initialize(from);
    mAction = from.mAction;
    mFlags = from.mFlags;
    mKeyCode = from.mKeyCode;
    mScanCode = from.mScanCode;
    mMetaState = from.mMetaState;
    mRepeatCount = from.mRepeatCount;
    mDownTime = from.mDownTime;
    mEventTime = from.mEventTime;
}


// --- PointerCoords ---

float PointerCoords::getAxisValue(int32_t axis) const {
    if (axis < 0 || axis > 63 || !BitSet64::hasBit(bits, axis)){
        return 0;
    }
    return values[BitSet64::getIndexOfBit(bits, axis)];
}

status_t PointerCoords::setAxisValue(int32_t axis, float value) {
    if (axis < 0 || axis > 63) {
        return NAME_NOT_FOUND;
    }

    uint32_t index = BitSet64::getIndexOfBit(bits, axis);
    if (!BitSet64::hasBit(bits, axis)) {
        if (value == 0) {
            return OK; // axes with value 0 do not need to be stored
        }

        uint32_t count = BitSet64::count(bits);
        if (count >= MAX_AXES) {
            tooManyAxes(axis);
            return NO_MEMORY;
        }
        BitSet64::markBit(bits, axis);
        for (uint32_t i = count; i > index; i--) {
            values[i] = values[i - 1];
        }
    }

    values[index] = value;
    return OK;
}

static inline void scaleAxisValue(PointerCoords& c, int axis, float scaleFactor) {
    float value = c.getAxisValue(axis);
    if (value != 0) {
        c.setAxisValue(axis, value * scaleFactor);
    }
}

static inline void transformAxisValue(PointerCoords& c, int axis,float offset, float scale){
	float value = c.getAxisValue(axis);
	if(value != 0){
		c.setAxisValue(axis, (value - offset)/scale);
	}
}
void PointerCoords::scale(float scaleFactor) {
    // No need to scale pressure or size since they are normalized.
    // No need to scale orientation since it is meaningless to do so.
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_X, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_Y, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_TOUCH_MAJOR, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_TOUCH_MINOR, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_TOOL_MAJOR, scaleFactor);
    scaleAxisValue(*this, AMOTION_EVENT_AXIS_TOOL_MINOR, scaleFactor);
}
void PointerCoords::transfromCoordinate(float posX,float posY,float scaleX,float scaleY){
	XLOGD("posXY=(%f,%f), scaleXY=(%f,%f)",posX, posY, scaleX, scaleY);
	transformAxisValue(*this, AMOTION_EVENT_AXIS_X, posX, scaleX);
	transformAxisValue(*this, AMOTION_EVENT_AXIS_Y, posY, scaleY);
}
void PointerCoords::applyOffset(float xOffset, float yOffset) {
    setAxisValue(AMOTION_EVENT_AXIS_X, getX() + xOffset);
    setAxisValue(AMOTION_EVENT_AXIS_Y, getY() + yOffset);
}

#ifdef HAVE_ANDROID_OS
status_t PointerCoords::readFromParcel(Parcel* parcel) {
    bits = parcel->readInt64();

    uint32_t count = BitSet64::count(bits);
    if (count > MAX_AXES) {
        return BAD_VALUE;
    }

    for (uint32_t i = 0; i < count; i++) {
        values[i] = parcel->readFloat();
    }
    return OK;
}

status_t PointerCoords::writeToParcel(Parcel* parcel) const {
    parcel->writeInt64(bits);

    uint32_t count = BitSet64::count(bits);
    for (uint32_t i = 0; i < count; i++) {
        parcel->writeFloat(values[i]);
    }
    return OK;
}
#endif

void PointerCoords::tooManyAxes(int axis) {
    ALOGW("Could not set value for axis %d because the PointerCoords structure is full and "
            "cannot contain more than %d axis values.", axis, int(MAX_AXES));
}

bool PointerCoords::operator==(const PointerCoords& other) const {
    if (bits != other.bits) {
        return false;
    }
    uint32_t count = BitSet64::count(bits);
    for (uint32_t i = 0; i < count; i++) {
        if (values[i] != other.values[i]) {
            return false;
        }
    }
    return true;
}

void PointerCoords::copyFrom(const PointerCoords& other) {
    bits = other.bits;
    uint32_t count = BitSet64::count(bits);
    for (uint32_t i = 0; i < count; i++) {
        values[i] = other.values[i];
    }
}


// --- PointerProperties ---

bool PointerProperties::operator==(const PointerProperties& other) const {
    return id == other.id
            && toolType == other.toolType;
}

void PointerProperties::copyFrom(const PointerProperties& other) {
    id = other.id;
    toolType = other.toolType;
}


// --- MotionEvent ---

void MotionEvent::initialize(
        int32_t deviceId,
        int32_t source,
        int32_t action,
        int32_t flags,
        int32_t edgeFlags,
        int32_t metaState,
        int32_t buttonState,
        float xOffset,
        float yOffset,
        float xPrecision,
        float yPrecision,
        nsecs_t downTime,
        nsecs_t eventTime,
        size_t pointerCount,
        const PointerProperties* pointerProperties,
        const PointerCoords* pointerCoords) {
        XLOG("initialize");
    InputEvent::initialize(deviceId, source);
    mAction = action;
    mFlags = flags;
    mEdgeFlags = edgeFlags;
    mMetaState = metaState;
    mButtonState = buttonState;
    mXOffset = xOffset;
    mYOffset = yOffset;
    mXPrecision = xPrecision;
    mYPrecision = yPrecision;
    mDownTime = downTime;
	XLOG("mPointerProperties appent before pointerProperties=%p,pointerCount=%d",pointerProperties,
		pointerCount);
    mPointerProperties.clear();
    mPointerProperties.appendArray(pointerProperties, pointerCount);
	XLOG("mPointerProperties appent after");
    mSampleEventTimes.clear();
    mSamplePointerCoords.clear();
    addSample(eventTime, pointerCoords);
	XLOG("initialize end ");
}

void MotionEvent::copyFrom(const MotionEvent* other, bool keepHistory) {
    InputEvent::initialize(other->mDeviceId, other->mSource);
    mAction = other->mAction;
    mFlags = other->mFlags;
    mEdgeFlags = other->mEdgeFlags;
    mMetaState = other->mMetaState;
    mButtonState = other->mButtonState;
    mXOffset = other->mXOffset;
    mYOffset = other->mYOffset;
    mXPrecision = other->mXPrecision;
    mYPrecision = other->mYPrecision;
    mDownTime = other->mDownTime;
    mPointerProperties = other->mPointerProperties;

    if (keepHistory) {
        mSampleEventTimes = other->mSampleEventTimes;
        mSamplePointerCoords = other->mSamplePointerCoords;
    } else {
        mSampleEventTimes.clear();
        mSampleEventTimes.push(other->getEventTime());
        mSamplePointerCoords.clear();
        size_t pointerCount = other->getPointerCount();
        size_t historySize = other->getHistorySize();
        mSamplePointerCoords.appendArray(other->mSamplePointerCoords.array()
                + (historySize * pointerCount), pointerCount);
    }
}

void MotionEvent::addSample(
        int64_t eventTime,
        const PointerCoords* pointerCoords) {
        XLOG("addSample before");
    mSampleEventTimes.push(eventTime);
    mSamplePointerCoords.appendArray(pointerCoords, getPointerCount());
	XLOG("addsample after");
}

const PointerCoords* MotionEvent::getRawPointerCoords(size_t pointerIndex) const {
    return &mSamplePointerCoords[getHistorySize() * getPointerCount() + pointerIndex];
}

float MotionEvent::getRawAxisValue(int32_t axis, size_t pointerIndex) const {
    return getRawPointerCoords(pointerIndex)->getAxisValue(axis);
}

float MotionEvent::getAxisValue(int32_t axis, size_t pointerIndex) const {
    float value = getRawPointerCoords(pointerIndex)->getAxisValue(axis);
    switch (axis) {
    case AMOTION_EVENT_AXIS_X:
        return value + mXOffset;
    case AMOTION_EVENT_AXIS_Y:
        return value + mYOffset;
    }
    return value;
}

const PointerCoords* MotionEvent::getHistoricalRawPointerCoords(
        size_t pointerIndex, size_t historicalIndex) const {
    return &mSamplePointerCoords[historicalIndex * getPointerCount() + pointerIndex];
}

float MotionEvent::getHistoricalRawAxisValue(int32_t axis, size_t pointerIndex,
        size_t historicalIndex) const {
    return getHistoricalRawPointerCoords(pointerIndex, historicalIndex)->getAxisValue(axis);
}

float MotionEvent::getHistoricalAxisValue(int32_t axis, size_t pointerIndex,
        size_t historicalIndex) const {
    float value = getHistoricalRawPointerCoords(pointerIndex, historicalIndex)->getAxisValue(axis);
    switch (axis) {
    case AMOTION_EVENT_AXIS_X:
        return value + mXOffset;
    case AMOTION_EVENT_AXIS_Y:
        return value + mYOffset;
    }
    return value;
}

ssize_t MotionEvent::findPointerIndex(int32_t pointerId) const {
    size_t pointerCount = mPointerProperties.size();
    for (size_t i = 0; i < pointerCount; i++) {
        if (mPointerProperties.itemAt(i).id == pointerId) {
            return i;
        }
    }
    return -1;
}

void MotionEvent::offsetLocation(float xOffset, float yOffset) {
    mXOffset += xOffset;
    mYOffset += yOffset;
}

void MotionEvent::scale(float scaleFactor) {
    mXOffset *= scaleFactor;
    mYOffset *= scaleFactor;
    mXPrecision *= scaleFactor;
    mYPrecision *= scaleFactor;

    size_t numSamples = mSamplePointerCoords.size();
    for (size_t i = 0; i < numSamples; i++) {
        mSamplePointerCoords.editItemAt(i).scale(scaleFactor);
    }
}

void MotionEvent::transformCoordinate(float posX,float posY,float scaleX,float scaleY){
		size_t numSamples = mSamplePointerCoords.size();
    for (size_t i = 0; i < numSamples; i++) {
        mSamplePointerCoords.editItemAt(i).transfromCoordinate(posX, posY, scaleX, scaleY);
    }
}

static void transformPoint(const float matrix[9], float x, float y, float *outX, float *outY) {
    // Apply perspective transform like Skia.
    float newX = matrix[0] * x + matrix[1] * y + matrix[2];
    float newY = matrix[3] * x + matrix[4] * y + matrix[5];
    float newZ = matrix[6] * x + matrix[7] * y + matrix[8];
    if (newZ) {
        newZ = 1.0f / newZ;
    }
    *outX = newX * newZ;
    *outY = newY * newZ;
}

static float transformAngle(const float matrix[9], float angleRadians,
        float originX, float originY) {
    // Construct and transform a vector oriented at the specified clockwise angle from vertical.
    // Coordinate system: down is increasing Y, right is increasing X.
    float x = sinf(angleRadians);
    float y = -cosf(angleRadians);
    transformPoint(matrix, x, y, &x, &y);
    x -= originX;
    y -= originY;

    // Derive the transformed vector's clockwise angle from vertical.
    float result = atan2f(x, -y);
    if (result < - M_PI_2) {
        result += M_PI;
    } else if (result > M_PI_2) {
        result -= M_PI;
    }
    return result;
}

void MotionEvent::transform(const float matrix[9]) {
    // The tricky part of this implementation is to preserve the value of
    // rawX and rawY.  So we apply the transformation to the first point
    // then derive an appropriate new X/Y offset that will preserve rawX
     // and rawY for that point.
    float oldXOffset = mXOffset;
    float oldYOffset = mYOffset;
    float newX, newY;
    float rawX = getRawX(0);
    float rawY = getRawY(0);
    transformPoint(matrix, rawX + oldXOffset, rawY + oldYOffset, &newX, &newY);
    mXOffset = newX - rawX;
    mYOffset = newY - rawY;

    // Determine how the origin is transformed by the matrix so that we
    // can transform orientation vectors.
    float originX, originY;
    transformPoint(matrix, 0, 0, &originX, &originY);

    // Apply the transformation to all samples.
    size_t numSamples = mSamplePointerCoords.size();
    for (size_t i = 0; i < numSamples; i++) {
        PointerCoords& c = mSamplePointerCoords.editItemAt(i);
        float x = c.getAxisValue(AMOTION_EVENT_AXIS_X) + oldXOffset;
        float y = c.getAxisValue(AMOTION_EVENT_AXIS_Y) + oldYOffset;
        transformPoint(matrix, x, y, &x, &y);
        c.setAxisValue(AMOTION_EVENT_AXIS_X, x - mXOffset);
        c.setAxisValue(AMOTION_EVENT_AXIS_Y, y - mYOffset);

        float orientation = c.getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION);
        c.setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION,
                transformAngle(matrix, orientation, originX, originY));
    }
}

#ifdef HAVE_ANDROID_OS
status_t MotionEvent::readFromParcel(Parcel* parcel) {
	XLOG("readFromParcel");
    size_t pointerCount = parcel->readInt32();
    size_t sampleCount = parcel->readInt32();
    if (pointerCount == 0 || pointerCount > MAX_POINTERS || sampleCount == 0) {
        return BAD_VALUE;
    }

    mDeviceId = parcel->readInt32();
    mSource = parcel->readInt32();
    mAction = parcel->readInt32();
    mFlags = parcel->readInt32();
    mEdgeFlags = parcel->readInt32();
    mMetaState = parcel->readInt32();
    mButtonState = parcel->readInt32();
    mXOffset = parcel->readFloat();
    mYOffset = parcel->readFloat();
    mXPrecision = parcel->readFloat();
    mYPrecision = parcel->readFloat();
    mDownTime = parcel->readInt64();

    mPointerProperties.clear();
    mPointerProperties.setCapacity(pointerCount);
    mSampleEventTimes.clear();
    mSampleEventTimes.setCapacity(sampleCount);
    mSamplePointerCoords.clear();
    mSamplePointerCoords.setCapacity(sampleCount * pointerCount);

    for (size_t i = 0; i < pointerCount; i++) {
        mPointerProperties.push();
        PointerProperties& properties = mPointerProperties.editTop();
        properties.id = parcel->readInt32();
        properties.toolType = parcel->readInt32();
    }

    while (sampleCount-- > 0) {
        mSampleEventTimes.push(parcel->readInt64());
        for (size_t i = 0; i < pointerCount; i++) {
            mSamplePointerCoords.push();
            status_t status = mSamplePointerCoords.editTop().readFromParcel(parcel);
            if (status) {
                return status;
            }
        }
    }
    return OK;
}

status_t MotionEvent::writeToParcel(Parcel* parcel) const {
	XLOG("writeToParcel");
    size_t pointerCount = mPointerProperties.size();
    size_t sampleCount = mSampleEventTimes.size();

    parcel->writeInt32(pointerCount);
    parcel->writeInt32(sampleCount);

    parcel->writeInt32(mDeviceId);
    parcel->writeInt32(mSource);
    parcel->writeInt32(mAction);
    parcel->writeInt32(mFlags);
    parcel->writeInt32(mEdgeFlags);
    parcel->writeInt32(mMetaState);
    parcel->writeInt32(mButtonState);
    parcel->writeFloat(mXOffset);
    parcel->writeFloat(mYOffset);
    parcel->writeFloat(mXPrecision);
    parcel->writeFloat(mYPrecision);
    parcel->writeInt64(mDownTime);

    for (size_t i = 0; i < pointerCount; i++) {
        const PointerProperties& properties = mPointerProperties.itemAt(i);
        parcel->writeInt32(properties.id);
        parcel->writeInt32(properties.toolType);
    }

    const PointerCoords* pc = mSamplePointerCoords.array();
    for (size_t h = 0; h < sampleCount; h++) {
        parcel->writeInt64(mSampleEventTimes.itemAt(h));
        for (size_t i = 0; i < pointerCount; i++) {
            status_t status = (pc++)->writeToParcel(parcel);
            if (status) {
                return status;
            }
        }
    }
    return OK;
}
#endif

bool MotionEvent::isTouchEvent(int32_t source, int32_t action) {
    if (source & AINPUT_SOURCE_CLASS_POINTER) {
        // Specifically excludes HOVER_MOVE and SCROLL.
        switch (action & AMOTION_EVENT_ACTION_MASK) {
        case AMOTION_EVENT_ACTION_DOWN:
        case AMOTION_EVENT_ACTION_MOVE:
        case AMOTION_EVENT_ACTION_UP:
        case AMOTION_EVENT_ACTION_POINTER_DOWN:
        case AMOTION_EVENT_ACTION_POINTER_UP:
        case AMOTION_EVENT_ACTION_CANCEL:
        case AMOTION_EVENT_ACTION_OUTSIDE:
            return true;
        }
    }
    return false;
}

const char* MotionEvent::getLabel(int32_t axis) {
    return getAxisLabel(axis);
}

int32_t MotionEvent::getAxisFromLabel(const char* label) {
    return getAxisByLabel(label);
}


// --- PooledInputEventFactory ---

PooledInputEventFactory::PooledInputEventFactory(size_t maxPoolSize) :
        mMaxPoolSize(maxPoolSize) {
}

PooledInputEventFactory::~PooledInputEventFactory() {
    for (size_t i = 0; i < mKeyEventPool.size(); i++) {
        delete mKeyEventPool.itemAt(i);
    }
    for (size_t i = 0; i < mMotionEventPool.size(); i++) {
        delete mMotionEventPool.itemAt(i);
    }
}

KeyEvent* PooledInputEventFactory::createKeyEvent() {
    if (!mKeyEventPool.isEmpty()) {
        KeyEvent* event = mKeyEventPool.top();
        mKeyEventPool.pop();
        return event;
    }
    return new KeyEvent();
}

MotionEvent* PooledInputEventFactory::createMotionEvent() {
    if (!mMotionEventPool.isEmpty()) {
        MotionEvent* event = mMotionEventPool.top();
        mMotionEventPool.pop();
        return event;
    }
    return new MotionEvent();
}

void PooledInputEventFactory::recycle(InputEvent* event) {
    switch (event->getType()) {
    case AINPUT_EVENT_TYPE_KEY:
        if (mKeyEventPool.size() < mMaxPoolSize) {
            mKeyEventPool.push(static_cast<KeyEvent*>(event));
            return;
        }
        break;
    case AINPUT_EVENT_TYPE_MOTION:
        if (mMotionEventPool.size() < mMaxPoolSize) {
            mMotionEventPool.push(static_cast<MotionEvent*>(event));
            return;
        }
        break;
    }
    delete event;
}

} // namespace android
