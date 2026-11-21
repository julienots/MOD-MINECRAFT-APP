package com.bedrockmodstudio.core.render

/**
 * Programmes GPU en GLSL ES 3.00 : le même texte est accepté par OpenGL ES 3.0 (Android) et WebGL 2,
 * ce qui permet de les compiler et de les essayer dans un navigateur sans téléphone.
 *
 * Disposition des sommets : voir [GpuMesh] (emplacements 0 à 4).
 */
object Shaders {

    private const val VERSION = "#version 300 es\n"

    val MODEL_VS = VERSION + """
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec2 aUv;
        layout(location = 3) in vec4 aColor;
        layout(location = 4) in vec3 aExtra;
        uniform mat4 uModel;
        uniform mat4 uView;
        uniform mat4 uProj;
        uniform mat4 uLightVP;
        out vec3 vWorld;
        out vec3 vNormal;
        out vec2 vUv;
        out vec4 vColor;
        out vec3 vExtra;
        out vec4 vLight;
        void main() {
            vec4 w = uModel * vec4(aPos, 1.0);
            vWorld = w.xyz;
            vNormal = mat3(uModel) * aNormal;
            vUv = aUv;
            vColor = aColor;
            vExtra = aExtra;
            vLight = uLightVP * w;
            gl_Position = uProj * uView * w;
        }
    """.trimIndent()

    /** Éclairage commun : soleil avec ombre douce (PCF 3×3), ciel/sol en lumière ambiante, reflet et contour. */
    private val LIGHTING = """
        uniform highp sampler2DShadow uShadow;
        uniform float uShadowOn;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform vec3 uAmbSky;
        uniform vec3 uAmbGround;
        uniform vec3 uFog;
        uniform vec2 uFogRange;
        uniform vec3 uEye;
        float shadowAt(vec4 lp, float bias) {
            if (uShadowOn < 0.5) return 1.0;
            vec3 p = lp.xyz / lp.w * 0.5 + 0.5;
            if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
            vec2 t = 1.0 / vec2(textureSize(uShadow, 0));
            float s = 0.0;
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) s += texture(uShadow, vec3(p.xy + vec2(float(x), float(y)) * t, p.z - bias));
            return s / 9.0;
        }
        vec3 fogged(vec3 c, vec3 world) {
            float d = distance(world, uEye);
            return mix(c, uFog, smoothstep(uFogRange.x, uFogRange.y, d));
        }
    """.trimIndent()

    val MODEL_FS = VERSION + """
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec2 vUv;
        in vec4 vColor;
        in vec3 vExtra;
        in vec4 vLight;
        uniform sampler2D uTex;
        uniform float uHasTex;
        uniform int uMode;
        uniform float uTime;
        uniform float uAlpha;
        $LIGHTING
        out vec4 fragColor;
        void main() {
            vec4 base = vec4(vColor.rgb, 1.0);
            if (vColor.a > 0.5 && uHasTex > 0.5) {
                base = texture(uTex, vUv);
                if (base.a < 0.1) discard;
            }
            vec3 v = normalize(uEye - vWorld);
            vec3 n = normalize(vNormal);
            if (dot(n, v) < 0.0) n = -n; // faces vues de l'intérieur (plans fins, cubes gonflés négativement)
            if (uMode == 1) base.rgb = vec3(0.80, 0.78, 0.75);
            if (uMode == 3) { fragColor = vec4(n * 0.5 + 0.5, 1.0); return; }
            float ndl = max(dot(n, normalize(uSunDir)), 0.0);
            float bias = max(0.004 * (1.0 - ndl), 0.0008);
            float sh = shadowAt(vLight, bias);
            vec3 amb = mix(uAmbGround, uAmbSky, n.y * 0.5 + 0.5);
            vec3 h = normalize(normalize(uSunDir) + v);
            float spec = pow(max(dot(n, h), 0.0), 32.0) * 0.18 * sh;
            float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.22;
            vec3 c = base.rgb * (amb + uSunColor * ndl * sh) + uSunColor * spec + uAmbSky * rim;
            if (uMode == 2) {
                vec2 q = vExtra.xy;
                vec2 w = fwidth(q) * 1.4;
                vec2 edge = smoothstep(vec2(0.0), w, q) * smoothstep(vec2(0.0), w, 1.0 - q);
                c = mix(c, vec3(0.45, 0.95, 1.0), 1.0 - min(edge.x, edge.y));
            }
            if (vExtra.z > 0.5) {
                float pulse = 0.35 + 0.25 * sin(uTime * 5.0);
                c = mix(c, vec3(1.0, 0.82, 0.40), pulse);
            }
            c = fogged(c, vWorld);
            float a = uMode == 4 ? 0.35 : uAlpha;
            fragColor = vec4(c, a);
        }
    """.trimIndent()

    /** Sol : grand disque avec quadrillage d'un bloc (16 px), ombres reçues et bord fondu. */
    val GROUND_VS = VERSION + """
        layout(location = 0) in vec3 aPos;
        uniform mat4 uView;
        uniform mat4 uProj;
        uniform mat4 uLightVP;
        out vec3 vWorld;
        out vec4 vLight;
        void main() {
            vWorld = aPos;
            vLight = uLightVP * vec4(aPos, 1.0);
            gl_Position = uProj * uView * vec4(aPos, 1.0);
        }
    """.trimIndent()

