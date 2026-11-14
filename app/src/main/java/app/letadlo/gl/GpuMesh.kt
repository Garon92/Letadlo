package app.letadlo.gl

import android.opengl.GLES30
import app.letadlo.game.MeshBuilder
import app.letadlo.game.MeshData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Síť nahraná na GPU (VAO + VBO + IBO). */
class GpuMesh(data: MeshData) {

    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    val indexCount: Int = data.indexCount

    init {
        if (indexCount > 0) {
            val ids = IntArray(3)
            GLES30.glGenVertexArrays(1, ids, 0)
            GLES30.glGenBuffers(2, ids, 1)
            vao = ids[0]
            vbo = ids[1]
            ibo = ids[2]
            GLES30.glBindVertexArray(vao)

            val floats = data.vertexCount * MeshBuilder.STRIDE
            val vb = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(data.vertices, 0, floats).position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * 4, vb, GLES30.GL_STATIC_DRAW)

            val ib = ByteBuffer.allocateDirect(indexCount * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            ib.put(data.indices, 0, indexCount).position(0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexCount * 4, ib, GLES30.GL_STATIC_DRAW)

            val stride = MeshBuilder.STRIDE * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 24)
            GLES30.glBindVertexArray(0)
        }
    }

    fun draw() {
        if (indexCount == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    fun release() {
        if (indexCount == 0) return
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        vao = 0
    }
}
