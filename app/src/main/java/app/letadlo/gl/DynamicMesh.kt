package app.letadlo.gl

import android.opengl.GLES30
import app.letadlo.game.DynamicBatch
import app.letadlo.game.MeshBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** GPU síť, která se každý snímek přepíše obsahem [DynamicBatch]. */
class DynamicMesh {
    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    private var vbuf: FloatBuffer = alloc(4096 * MeshBuilder.STRIDE).asFloatBuffer()
    private var ibuf: IntBuffer = alloc(8192).asIntBuffer()
    private var indexCount = 0

    private fun alloc(n: Int): ByteBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())

    fun init() {
        val ids = IntArray(3)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(2, ids, 1)
        vao = ids[0]; vbo = ids[1]; ibo = ids[2]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val stride = MeshBuilder.STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glBindVertexArray(0)
        indexCount = 0
    }

    fun upload(b: DynamicBatch) {
        indexCount = b.indexCount
        if (indexCount == 0) return
        val floats = b.vertexCount * MeshBuilder.STRIDE
        if (vbuf.capacity() < floats) vbuf = alloc(floats + floats / 2).asFloatBuffer()
        if (ibuf.capacity() < indexCount) ibuf = alloc(indexCount + indexCount / 2).asIntBuffer()
        vbuf.clear()
        vbuf.put(b.vertices, 0, floats).position(0)
        ibuf.clear()
        ibuf.put(b.indices, 0, indexCount).position(0)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * 4, vbuf, GLES30.GL_STREAM_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexCount * 4, ibuf, GLES30.GL_STREAM_DRAW)
    }

    fun draw() {
        if (indexCount == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
    }
}
