package me.phh.shadertoyscreensaver

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLSurfaceView.Renderer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.dreams.DreamService
import android.util.Log
import org.json.JSONObject
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread


class ShaderRenderer(val glSurfaceView: GLSurfaceView) : Renderer {
    private var program = 0
    private var aPositionLoc = 0
    private var iResolutionLoc = 0
    private var iTimeLoc = 0
    private var startTime: Long = 0
    private val vertexBuffer: FloatBuffer
    private val shaderToy: JSONObject

    fun loadTexture(url: String, wrap: Boolean, mipmap: Boolean, channelId: Int): Int {
        val textureId = intArrayOf(-1, -1)
        GLES20.glGenTextures(2, textureId, 0)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e("PHH", "Error generating texture IDs: $error")
        }

        Log.d("PHH", "Texture id ${textureId[0]} and ${textureId[1]}")

        mHandler.post {
            val conn = URL(url).openConnection() as HttpsURLConnection
            val bytes = conn.inputStream.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            glSurfaceView.queueEvent {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S,
                    if (wrap) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T,
                    if (wrap) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
                )
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

                if (mipmap) {
                    GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                }
                bitmap.recycle()

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + channelId)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
                GLES20.glUniform1i(
                    GLES20.glGetUniformLocation(program, "iChannel${channelId}"),
                    channelId
                )
            }
        }

        return textureId[0]
    }

    val working = listOf(
        "Wt33Wf",
        "mtyGWy",
        "XsXXDn",
        "NslGRN",
        "4ccfRn",
        "MfjyWK",
        "Ml2XRD",
        "MX3fW7",
        "lsl3RH",
        "3ttSzr",
        "ldfyzl",
    )
    val tooSlow = listOf(
        "Ms2SD1",
        "tsScRK",
        "3lsSzf",
        "XfyXRV",
        "ll3SWl",
        "lllSR2",
        "MdXyzX",
        "MdX3zr",

    )
    val broken = listOf(
        "WsSBzh",
        "XXjSRc", // missing support for multiple render pass
        "XslGRr" // Unsupported volume texture
        // Some unsupported "Common" functions
    )
    var inputTextureIds = mutableListOf<Int>()

    init {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTICES)
        vertexBuffer.position(0)

        val conn = URL("https://www.shadertoy.com/api/v1/shaders/ldfyzl?key=").openConnection() as HttpsURLConnection
        val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
        Log.d("PHH", "Received json $jsonString")

        shaderToy = JSONObject(jsonString).getJSONObject("Shader")
    }

    val mHandler = Handler(HandlerThread("TextureLoader").apply { start() }.looper)
    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        startTime = SystemClock.elapsedRealtime()
        val renderpass = shaderToy.getJSONArray("renderpass").getJSONObject(0)

        var inputCode = ""
        val inputsJ = renderpass.getJSONArray("inputs")
        for(i in 0 until inputsJ.length()) {
            val input = inputsJ.getJSONObject(i)
            if (input.getString("ctype") == "texture") {
                inputCode += "uniform sampler2D iChannel${i};\n"
            } else if(input.getString("ctype") == "cubemap") {
                inputCode += "uniform samplerCube iChannel${i};\n"
            } else if(input.getString("ctype") == "volume") {
                inputCode += "uniform sampler3D iChannel${i};\n"
            } else {
                Log.e("ShaderError", "Unknown input type ${input.getString("ctype")}")
            }

            val url = input.getString("src")
            val mipmap = input.getJSONObject("sampler").getString("filter") == "mipmap"
            val wrap = input.getJSONObject("sampler").getString("wrap") == "repeat"
            loadTexture("https://www.shadertoy.com" + url, wrap, mipmap, i)
        }

        val versionPrefix =
            """#version 300 es
            #ifdef GL_ES
            precision highp float;
            precision highp int;
            precision mediump sampler3D;
            #endif
            """.trimIndent()

        val vertexShaderCode =
            """$versionPrefix
            in vec4 aPosition;
            void main() {
                gl_Position = aPosition; 
            }
            """.trimIndent()

        val shaderCode = renderpass.getString("code")

        val fragmentShaderCode =
"""$versionPrefix
#define HW_PERFORMANCE 0
out vec4 fragColor;
uniform vec3 iResolution;
uniform float iTime;
uniform float iTimeDelta;
uniform int iFrame;
uniform float iChannelTime[4];
uniform vec3 iChannelResolution[4];
uniform vec4 iMouse;
uniform vec4 iDate;
uniform float iSampleRate;

$inputCode
$shaderCode

void main(void) {
    mainImage(fragColor, gl_FragCoord.xy);
}
"""

        for(line in fragmentShaderCode.split("\n")) {
            Log.d("PHH", "Shader line: $line")
        }

        // Compile shaders (replace with your Shadertoy code)
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        checkProgramLinkError(program)

        // Get uniform/attribute locations
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        iResolutionLoc = GLES20.glGetUniformLocation(program, "iResolution")
        iTimeLoc = GLES20.glGetUniformLocation(program, "iTime")
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == GLES20.GL_FALSE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            Log.e("ShaderError", "Shader compilation failed:\n$log")
        }
        return shader
    }

    private fun checkProgramLinkError(program: Int) {
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == GLES20.GL_FALSE) {
            val log = GLES20.glGetProgramInfoLog(program)
            Log.e("ShaderError", "Program linking failed:\n$log")
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(iResolutionLoc, width.toFloat(), height.toFloat(), 1.0f)
    }

    override fun onDrawFrame(gl: GL10) {
        val time = (SystemClock.elapsedRealtime() - startTime) / 1000.0f
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(iTimeLoc, time)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionLoc)
    }

    companion object {
        // Full-screen quad vertices
        private val VERTICES = floatArrayOf(
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f
        )
    }
}


class MainDreamService : DreamService() {
    private var glSurfaceView: GLSurfaceView? = null

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        glSurfaceView?.onResume()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        glSurfaceView?.onPause()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // Disable user interaction


        val glsf = GLSurfaceView(this)
        thread {
            val renderer = ShaderRenderer(glsf)
            mainExecutor.execute {


                glsf.getHolder().setFixedSize(1920/4, 1080/4)

                glsf.setEGLContextClientVersion(2)
                glsf.setRenderer(renderer)
                setContentView(glsf)
                glSurfaceView = glsf
            }
        }
    }
}
