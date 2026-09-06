package app.hdri.ui

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import app.hdri.core.Q
import app.hdri.core.V3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * On-demand float HDR renderer. The two probes share orientation, exposure and display transform.
 */
internal class SphereViewer(
    context: Context,
    private val environment: LightingEnvironment,
    private val ready: () -> Unit,
    private val failed: (String) -> Unit,
) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private var shader = 0
    private val textures = IntArray(2)
    private val sizes = IntArray(4)
    private var aspect = 1f
    @Volatile private var yaw = 0.0
    @Volatile private var pitch = 0.0
    @Volatile var exposure = 0f
    @Volatile var probes = true
    @Volatile var linear = true
    @Volatile var exposureScale: Float? = null
    @Volatile var background = false
    @Volatile
    var zoomFactor = 1f
        private set

    var zoomChanged: (Float) -> Unit = {}
    private var dragging = false
    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!probes) setViewZoom(zoomFactor * detector.scaleFactor)
                    return true
                }
            },
        )
    private var announced = false
    private var lastX = 0f
    private var lastY = 0f
    private val vertices =
        ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription =
            "HDR lighting viewer. Drag to look around. Pinch to zoom in Explore HDR."
    }

    fun look(dx: Double, dy: Double) {
        yaw = (yaw + dx) % 360.0
        pitch = (pitch + dy).coerceIn(-89.0, 89.0)
        requestRender()
    }

    fun reset() {
        yaw = 0.0
        pitch = 0.0
        setViewZoom(1f)
        requestRender()
    }

    fun setViewZoom(value: Float) {
        if (!value.isFinite()) return
        zoomFactor = value.coerceIn(.5f, 4f)
        zoomChanged(zoomFactor)
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        announced = false
        try {
            fun compile(type: Int, source: String): Int {
                val id = glCreateShader(type)
                glShaderSource(id, source)
                glCompileShader(id)
                val status = IntArray(1)
                glGetShaderiv(id, GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "Lighting shader: ${glGetShaderInfoLog(id)}" }
                return id
            }
            val vertex =
                compile(
                    GL_VERTEX_SHADER,
                    "#version 300 es\nin vec2 p;out vec2 pos;void main(){pos=p;gl_Position=vec4(p,0.,1.);}",
                )
            val fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT)
            shader = glCreateProgram()
            glAttachShader(shader, vertex)
            glAttachShader(shader, fragment)
            glLinkProgram(shader)
            glDeleteShader(vertex)
            glDeleteShader(fragment)
            val status = IntArray(1)
            glGetProgramiv(shader, GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Lighting renderer: ${glGetProgramInfoLog(shader)}" }
            glGenTextures(2, textures, 0)
            val limit = IntArray(1)
            glGetIntegerv(GL_MAX_TEXTURE_SIZE, limit, 0)
            listOf(environment.reflection, environment.diffuse).forEachIndexed { index, original ->
                val map =
                    if (original.width <= limit[0]) original
                    else original.reduced(limit[0], limit[0] / 2)
                sizes[index * 2] = map.width
                sizes[index * 2 + 1] = map.height
                glBindTexture(GL_TEXTURE_2D, textures[index])
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
                // Allocate the GPU texture once, then stream small row blocks. A second
                // 96 MiB full-image buffer can exceed Android's regular app heap at 4K.
                glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGB32F,
                    map.width,
                    map.height,
                    0,
                    GL_RGB,
                    GL_FLOAT,
                    null,
                )
                val blockRows = minOf(64, map.height)
                val data =
                    ByteBuffer.allocateDirect(map.width * blockRows * 12)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                for (y in 0 until map.height step blockRows) {
                    val rows = minOf(blockRows, map.height - y)
                    data.clear()
                    data.put(map.rgb, y * map.width * 3, rows * map.width * 3).flip()
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y, map.width, rows, GL_RGB, GL_FLOAT, data)
                }
            }
            check(glGetError() == GL_NO_ERROR) { "The phone could not upload the HDR textures." }
        } catch (e: Exception) {
            shader = 0
            post { failed(e.message ?: "The lighting view could not start.") }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        glClearColor(.082f, .09f, .098f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        if (shader == 0) return
        glUseProgram(shader)
        for (i in 0..1) {
            glActiveTexture(GL_TEXTURE0 + i)
            glBindTexture(GL_TEXTURE_2D, textures[i])
            glUniform1i(glGetUniformLocation(shader, if (i == 0) "env" else "diffuseMap"), i)
            glUniform2f(
                glGetUniformLocation(shader, if (i == 0) "envSize" else "diffuseSize"),
                sizes[i * 2].toFloat(),
                sizes[i * 2 + 1].toFloat(),
            )
        }
        val q = Q.look(yaw, pitch)
        val matrix =
            listOf(V3(1.0, 0.0, 0.0), V3(0.0, 1.0, 0.0), V3(0.0, 0.0, 1.0))
                .flatMap {
                    val v = q.rotate(it)
                    listOf(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
                }
                .toFloatArray()
        glUniformMatrix3fv(glGetUniformLocation(shader, "rot"), 1, false, matrix, 0)
        glUniform1f(glGetUniformLocation(shader, "aspect"), aspect)
        glUniform1f(
            glGetUniformLocation(shader, "gain"),
            (exposureScale ?: environment.scale) * Math.pow(2.0, exposure.toDouble()).toFloat(),
        )
        glUniform1i(glGetUniformLocation(shader, "probes"), if (probes) 1 else 0)
        glUniform1i(glGetUniformLocation(shader, "linearDisplay"), if (linear) 1 else 0)
        glUniform1i(glGetUniformLocation(shader, "showBackground"), if (background) 1 else 0)
        glUniform1f(glGetUniformLocation(shader, "viewScale"), .7f / zoomFactor)
        val p = glGetAttribLocation(shader, "p")
        vertices.position(0)
        glEnableVertexAttribArray(p)
        glVertexAttribPointer(p, 2, GL_FLOAT, false, 0, vertices)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(p)
        if (!announced) {
            announced = true
            val error = glGetError()
            post { if (error == GL_NO_ERROR) ready() else failed("Lighting draw failed ($error).") }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && event.pointerCount == 1 && !scaleDetector.isInProgress)
                    look(
                        -(event.x - lastX) * .14 / zoomFactor,
                        (event.y - lastY) * .14 / zoomFactor,
                    )
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                dragging = event.pointerCount == 2
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private val FRAGMENT =
            """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
in vec2 pos;out vec4 color;
uniform sampler2D env;uniform sampler2D diffuseMap;
uniform mat3 rot;uniform float aspect;uniform float gain;
uniform vec2 envSize;uniform vec2 diffuseSize;
uniform bool probes;uniform bool linearDisplay;uniform bool showBackground;uniform float viewScale;
vec3 light(sampler2D t,vec2 dims,vec3 d) {
    d=normalize(d);
    vec2 uv=vec2(atan(d.x,-d.z)/6.283185307+.5,.5-asin(clamp(d.y,-1.,1.))/3.141592654);
    vec2 p=uv*dims-.5;vec2 f=fract(p);vec2 corner=(floor(p)+.5)/dims;vec2 stepUV=1./dims;
    return mix(mix(textureLod(t,corner,0.).rgb,textureLod(t,corner+vec2(stepUV.x,0.),0.).rgb,f.x),
               mix(textureLod(t,corner+vec2(0.,stepUV.y),0.).rgb,textureLod(t,corner+stepUV,0.).rgb,f.x),f.y);
}
vec3 display(vec3 radiance) {
    vec3 c=max(radiance*gain,vec3(0.));
    c=linearDisplay?clamp(c,0.,1.):c/(vec3(1.)+c);
    return mix(12.92*c,1.055*pow(c,vec3(1./2.4))-.055,step(vec3(.0031308),c));
}
void main() {
    if(!probes) {color=vec4(display(light(env,envSize,rot*vec3(pos.x*aspect*viewScale,pos.y*viewScale,-1.))),1.);return;}
    vec2 p=vec2(pos.x*aspect,pos.y);
    float radius=min(aspect*.41,.78);bool chrome=pos.x<0.;
    vec2 local=(p-vec2((chrome?-.5:.5)*aspect,0.))/radius;
    float rr=dot(local,local);float aa=max(fwidth(rr),.001);
    vec3 background=vec3(.082,.090,.098);
    if(showBackground) background=display(light(env,envSize,rot*vec3(pos.x*aspect*.7,pos.y*.7,-1.)));
    if(rr>1.+aa) {color=vec4(background,1.);return;}
    vec3 n=normalize(vec3(local,sqrt(max(0.,1.-rr))));
    vec3 radiance=chrome?light(env,envSize,rot*reflect(vec3(0.,0.,-1.),n)):.18*light(diffuseMap,diffuseSize,rot*n);
    color=vec4(mix(display(radiance),background,smoothstep(1.-aa,1.+aa,rr)),1.);
}
"""
    }
}