    val GROUND_FS = VERSION + """
        precision highp float;
        in vec3 vWorld;
        in vec4 vLight;
        uniform vec3 uGround;
        uniform vec3 uGrid;
        uniform vec3 uCenter;
        uniform float uRadius;
        $LIGHTING
        out vec4 fragColor;
        float gridLine(vec2 p, float cell) {
            vec2 g = abs(fract(p / cell - 0.5) - 0.5) / fwidth(p / cell);
            return 1.0 - min(min(g.x, g.y), 1.0);
        }
        void main() {
            float sh = shadowAt(vLight, 0.0015);
            float ndl = max(normalize(uSunDir).y, 0.0);
            vec3 c = uGround * (uAmbSky * 0.9 + uSunColor * ndl * (0.35 + 0.65 * sh));
            float line = max(gridLine(vWorld.xz, 16.0) * 0.55, gridLine(vWorld.xz, 64.0));
            c = mix(c, uGrid * (0.6 + 0.4 * sh), line * 0.6);
            // Axes X (rouge) et Z (bleu) au centre.
            float ax = 1.0 - min(abs(vWorld.z - uCenter.z) / fwidth(vWorld.z), 1.0);
            float az = 1.0 - min(abs(vWorld.x - uCenter.x) / fwidth(vWorld.x), 1.0);
            c = mix(c, vec3(0.85, 0.30, 0.30), ax * 0.8);
            c = mix(c, vec3(0.30, 0.50, 0.95), az * 0.8);
            c = fogged(c, vWorld);
            float r = distance(vWorld.xz, uCenter.xz) / uRadius;
            fragColor = vec4(c, 1.0 - smoothstep(0.6, 1.0, r));
        }
    """.trimIndent()

    /** Ciel : triangle plein écran, dégradé, disque solaire et étoiles. */
    val SKY_VS = VERSION + """
        out vec2 vNdc;
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)) * 2.0 - 1.0;
            vNdc = p;
            gl_Position = vec4(p, 0.9999, 1.0);
        }
    """.trimIndent()

    val SKY_FS = VERSION + """
        precision highp float;
        in vec2 vNdc;
        uniform mat4 uInvViewProj;
        uniform vec3 uEye;
        uniform vec3 uSkyTop;
        uniform vec3 uSkyHorizon;
        uniform vec3 uSkyBottom;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform float uStars;
        uniform float uSunDisc;
        out vec4 fragColor;
        float hash(vec3 p) { return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }
        void main() {
            vec4 far = uInvViewProj * vec4(vNdc, 1.0, 1.0);
            vec3 dir = normalize(far.xyz / far.w - uEye);
            float y = dir.y;
            vec3 c = y > 0.0 ? mix(uSkyHorizon, uSkyTop, pow(y, 0.6)) : mix(uSkyHorizon, uSkyBottom, pow(-y, 0.5));
            float sd = max(dot(dir, normalize(uSunDir)), 0.0);
            if (uSunDisc > 0.0) {
                c += uSunColor * pow(sd, 900.0 / uSunDisc) * 2.0;
                c += uSunColor * pow(sd, 12.0) * 0.18 * uSunDisc;
            }
            if (uStars > 0.0 && y > 0.0) {
                vec3 cell = floor(dir * 140.0);
                float s = hash(cell);
                c += vec3(step(0.9975, s)) * uStars * smoothstep(0.0, 0.25, y);
            }
            vec2 q = vNdc * 0.5;
            c *= 1.0 - dot(q, q) * 0.35; // vignettage léger
            fragColor = vec4(c, 1.0);
        }
    """.trimIndent()

    /** Passe d'ombre : profondeur vue du soleil (les pixels transparents de la texture ne portent pas d'ombre). */
    val DEPTH_VS = VERSION + """
        layout(location = 0) in vec3 aPos;
        layout(location = 2) in vec2 aUv;
        layout(location = 3) in vec4 aColor;
        uniform mat4 uModel;
        uniform mat4 uLightVP;
        out vec2 vUv;
        out float vTextured;
        void main() {
            vUv = aUv;
            vTextured = aColor.a;
            gl_Position = uLightVP * uModel * vec4(aPos, 1.0);
        }
    """.trimIndent()

    val DEPTH_FS = VERSION + """
        precision highp float;
        in vec2 vUv;
        in float vTextured;
        uniform sampler2D uTex;
        uniform float uHasTex;
        out vec4 fragColor;
        void main() {
            if (vTextured > 0.5 && uHasTex > 0.5 && texture(uTex, vUv).a < 0.1) discard;
            fragColor = vec4(1.0);
        }
    """.trimIndent()

    val ALL: Map<String, Pair<String, String>> = mapOf(
        "model" to (MODEL_VS to MODEL_FS),
        "ground" to (GROUND_VS to GROUND_FS),
        "sky" to (SKY_VS to SKY_FS),
        "depth" to (DEPTH_VS to DEPTH_FS),
    )
}
