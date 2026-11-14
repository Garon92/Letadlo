package app.letadlo.gl

object Shaders {

    const val MODE_TERRAIN = 0
    const val MODE_OBJECTS = 1
    const val MODE_PLANE = 2
    const val MODE_WATER = 3
    const val MODE_SHADOW = 4
    const val MODE_EFFECT = 5

    val WORLD_VS = """
        #version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec4 aColor;
        uniform mat4 uViewProj;
        uniform mat4 uModel;
        out vec3 vWorld;
        out vec3 vNormal;
        out vec4 vColor;
        void main() {
            vec4 w = uModel * vec4(aPos, 1.0);
            vWorld = w.xyz;
            vNormal = mat3(uModel) * aNormal;
            vColor = aColor;
            gl_Position = uViewProj * w;
        }
    """.trimIndent()

    val WORLD_FS = """
        #version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec4 vColor;
        uniform int uMode;
        uniform vec3 uSunDir;
        uniform vec3 uCamPos;
        uniform vec3 uFogColor;
        uniform vec2 uFog;
        uniform vec4 uRunway;
        uniform float uRunwayY;
        uniform vec4 uCity;
        uniform vec4 uCityGrid;
        uniform vec4 uTint;
        uniform float uTime;
        out vec4 fragColor;

        float hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        vec3 runwayColor(vec2 p, float dist) {
            float halfW = 0.5 * (uRunway.z - uRunway.x);
            float halfL = 0.5 * (uRunway.w - uRunway.y);
            float u = p.x - 0.5 * (uRunway.x + uRunway.z);
            float v = p.y - 0.5 * (uRunway.y + uRunway.w);
            vec3 col = vec3(0.25, 0.255, 0.26) * (0.93 + 0.07 * hash12(floor(p * 1.5)));
            float fade = 1.0 - smoothstep(500.0, 1800.0, dist);
            float paint = step(halfW - 1.4, abs(u)) * step(abs(u), halfW - 0.5);
            paint = max(paint, step(abs(u), 0.45) * step(fract(v / 50.0), 0.55) * step(abs(v), halfL - 80.0));
            float thr = step(halfL - 62.0, abs(v)) * step(abs(v), halfL - 14.0);
            paint = max(paint, thr * step(fract((u + halfW) / 3.6), 0.55) * step(abs(u), halfW - 3.0));
            float tz = step(halfL - 280.0, abs(v)) * step(abs(v), halfL - 210.0);
            paint = max(paint, tz * step(4.0, abs(u)) * step(abs(u), 12.0));
            return mix(col, vec3(0.93), paint * mix(0.45, 1.0, fade));
        }

        vec3 cityColor(vec2 p, float dist) {
            vec2 l = mod(p - uCity.xy, uCityGrid.x);
            float road = uCityGrid.y;
            float isRoad = max(step(l.x, road), step(l.y, road));
            vec3 asphalt = vec3(0.21, 0.215, 0.22);
            vec3 walk = vec3(0.60, 0.59, 0.56);
            if (distance(p, uCity.xy) > uCityGrid.z) {
                // předměstí: trávníky a zahrady místo dlažby
                vec2 lot = floor((p - uCity.xy) / 40.0);
                walk = mix(vec3(0.36, 0.52, 0.23), vec3(0.30, 0.46, 0.20), hash12(lot));
            }
            float fade = 1.0 - smoothstep(300.0, 1300.0, dist);
            float lineX = step(abs(l.x - road * 0.5), 0.22) * step(fract(p.y / 12.0), 0.5) * step(road, l.y);
            float lineZ = step(abs(l.y - road * 0.5), 0.22) * step(fract(p.x / 12.0), 0.5) * step(road, l.x);
            vec3 roadCol = mix(asphalt, vec3(0.92, 0.9, 0.78), max(lineX, lineZ) * fade);
            float curb = (1.0 - isRoad) * max(step(l.x, road + 1.2), step(l.y, road + 1.2));
            vec3 c = mix(walk, roadCol, isRoad);
            return mix(c, walk * 0.78, curb);
        }

        vec3 fieldColor(vec2 p, float dist, vec3 grass) {
            vec2 q = vec2(0.83 * p.x - 0.55 * p.y, 0.55 * p.x + 0.83 * p.y);
            vec2 size = vec2(170.0, 115.0);
            vec2 id = floor(q / size);
            vec2 f = fract(q / size);
            float h = hash12(id + 17.0);
            vec3 c;
            if (h < 0.22) c = vec3(0.80, 0.70, 0.36);
            else if (h < 0.42) c = vec3(0.42, 0.58, 0.24);
            else if (h < 0.58) c = vec3(0.52, 0.40, 0.25);
            else if (h < 0.74) c = vec3(0.33, 0.50, 0.20);
            else if (h < 0.86) c = vec3(0.72, 0.74, 0.30);
            else c = grass;
            float fade = 1.0 - smoothstep(250.0, 900.0, dist);
            float rows = 0.9 + 0.1 * sin(q.x * (h > 0.42 && h < 0.58 ? 1.7 : 0.9));
            c *= mix(1.0, rows, fade);
            vec2 e = min(f, 1.0 - f) * size;
            float edge = 1.0 - step(2.0, min(e.x, e.y));
            return mix(c, vec3(0.28, 0.40, 0.19), edge * 0.7);
        }

        vec3 windowsColor(vec3 base, vec3 p, vec3 n, float dist) {
            if (abs(n.y) > 0.5) return base;
            float u = abs(n.x) > abs(n.z) ? p.z : p.x;
            float wx = fract(u / 3.2);
            float wy = fract(p.y / 3.6);
            float win = step(0.18, wx) * step(wx, 0.82) * step(0.3, wy) * step(wy, 0.82);
            float r = hash12(vec2(floor(u / 3.2), floor(p.y / 3.6)) + floor(p.xz * 0.013));
            vec3 glass = mix(vec3(0.15, 0.21, 0.29), vec3(0.50, 0.62, 0.74), r * 0.7);
            vec3 detailed = mix(base, glass, win);
            vec3 avg = mix(base, vec3(0.30, 0.38, 0.47), 0.32);
            return mix(detailed, avg, smoothstep(500.0, 1800.0, dist));
        }

        vec3 applyFog(vec3 c, float dist) {
            return mix(c, uFogColor, smoothstep(uFog.x, uFog.y, dist));
        }

        void main() {
            float dist = length(vWorld - uCamPos);
            if (uMode == 4) {
                fragColor = vec4(0.0, 0.0, 0.0, uTint.a);
                return;
            }
            if (uMode == 5) {
                fragColor = vec4(applyFog(vColor.rgb * uTint.rgb, dist), uTint.a);
                return;
            }
            vec3 v = normalize(uCamPos - vWorld);
            if (uMode == 3) {
                vec2 p = vWorld.xz;
                float amp = 1.0 - smoothstep(150.0, 1400.0, dist);
                vec3 wn = normalize(vec3(
                    amp * (0.05 * sin(p.x * 0.09 + uTime * 1.3) + 0.035 * sin(p.y * 0.13 + p.x * 0.05 - uTime * 1.7)),
                    1.0,
                    amp * (0.05 * cos(p.y * 0.08 + uTime * 1.1) + 0.035 * cos(p.x * 0.11 - uTime * 1.9))));
                float fres = pow(1.0 - max(dot(wn, v), 0.0), 3.0);
                vec3 col = mix(vec3(0.06, 0.21, 0.31), vec3(0.62, 0.76, 0.90), 0.2 + 0.7 * fres);
                float spec = pow(max(dot(reflect(-uSunDir, wn), v), 0.0), 90.0);
                col += vec3(1.0, 0.95, 0.8) * spec * 0.9;
                fragColor = vec4(applyFog(col, dist), 1.0);
                return;
            }

            vec3 n = normalize(vNormal);
            vec3 base = vColor.rgb;
            if (uMode == 0) {
                if (vWorld.x > uRunway.x && vWorld.x < uRunway.z && vWorld.z > uRunway.y && vWorld.z < uRunway.w &&
                    abs(vWorld.y - uRunwayY) < 0.6) {
                    base = runwayColor(vWorld.xz, dist);
                } else if (uCity.z > 0.0 && abs(vWorld.y - uCity.w) < 0.6 && distance(vWorld.xz, uCity.xy) < uCity.z + 40.0) {
                    base = cityColor(vWorld.xz, dist);
                } else {
                    if (vColor.a < 0.5) base = fieldColor(vWorld.xz, dist, base);
                    float grain = hash12(floor(vWorld.xz * 0.5));
                    base *= mix(0.93 + 0.07 * grain, 1.0, smoothstep(150.0, 600.0, dist));
                }
            } else if (uMode == 1 && vColor.a < 0.9) {
                base = windowsColor(base, vWorld, n, dist);
            }

            float diff = max(dot(n, uSunDir), 0.0);
            float hemi = 0.5 + 0.5 * n.y;
            vec3 ambient = mix(vec3(0.30, 0.29, 0.27), vec3(0.50, 0.58, 0.70), hemi);
            vec3 lit = base * (ambient + vec3(1.0, 0.96, 0.88) * diff * 0.78);
            if (uMode == 2) {
                float spec = pow(max(dot(reflect(-uSunDir, n), v), 0.0), 28.0);
                lit += vec3(1.0) * spec * 0.35;
            }
            fragColor = vec4(applyFog(lit, dist), 1.0);
        }
    """.trimIndent()

    val SKY_VS = """
        #version 300 es
        out vec2 vNdc;
        void main() {
            vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
            vNdc = p;
            gl_Position = vec4(p, 1.0, 1.0);
        }
    """.trimIndent()

    val SKY_FS = """
        #version 300 es
        precision highp float;
        in vec2 vNdc;
        uniform mat4 uInvViewProj;
        uniform vec3 uSunDir;
        uniform vec3 uFogColor;
        out vec4 fragColor;
        void main() {
            vec4 w = uInvViewProj * vec4(vNdc, 1.0, 1.0);
            vec3 dir = normalize(w.xyz / w.w);
            float h = clamp(dir.y, 0.0, 1.0);
            vec3 zenith = vec3(0.22, 0.45, 0.80);
            vec3 col = mix(uFogColor, zenith, pow(h, 0.5));
            float s = max(dot(dir, uSunDir), 0.0);
            col += vec3(1.0, 0.92, 0.75) * (pow(s, 700.0) * 1.6 + pow(s, 10.0) * 0.16);
            fragColor = vec4(col, 1.0);
        }
    """.trimIndent()
}
