package com.khumasi.game.render

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

internal object GlUtil {
    private const val TAG = "KhumasiGL"

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    fun shortBuffer(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(data); position(0)
        }

    fun checkError(where: String) {
        var err = GLES30.glGetError()
        while (err != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "GL error 0x${Integer.toHexString(err)} at $where")
            err = GLES30.glGetError()
        }
    }
}

/** A linked shader program with cached uniform locations. */
internal class ShaderProgram(name: String, vertexSrc: String, fragmentSrc: String) {
    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc, name)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc, name)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glBindAttribLocation(id, Mesh.ATTR_POSITION, "aPos")
        GLES30.glBindAttribLocation(id, Mesh.ATTR_NORMAL, "aNormal")
        GLES30.glBindAttribLocation(id, Mesh.ATTR_COLOR, "aColor")
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw IllegalStateException("Link failed ($name): $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun set1f(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun set1i(name: String, v: Int) = GLES30.glUniform1i(loc(name), v)
    fun set2f(name: String, a: Float, b: Float) = GLES30.glUniform2f(loc(name), a, b)
    fun set3f(name: String, a: Float, b: Float, c: Float) = GLES30.glUniform3f(loc(name), a, b, c)
    fun set3f(name: String, v: FloatArray) = GLES30.glUniform3f(loc(name), v[0], v[1], v[2])
    fun set4f(name: String, a: Float, b: Float, c: Float, d: Float) = GLES30.glUniform4f(loc(name), a, b, c, d)
    fun setMat4(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(name), 1, false, m, 0)
    fun setMat3(name: String, m: FloatArray) = GLES30.glUniformMatrix3fv(loc(name), 1, false, m, 0)

    private fun compile(type: Int, src: String, name: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Compile failed ($name ${if (type == GLES30.GL_VERTEX_SHADER) "vs" else "fs"}): $log")
        }
        return shader
    }
}
