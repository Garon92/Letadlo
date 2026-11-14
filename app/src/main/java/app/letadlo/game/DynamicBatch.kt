package app.letadlo.game

import kotlin.math.cos
import kotlin.math.sin

/** Rotační matice 3×3 (po řádcích) R = Ry(yaw) · Rx(pitch) · Rz(roll). */
object Rot {
    fun ypr(yaw: Float, pitch: Float, roll: Float, out: FloatArray) {
        val ca = cos(yaw); val sa = sin(yaw)
        val cb = cos(pitch); val sb = sin(pitch)
        val cc = cos(roll); val sc = sin(roll)
        out[0] = ca * cc + sa * sb * sc
        out[1] = -ca * sc + sa * sb * cc
        out[2] = sa * cb
        out[3] = cb * sc
        out[4] = cb * cc
        out[5] = -sb
        out[6] = -sa * cc + ca * sb * sc
        out[7] = sa * sc + ca * sb * cc
        out[8] = ca * cb
    }

    /** Natočení (yaw) tak, aby šablona s přídí v -Z mířila ve směru (dx, dz). */
    fun yawFor(dx: Float, dz: Float): Float = kotlin.math.atan2(-dx, -dz)
}

/**
 * Každý snímek znovu poskládaná geometrie pohyblivých věcí (auta, lidé, ptáci,
 * lodě, lopatky elektráren). Kreslí se jedním voláním.
 */
class DynamicBatch(initialVertices: Int = 8192) {
    var vertices = FloatArray(initialVertices * MeshBuilder.STRIDE); private set
    var indices = IntArray(initialVertices * 2); private set
    var vertexCount = 0; private set
    var indexCount = 0; private set
    private val m = FloatArray(9)

    fun clear() {
        vertexCount = 0
        indexCount = 0
    }

    private fun ensure(v: Int, i: Int) {
        val needV = (vertexCount + v) * MeshBuilder.STRIDE
        if (needV > vertices.size) vertices = vertices.copyOf(maxOf(needV, vertices.size * 2))
        if (indexCount + i > indices.size) indices = indices.copyOf(maxOf(indexCount + i, indices.size * 2))
    }

    fun add(t: MeshData, x: Float, y: Float, z: Float, yaw: Float, pitch: Float = 0f, roll: Float = 0f, scale: Float = 1f) {
        Rot.ypr(yaw, pitch, roll, m)
        addMatrix(t, m, x, y, z, scale)
    }

    fun addMatrix(t: MeshData, r: FloatArray, x: Float, y: Float, z: Float, scale: Float = 1f) {
        if (t.indexCount == 0) return
        ensure(t.vertexCount, t.indexCount)
        val base = vertexCount
        val src = t.vertices
        var o = vertexCount * MeshBuilder.STRIDE
        for (i in 0 until t.vertexCount) {
            val s = i * MeshBuilder.STRIDE
            val px = src[s] * scale
            val py = src[s + 1] * scale
            val pz = src[s + 2] * scale
            val nx = src[s + 3]
            val ny = src[s + 4]
            val nz = src[s + 5]
            vertices[o] = r[0] * px + r[1] * py + r[2] * pz + x
            vertices[o + 1] = r[3] * px + r[4] * py + r[5] * pz + y
            vertices[o + 2] = r[6] * px + r[7] * py + r[8] * pz + z
            vertices[o + 3] = r[0] * nx + r[1] * ny + r[2] * nz
            vertices[o + 4] = r[3] * nx + r[4] * ny + r[5] * nz
            vertices[o + 5] = r[6] * nx + r[7] * ny + r[8] * nz
            vertices[o + 6] = src[s + 6]
            vertices[o + 7] = src[s + 7]
            vertices[o + 8] = src[s + 8]
            vertices[o + 9] = src[s + 9]
            o += MeshBuilder.STRIDE
        }
        vertexCount += t.vertexCount
        val ti = t.indices
        for (i in 0 until t.indexCount) indices[indexCount + i] = ti[i] + base
        indexCount += t.indexCount
    }

    private fun vtx(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float): Int {
        val o = vertexCount * MeshBuilder.STRIDE
        vertices[o] = x; vertices[o + 1] = y; vertices[o + 2] = z
        vertices[o + 3] = 0f; vertices[o + 4] = 1f; vertices[o + 5] = 0f
        vertices[o + 6] = r; vertices[o + 7] = g; vertices[o + 8] = b; vertices[o + 9] = 1f
        return vertexCount++
    }

    /** Pták: tělo a dvě mávající křídla (tři trojúhelníky). */
    fun addBird(x: Float, y: Float, z: Float, yaw: Float, flap: Float, span: Float, r: Float, g: Float, b: Float) {
        ensure(9, 9)
        val ca = cos(yaw); val sa = sin(yaw)
        val half = span * 0.5f
        val fy = sin(flap) * span * 0.28f
        fun px(lx: Float, lz: Float) = x + ca * lx + sa * lz
        fun pz(lx: Float, lz: Float) = z - sa * lx + ca * lz
        val l = span * 0.2f
        // tělo
        var a = vtx(px(0f, -l), y, pz(0f, -l), r * 0.8f, g * 0.8f, b * 0.8f)
        var bb = vtx(px(0.06f * span, l), y, pz(0.06f * span, l), r * 0.8f, g * 0.8f, b * 0.8f)
        var c = vtx(px(-0.06f * span, l), y, pz(-0.06f * span, l), r * 0.8f, g * 0.8f, b * 0.8f)
        tri(a, bb, c)
        // křídla
        for (side in -1..1 step 2) {
            val s = side.toFloat()
            a = vtx(px(s * 0.05f * span, -0.08f * span), y, pz(s * 0.05f * span, -0.08f * span), r, g, b)
            bb = vtx(px(s * 0.05f * span, 0.08f * span), y, pz(s * 0.05f * span, 0.08f * span), r, g, b)
            c = vtx(px(s * half, 0.06f * span), y + fy, pz(s * half, 0.06f * span), r * 0.9f, g * 0.9f, b * 0.9f)
            tri(a, bb, c)
        }
    }

    private fun tri(a: Int, b: Int, c: Int) {
        indices[indexCount++] = a
        indices[indexCount++] = b
        indices[indexCount++] = c
    }
}
