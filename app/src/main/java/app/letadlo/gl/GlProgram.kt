package app.letadlo.gl

import android.opengl.GLES30

class GlProgram(vertexSource: String, fragmentSource: String) {

    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw RuntimeException("Chyba linkování shaderu: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Chyba kompilace shaderu: $log")
        }
        return shader
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun set(name: String, v: Int) = GLES30.glUniform1i(loc(name), v)
    fun set(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun set(name: String, x: Float, y: Float) = GLES30.glUniform2f(loc(name), x, y)
    fun set(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc(name), x, y, z)
    fun set(name: String, x: Float, y: Float, z: Float, w: Float) = GLES30.glUniform4f(loc(name), x, y, z, w)
    fun setMatrix(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(name), 1, false, m, 0)
}
