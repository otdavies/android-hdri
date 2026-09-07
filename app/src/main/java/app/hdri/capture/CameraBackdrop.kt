package app.hdri.capture

import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import app.hdri.core.PreviewTexture
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class CameraBackdrop {
    private fun buffer(data: FloatArray) =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    private val vertices = buffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv = buffer(FloatArray(8))
    private var program = 0
    var texture = 0
        private set

    fun create() {
        val names = IntArray(1)
        glGenTextures(1, names, 0)
        texture = names[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        fun shader(type: Int, code: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, code)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { glGetShaderInfoLog(s) }
            return s
        }
        val v =
            shader(
                GL_VERTEX_SHADER,
                "attribute vec2 p; attribute vec2 t; varying vec2 uv; void main(){ gl_Position=vec4(p,0.,1.); uv=t; }",
            )
        val f =
            shader(
                GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 uv; void main(){gl_FragColor=texture2D(image,uv);}",
            )
        program = glCreateProgram()
        glAttachShader(program, v)
        glAttachShader(program, f)
        glLinkProgram(program)
        glDeleteShader(v)
        glDeleteShader(f)
    }

    fun drawNative(coordinates: FloatArray, transform: FloatArray) {
        val mapping = PreviewTexture(transform)
        uv.position(0)
        for (i in 0 until 4) {
            val point = mapping.sample(coordinates[i * 2], coordinates[i * 2 + 1])
            uv.put(point.first)
            uv.put(point.second)
        }
        uv.position(0)
        draw(null)
    }

    fun draw(frame: Frame?) {
        if (frame != null) {
            vertices.position(0)
            uv.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                vertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                uv,
            )
        }
        glDisable(GL_DEPTH_TEST)
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        glUniform1i(glGetUniformLocation(program, "image"), 0)
        val p = glGetAttribLocation(program, "p")
        val t = glGetAttribLocation(program, "t")
        vertices.position(0)
        uv.position(0)
        glEnableVertexAttribArray(p)
        glEnableVertexAttribArray(t)
        glVertexAttribPointer(p, 2, GL_FLOAT, false, 0, vertices)
        glVertexAttribPointer(t, 2, GL_FLOAT, false, 0, uv)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(p)
        glDisableVertexAttribArray(t)
    }
}
