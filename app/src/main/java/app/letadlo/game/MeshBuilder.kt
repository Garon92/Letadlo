package app.letadlo.game

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Data sítě připravená k nahrání na GPU: pozice(3), normála(3), barva(4). */
class MeshData(val vertices: FloatArray, val vertexCount: Int, val indices: IntArray, val indexCount: Int) {
    val isEmpty: Boolean get() = indexCount == 0
}

class MeshBuilder(initialVertices: Int = 256) {
    companion object {
        const val STRIDE = 10
    }

    private var v = FloatArray(initialVertices * STRIDE)
    private var idx = IntArray(initialVertices * 2)
    var vertexCount = 0; private set
    var indexCount = 0; private set

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float, a: Float = 1f): Int {
        if ((vertexCount + 1) * STRIDE > v.size) v = v.copyOf(v.size * 2)
        val o = vertexCount * STRIDE
        v[o] = x; v[o + 1] = y; v[o + 2] = z
        v[o + 3] = nx; v[o + 4] = ny; v[o + 5] = nz
        v[o + 6] = r; v[o + 7] = g; v[o + 8] = b; v[o + 9] = a
        return vertexCount++
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[indexCount++] = a
        idx[indexCount++] = b
        idx[indexCount++] = c
    }

    /** Plochý čtyřúhelník p0-p1-p2-p3 (proti směru hodinek z vnější strany). */
    fun quad(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        r: Float, g: Float, b: Float, a: Float = 1f,
    ) {
        val ux = x1 - x0; val uy = y1 - y0; val uz = z1 - z0
        val wx = x3 - x0; val wy = y3 - y0; val wz = z3 - z0
        var nx = uy * wz - uz * wy
        var ny = uz * wx - ux * wz
        var nz = ux * wy - uy * wx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 1e-6f) { nx /= len; ny /= len; nz /= len } else { nx = 0f; ny = 1f; nz = 0f }
        val i0 = vertex(x0, y0, z0, nx, ny, nz, r, g, b, a)
        val i1 = vertex(x1, y1, z1, nx, ny, nz, r, g, b, a)
        val i2 = vertex(x2, y2, z2, nx, ny, nz, r, g, b, a)
        val i3 = vertex(x3, y3, z3, nx, ny, nz, r, g, b, a)
        tri(i0, i1, i2)
        tri(i0, i2, i3)
    }

    fun triangle(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float = 1f,
    ) {
        val ux = x1 - x0; val uy = y1 - y0; val uz = z1 - z0
        val wx = x2 - x0; val wy = y2 - y0; val wz = z2 - z0
        var nx = uy * wz - uz * wy
        var ny = uz * wx - ux * wz
        var nz = ux * wy - uy * wx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 1e-6f) { nx /= len; ny /= len; nz /= len } else { nx = 0f; ny = 1f; nz = 0f }
        val i0 = vertex(x0, y0, z0, nx, ny, nz, r, g, b, a)
        val i1 = vertex(x1, y1, z1, nx, ny, nz, r, g, b, a)
        val i2 = vertex(x2, y2, z2, nx, ny, nz, r, g, b, a)
        tri(i0, i1, i2)
    }

    /** Osově zarovnaný kvádr. */
    fun box(
        minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float,
        r: Float, g: Float, b: Float, a: Float = 1f,
        roofR: Float = r, roofG: Float = g, roofB: Float = b, bottom: Boolean = true,
    ) {
        // +Z
        quad(minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a)
        // -Z
        quad(maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a)
        // +X
        quad(maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a)
        // -X
        quad(minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a)
        // střecha
        quad(minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ, roofR, roofG, roofB, 1f)
        if (bottom) quad(minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, 1f)
    }

    /** Kužel/jehlan se špičkou nahoře (koruna jehličnanu). */
    fun cone(cx: Float, baseY: Float, cz: Float, radius: Float, height: Float, sides: Int, r: Float, g: Float, b: Float) {
        val topY = baseY + height
        for (s in 0 until sides) {
            val a0 = (s * 2.0 * Math.PI / sides).toFloat()
            val a1 = ((s + 1) * 2.0 * Math.PI / sides).toFloat()
            val x0 = cx + cos(a0) * radius; val z0 = cz + sin(a0) * radius
            val x1 = cx + cos(a1) * radius; val z1 = cz + sin(a1) * radius
            triangle(x1, baseY, z1, x0, baseY, z0, cx, topY, cz, r, g, b)
        }
    }

    /** Hrubá „koule" – dvojitý jehlan (koruna listnáče, ohnivá koule). */
    fun blob(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, sides: Int, r: Float, g: Float, b: Float) {
        val top = cy + ry
        val bot = cy - ry * 0.7f
        for (s in 0 until sides) {
            val a0 = (s * 2.0 * Math.PI / sides).toFloat()
            val a1 = ((s + 1) * 2.0 * Math.PI / sides).toFloat()
            val x0 = cx + cos(a0) * rx; val z0 = cz + sin(a0) * rx
            val x1 = cx + cos(a1) * rx; val z1 = cz + sin(a1) * rx
            triangle(x1, cy, z1, x0, cy, z0, cx, top, cz, r, g, b)
            triangle(x0, cy, z0, x1, cy, z1, cx, bot, cz, r * 0.85f, g * 0.85f, b * 0.85f)
        }
    }

    /** Zploštělá koule s rovným spodkem – chuchvalec mraku. */
    fun puff(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, shade: Float) {
        val rings = 3
        val sides = 7
        val maxT = (Math.PI * 0.62).toFloat()
        fun px(t: Float, p: Float) = cx + sin(t) * cos(p) * rx
        fun py(t: Float) = cy + cos(t) * ry
        fun pz(t: Float, p: Float) = cz + sin(t) * sin(p) * rx
        for (ri in 0 until rings) {
            val t0 = maxT * ri / rings
            val t1 = maxT * (ri + 1) / rings
            val c = shade * (1f - 0.07f * ri)
            for (s in 0 until sides) {
                val p0 = (2.0 * Math.PI * s / sides).toFloat()
                val p1 = (2.0 * Math.PI * (s + 1) / sides).toFloat()
                if (ri == 0) {
                    triangle(cx, py(0f), cz, px(t1, p1), py(t1), pz(t1, p1), px(t1, p0), py(t1), pz(t1, p0), c, c, c * 1.01f)
                } else {
                    quad(
                        px(t0, p0), py(t0), pz(t0, p0),
                        px(t0, p1), py(t0), pz(t0, p1),
                        px(t1, p1), py(t1), pz(t1, p1),
                        px(t1, p0), py(t1), pz(t1, p0),
                        c, c, c * 1.01f,
                    )
                }
            }
        }
        val yb = py(maxT)
        val cb = shade * 0.8f
        for (s in 0 until sides) {
            val p0 = (2.0 * Math.PI * s / sides).toFloat()
            val p1 = (2.0 * Math.PI * (s + 1) / sides).toFloat()
            triangle(cx, yb, cz, px(maxT, p0), yb, pz(maxT, p0), px(maxT, p1), yb, pz(maxT, p1), cb, cb, cb * 1.02f)
        }
    }

    fun build(): MeshData = MeshData(v.copyOf(vertexCount * STRIDE), vertexCount, idx.copyOf(indexCount), indexCount)
}
