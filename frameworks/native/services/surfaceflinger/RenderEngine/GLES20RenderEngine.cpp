/*
 * Copyright 2013 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_GRAPHICS

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <ui/Rect.h>

#include <utils/String8.h>
#include <utils/Trace.h>

#include <cutils/compiler.h>
#include <gui/ISurfaceComposer.h>
#include <math.h>
#include <cutils/properties.h>

#include "GLES20RenderEngine.h"
#include "Program.h"
#include "ProgramCache.h"
#include "Description.h"
#include "Mesh.h"
#include "Texture.h"

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

GLES20RenderEngine::GLES20RenderEngine() :
        mVpWidth(0), mVpHeight(0) {

    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &mMaxTextureSize);
    glGetIntegerv(GL_MAX_VIEWPORT_DIMS, mMaxViewportDims);

    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);

    struct pack565 {
        inline uint16_t operator() (int r, int g, int b) const {
            return (r<<11)|(g<<5)|b;
        }
    } pack565;

    const uint16_t protTexData[] = { 0 };
    glGenTextures(1, &mProtectedTexName);
    glBindTexture(GL_TEXTURE_2D, mProtectedTexName);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 1, 1, 0,
            GL_RGB, GL_UNSIGNED_SHORT_5_6_5, protTexData);

    //mColorBlindnessCorrection = M;
}

GLES20RenderEngine::~GLES20RenderEngine() {
}


size_t GLES20RenderEngine::getMaxTextureSize() const {
    return mMaxTextureSize;
}

size_t GLES20RenderEngine::getMaxViewportDims() const {
    return
        mMaxViewportDims[0] < mMaxViewportDims[1] ?
            mMaxViewportDims[0] : mMaxViewportDims[1];
}

void GLES20RenderEngine::setViewportAndProjection(
        size_t vpw, size_t vph, Rect sourceCrop, size_t hwh, bool yswap,
        Transform::orientation_flags rotation) {

    size_t l = sourceCrop.left;
    size_t r = sourceCrop.right;

    // In GL, (0, 0) is the bottom-left corner, so flip y coordinates
    size_t t = hwh - sourceCrop.top;
    size_t b = hwh - sourceCrop.bottom;

    mat4 m;
    if (yswap) {
        m = mat4::ortho(l, r, t, b, 0, 1);
    } else {
        m = mat4::ortho(l, r, b, t, 0, 1);
    }

    // Apply custom rotation to the projection.
    float rot90InRadians = 2.0f * static_cast<float>(M_PI) / 4.0f;
    switch (rotation) {
        case Transform::ROT_0:
            break;
        case Transform::ROT_90:
            m = mat4::rotate(rot90InRadians, vec3(0,0,1)) * m;
            break;
        case Transform::ROT_180:
            m = mat4::rotate(rot90InRadians * 2.0f, vec3(0,0,1)) * m;
            break;
        case Transform::ROT_270:
            m = mat4::rotate(rot90InRadians * 3.0f, vec3(0,0,1)) * m;
            break;
        default:
            break;
    }

    glViewport(0, 0, vpw, vph);
    mState.setProjectionMatrix(m);
    mVpWidth = vpw;
    mVpHeight = vph;
}

void GLES20RenderEngine::setupLayerBlending(
    bool premultipliedAlpha, bool opaque, int alpha) {

    mState.setPremultipliedAlpha(premultipliedAlpha);
    mState.setOpaque(opaque);
    mState.setPlaneAlpha(alpha / 255.0f);

    if (alpha < 0xFF || !opaque) {
        glEnable(GL_BLEND);
       // glBlendFunc(premultipliedAlpha ? GL_ONE : GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
       glBlendFuncSeparate(premultipliedAlpha ? GL_ONE : GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

    } else {
        glDisable(GL_BLEND);
    }
}

void GLES20RenderEngine::setupDimLayerBlending(int alpha) {
    mState.setPlaneAlpha(1.0f);
    mState.setPremultipliedAlpha(true);
    mState.setOpaque(false);
    mState.setColor(0, 0, 0, alpha/255.0f);
    mState.disableTexture();

    if (alpha == 0xFF) {
        glDisable(GL_BLEND);
    } else {
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }
}

void GLES20RenderEngine::setupLayerTexturing(const Texture& texture) {
    GLuint target = texture.getTextureTarget();
    glBindTexture(target, texture.getTextureName());
    GLenum filter = GL_NEAREST;
    if (texture.getFiltering()) {
        filter = GL_LINEAR;
    }
    glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, filter);

    mState.setTexture(texture);
}

void GLES20RenderEngine::setupLayerBlackedOut() {
    glBindTexture(GL_TEXTURE_2D, mProtectedTexName);
    Texture texture(Texture::TEXTURE_2D, mProtectedTexName);
    texture.setDimensions(1, 1); // FIXME: we should get that from somewhere
    mState.setTexture(texture);
}

void GLES20RenderEngine::disableTexturing() {
    mState.disableTexture();
}

void GLES20RenderEngine::disableBlending() {
    glDisable(GL_BLEND);
}


void GLES20RenderEngine::bindImageAsFramebuffer(EGLImageKHR image,
        uint32_t* texName, uint32_t* fbName, uint32_t* status) {
    GLuint tname, name;
    // turn our EGLImage into a texture
    glGenTextures(1, &tname);
    glBindTexture(GL_TEXTURE_2D, tname);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, (GLeglImageOES)image);

    // create a Framebuffer Object to render into
    glGenFramebuffers(1, &name);
    glBindFramebuffer(GL_FRAMEBUFFER, name);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tname, 0);

    *status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    *texName = tname;
    *fbName = name;
}

void GLES20RenderEngine::unbindFramebuffer(uint32_t texName, uint32_t fbName) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbName);
    glDeleteTextures(1, &texName);
}

void GLES20RenderEngine::setupFillWithColor(float r, float g, float b, float a) {
    mState.setPlaneAlpha(1.0f);
    mState.setPremultipliedAlpha(true);
    mState.setOpaque(false);
    mState.setColor(r, g, b, a);
    mState.disableTexture();
    glDisable(GL_BLEND);
}

void GLES20RenderEngine::drawMesh(const Mesh& mesh) {

    ProgramCache::getInstance().useProgram(mState);

    if (mesh.getTexCoordsSize()) {
        glEnableVertexAttribArray(Program::texCoords);
        glVertexAttribPointer(Program::texCoords,
                mesh.getTexCoordsSize(),
                GL_FLOAT, GL_FALSE,
                mesh.getByteStride(),
                mesh.getTexCoords());
    }

    glVertexAttribPointer(Program::position,
            mesh.getVertexSize(),
            GL_FLOAT, GL_FALSE,
            mesh.getByteStride(),
            mesh.getPositions());

    glDrawArrays(mesh.getPrimitive(), 0, mesh.getVertexCount());
    
    if (mesh.getTexCoordsSize()) {
        glDisableVertexAttribArray(Program::texCoords);
    }

}

#ifdef ENABLE_STEREO_AND_DEFORM
void GLES20RenderEngine::beginGroup(const mat4& colorTransform,int mode) {
    GLuint tname, name;
    // create the texture
    glGenTextures(1, &tname);
    glBindTexture(GL_TEXTURE_2D, tname);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, mVpWidth, mVpHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);

    // create a Framebuffer Object to render into
    glGenFramebuffers(1, &name);
    glBindFramebuffer(GL_FRAMEBUFFER, name);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tname, 0);
    
    Group group;
    group.texture = tname;
    group.fbo = name;
    group.width = mVpWidth;
    group.height = mVpHeight;
    if(mode > 1)
    {
        group.colorTransform = colorTransform;
    }    
    
    mGroupStack.push(group);
}

void GLES20RenderEngine::endGroup(int mode) {

    const Group group(mGroupStack.top());
    mGroupStack.pop();

    // activate the previous render target
    GLuint fbo = 0;
    if (!mGroupStack.isEmpty()) {
        fbo = mGroupStack.top().fbo;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);

    // set our state
    Texture texture(Texture::TEXTURE_2D, group.texture);
    texture.setDimensions(group.width, group.height);
    glBindTexture(GL_TEXTURE_2D, group.texture);

    mState.setPlaneAlpha(1.0f);
    mState.setPremultipliedAlpha(true);
    mState.setOpaque(false);
    mState.setTexture(texture);
    mState.setColorMatrix(group.colorTransform);

    switch(mode)
    {
        case 1:
            mState.setDeform(true);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            break;
        case 2:
            mState.setDeform(true);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); 
        case 3:
            mState.setColorMatrix(group.colorTransform);
            break;
        default:
            break;
    }
    glDisable(GL_BLEND);

    Mesh mesh(Mesh::TRIANGLE_FAN, 4, 2, 2);
    Mesh::VertexArray<vec2> position(mesh.getPositionArray<vec2>());
    Mesh::VertexArray<vec2> texCoord(mesh.getTexCoordArray<vec2>());
    position[0] = vec2(0, 0);
    position[1] = vec2(group.width, 0);
    position[2] = vec2(group.width, group.height);
    position[3] = vec2(0, group.height);
    texCoord[0] = vec2(0, 0);
    texCoord[1] = vec2(1, 0);
    texCoord[2] = vec2(1, 1);
    texCoord[3] = vec2(0, 1);
    drawMesh(mesh);
    mState.setColorMatrix(mat4());
    // reset color matrix
    switch(mode)
    {
        case 1:
            mState.setDeform(false);
            break;
        case 2:
            mState.setDeform(false);
        case 3:
            mState.setColorMatrix(mat4());
            break;
        default:
            break;
    }
    // free our fbo and texture
    glDeleteFramebuffers(1, &group.fbo);
    glDeleteTextures(1, &group.texture);
}
#else
void GLES20RenderEngine::beginGroup(const mat4& colorTransform) {
    GLuint tname, name;
    // create the texture
    glGenTextures(1, &tname);
    glBindTexture(GL_TEXTURE_2D, tname);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, mVpWidth, mVpHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);

    // create a Framebuffer Object to render into
    glGenFramebuffers(1, &name);
    glBindFramebuffer(GL_FRAMEBUFFER, name);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tname, 0);
    
    Group group;
    group.texture = tname;
    group.fbo = name;
    group.width = mVpWidth;
    group.height = mVpHeight;
    group.colorTransform = colorTransform;
    //if(mode > 1)
    //{
    //   group.colorTransform = colorTransform;
    //}

    mGroupStack.push(group);
}

void GLES20RenderEngine::endGroup() {
    const Group group(mGroupStack.top());
    mGroupStack.pop();

    // activate the previous render target
    GLuint fbo = 0;
    if (!mGroupStack.isEmpty()) {
        fbo = mGroupStack.top().fbo;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    
    // set our state
    Texture texture(Texture::TEXTURE_2D, group.texture);
    texture.setDimensions(group.width, group.height);
    glBindTexture(GL_TEXTURE_2D, group.texture);

    mState.setPlaneAlpha(1.0f);
    mState.setPremultipliedAlpha(true);
    mState.setOpaque(false);
    mState.setTexture(texture);
    mState.setColorMatrix(group.colorTransform);
    /*
    switch(mode)
    {
        case 1:
            mState.setDeform(true);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);            
            break;
        case 2:
            mState.setDeform(true);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);                        
        case 3:
            mState.setColorMatrix(group.colorTransform);
            break;
        default:
            break;
    }
    */
    glDisable(GL_BLEND);

    Mesh mesh(Mesh::TRIANGLE_FAN, 4, 2, 2);
    Mesh::VertexArray<vec2> position(mesh.getPositionArray<vec2>());
    Mesh::VertexArray<vec2> texCoord(mesh.getTexCoordArray<vec2>());
    position[0] = vec2(0, 0);
    position[1] = vec2(group.width, 0);
    position[2] = vec2(group.width, group.height);
    position[3] = vec2(0, group.height);
    texCoord[0] = vec2(0, 0);
    texCoord[1] = vec2(1, 0);
    texCoord[2] = vec2(1, 1);
    texCoord[3] = vec2(0, 1);
    drawMesh(mesh);

    // reset color matrix
    mState.setColorMatrix(mat4());
    /*
    switch(mode)
    {
        case 1:
            mState.setDeform(false);
            break;
        case 2:
            mState.setDeform(false);
        case 3:
            mState.setColorMatrix(mat4());
            break;
        default:
            break;
    }
    */
    // free our fbo and texture
    glDeleteFramebuffers(1, &group.fbo);
    glDeleteTextures(1, &group.texture);
}
#endif

void GLES20RenderEngine::dump(String8& result) {
    RenderEngine::dump(result);
}

// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------

#if defined(__gl_h_)
#error "don't include gl/gl.h in this file"
#endif
