package app.hdri.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.MotionEvent
import app.hdri.core.Q
import app.hdri.core.V3
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SphereViewer(context: Context, private val file: File, private val ready: () -> Unit) :
    GLSurfaceView(context), GLSurfaceView.Renderer {
    private var shader = 0
    private var texture = 0
    private var aspect = 1f
    @Volatile private var yaw = 0.0
    @Volatile private var pitch = 0.0
    private var lastX = 0f
    private var lastY = 0f
    private val vertices =
        ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription = "Photosphere viewer. Drag to look around."
    }

    fun look(dx: Double, dy: Double) {
        yaw += dx
        pitch = (pitch + dy).coerceIn(-89.0, 89.0)
        requestRender()
    }

    fun reset() {
        yaw = 0.0
        pitch = 0.0
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        fun compile(type: Int, source: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, source)
            glCompileShader(s)
            return s
        }
        val v =
            compile(
                GL_VERTEX_SHADER,
                "attribute vec2 p;varying vec2 pos;void main(){pos=p;gl_Position=vec4(p,0.,1.);}",
            )
        val f =
            compile(
                GL_FRAGMENT_SHADER,
                "precision highp float;varying vec2 pos;uniform sampler2D tex;uniform mat3 rot;uniform float aspect;void main(){vec3 d=normalize(rot*vec3(pos.x*aspect*.7,pos.y*.7,-1.));vec2 uv=vec2(atan(d.x,-d.z)/6.2831853+.5,.5-asin(clamp(d.y,-1.,1.))/3.14159265);gl_FragColor=texture2D(tex,uv);}",
            )
        shader = glCreateProgram()
        glAttachShader(shader, v)
        glAttachShader(shader, f)
        glLinkProgram(shader)
        glDeleteShader(v)
        glDeleteShader(f)
        val names = IntArray(1)
        glGenTextures(1, names, 0)
        texture = names[0]
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap != null) {
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }
        post { ready() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        aspect = width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT)
        glUseProgram(shader)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        glUniform1i(glGetUniformLocation(shader, "tex"), 0)
        val q = Q.look(yaw, pitch)
        val axes =
            listOf(V3(1.0, 0.0, 0.0), V3(0.0, 1.0, 0.0), V3(0.0, 0.0, 1.0)).map { q.rotate(it) }
        val matrix =
            axes.flatMap { listOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }.toFloatArray()
        glUniformMatrix3fv(glGetUniformLocation(shader, "rot"), 1, false, matrix, 0)
        glUniform1f(glGetUniformLocation(shader, "aspect"), aspect)
        val p = glGetAttribLocation(shader, "p")
        vertices.position(0)
        glEnableVertexAttribArray(p)
        glVertexAttribPointer(p, 2, GL_FLOAT, false, 0, vertices)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(p)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                look(-(event.x - lastX) * .14, (event.y - lastY) * .14)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
