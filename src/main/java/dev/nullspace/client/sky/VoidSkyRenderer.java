package dev.nullspace.client.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.nullspace.Nullspace;
import dev.nullspace.client.sky.VoidSkyConfig.Quality;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.Random;

public final class VoidSkyRenderer {
    private static final float R = 100f;
    private static final double RAD = Math.PI / 180.0;

    // fixed time in ticks so two screenshots compare pixel for pixel
    static volatile Float timeOverride;

    private VoidTheme theme;
    private Quality quality;
    private VertexBuffer dome, band, core;
    private final VertexBuffer[] stars = new VertexBuffer[3];
    private float[][] glints; // x y z sizeDeg r g b phase

    private static ResourceLocation tex(String name) { return Nullspace.id(VoidTheme.TEX + name); }
    private static final ResourceLocation NEBULA = tex("nebula_band.png"), HAZE = tex("horizon_haze.png"), AURORA = tex("aurora.png"), GLINT = tex("glint.png"), DEBRIS = tex("debris.png");

    public void close() {
        for (VertexBuffer v : new VertexBuffer[] {dome, band, core, stars[0], stars[1], stars[2]}) if (v != null) v.close();
        dome = band = core = null;
        stars[0] = stars[1] = stars[2] = null;
        theme = null; quality = null;
    }

    private void ensure(VoidTheme wantTheme, Quality wantQuality) {
        if (theme == wantTheme && quality == wantQuality && dome != null) return;
        close();
        theme = wantTheme; quality = wantQuality;
        dome = buildDome();
        band = buildBand();
        core = buildCore();
        for (int g = 0; g < 3; g++) stars[g] = buildStars(g);
        glints = buildGlints();
    }


    private static VertexBuffer upload(BufferBuilder b) {
        VertexBuffer v = new VertexBuffer(VertexBuffer.Usage.STATIC);
        v.bind(); v.upload(b.buildOrThrow()); VertexBuffer.unbind();
        return v;
    }

    private static BufferBuilder begin(boolean textured) {
        return Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, textured ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR);
    }

    private static double[] dir(double azDeg, double elDeg) {
        double a = azDeg * RAD, e = elDeg * RAD, c = Math.cos(e);
        return new double[] {Math.sin(a) * c, Math.sin(e), -Math.cos(a) * c};
    }

    private float[] domeColor(double el) {
        float[][] s = theme.dome();
        if (el <= s[0][0]) return new float[] {s[0][1], s[0][2], s[0][3]};
        for (int i = 1; i < s.length; i++) {
            if (el <= s[i][0]) {
                float t = (float) ((el - s[i - 1][0]) / (s[i][0] - s[i - 1][0]));
                return new float[] {s[i - 1][1] + (s[i][1] - s[i - 1][1]) * t, s[i - 1][2] + (s[i][2] - s[i - 1][2]) * t, s[i - 1][3] + (s[i][3] - s[i - 1][3]) * t};
            }
        }
        float[] l = s[s.length - 1];
        return new float[] {l[1], l[2], l[3]};
    }

    private VertexBuffer buildDome() {
        double[] els = {-90, -62, -42, -28, -18, -11, -6, -3, -1, 0, 1, 3, 6, 10, 15, 21, 29, 40, 55, 72, 90};
        int slices = 72;
        BufferBuilder b = begin(false);
        for (int i = 0; i < els.length - 1; i++) {
            for (int j = 0; j < slices; j++) {
                double a0 = j * 360.0 / slices, a1 = (j + 1) * 360.0 / slices;
                vc(b, a0, els[i], domeColor(els[i], a0)); vc(b, a1, els[i], domeColor(els[i], a1));
                vc(b, a1, els[i + 1], domeColor(els[i + 1], a1)); vc(b, a0, els[i + 1], domeColor(els[i + 1], a0));
            }
        }
        return upload(b);
    }

    private float[] domeColor(double el, double az) {
        float[] c = domeColor(el);
        double da = Math.abs(((az - theme.glowAz() + 540) % 360) - 180);
        double k = Math.exp(-Math.pow(da / 62.0, 2)) * Math.exp(-Math.pow((el - 1.0) / 11.0, 2));
        float[] g = theme.glow();
        return new float[] {c[0] + g[0] * (float) k, c[1] + g[1] * (float) k, c[2] + g[2] * (float) k};
    }

    private static void vc(BufferBuilder b, double az, double el, float[] c) {
        double[] d = dir(az, el);
        b.addVertex((float) (d[0] * R), (float) (d[1] * R), (float) (d[2] * R)).setColor(c[0], c[1], c[2], 1f);
    }

    private VertexBuffer buildBand() {
        int segs = quality == Quality.LOW ? 64 : 128, rows = quality.bandRows;
        double half = 22.5;
        BufferBuilder b = begin(true);
        for (int i = 0; i < segs; i++) {
            for (int r = 0; r < rows; r++) {
                double l0 = -half + 2 * half * r / rows, l1 = -half + 2 * half * (r + 1) / rows;
                bandVertex(b, i, segs, l0, half); bandVertex(b, i + 1, segs, l0, half); bandVertex(b, i + 1, segs, l1, half); bandVertex(b, i, segs, l1, half);
            }
        }
        return upload(b);
    }

    private static void bandVertex(BufferBuilder b, int i, int segs, double lat, double half) {
        double[] d = dir(i * 360.0 / segs, lat);
        float v = (float) (1.0 - (lat + half) / (2 * half));
        b.addVertex((float) (d[0] * R), (float) (d[1] * R), (float) (d[2] * R)).setUv((float) i / segs, v).setColor(1f, 1f, 1f, 0.95f);
    }

    private VertexBuffer buildCore() {
        double[] rho = {0, 5, 10, 17, 26, 38};
        float[] alpha = {0.34f, 0.22f, 0.11f, 0.045f, 0.015f, 0f};
        int n = 24;
        BufferBuilder b = begin(false);
        for (int k = 0; k < rho.length - 1; k++) {
            for (int j = 0; j < n; j++) {
                double t0 = j * 2 * Math.PI / n, t1 = (j + 1) * 2 * Math.PI / n;
                coreV(b, rho[k], t0, alpha[k]); coreV(b, rho[k], t1, alpha[k]); coreV(b, rho[k + 1], t1, alpha[k + 1]); coreV(b, rho[k + 1], t0, alpha[k + 1]);
            }
        }
        return upload(b);
    }

    private void coreV(BufferBuilder b, double rhoDeg, double theta, float a) {
        double az = theme.bandCoreAzimuth();
        double dAz = Math.sin(theta) * rhoDeg * 1.7, dEl = Math.cos(theta) * rhoDeg * 0.8;
        double[] d = dir(az + dAz, Math.max(-85, Math.min(85, dEl)));
        b.addVertex((float) (d[0] * R), (float) (d[1] * R), (float) (d[2] * R)).setColor(1.0f, 0.62f, 0.52f, a);
    }

    private static final float[][] TEMPERATURES = {
        {0.80f, 0.88f, 1.00f}, {1.00f, 1.00f, 1.00f}, {1.00f, 0.94f, 0.78f}, {1.00f, 0.80f, 0.52f}, {1.00f, 0.62f, 0.45f}, {1.00f, 0.55f, 0.66f}};

    private static float[] temperature(Random rnd) {
        double k = rnd.nextDouble();
        return TEMPERATURES[k < 0.30 ? 0 : k < 0.55 ? 1 : k < 0.75 ? 2 : k < 0.90 ? 3 : k < 0.97 ? 4 : 5];
    }

    private VertexBuffer buildStars(int group) {
        Random rnd = new Random(theme.seed() + 101L * group);
        int count = quality.stars / 3;
        BufferBuilder b = begin(false);
        int made = 0;
        while (made < count) {
            double y = 2 * rnd.nextDouble() - 1, a = 2 * Math.PI * rnd.nextDouble(), rr = Math.sqrt(1 - y * y);
            double lat = Math.asin(y);
            if (rnd.nextDouble() > 0.30 + 0.70 * Math.exp(-Math.pow(lat / 0.30, 2))) continue;
            float bright = (float) (0.42 + 0.58 * Math.pow(rnd.nextDouble(), 2.2));
            double sizeDeg = 0.10 + 0.24 * bright;
            float[] t = temperature(rnd);
            quadOnSphere(b, rr * Math.cos(a), y, rr * Math.sin(a), sizeDeg, t[0] * bright, t[1] * bright, t[2] * bright, 1f, null);
            made++;
        }
        return upload(b);
    }

    private float[][] buildGlints() {
        Random rnd = new Random(theme.seed() + 7);
        float[][] g = new float[quality.glints][];
        for (int i = 0; i < g.length; i++) {
            double y = 2 * rnd.nextDouble() - 1, a = 2 * Math.PI * rnd.nextDouble(), rr = Math.sqrt(1 - y * y);
            float[] t = temperature(rnd);
            g[i] = new float[] {(float) (rr * Math.cos(a)), (float) y, (float) (rr * Math.sin(a)), (float) (1.3 + 1.9 * rnd.nextDouble()), t[0], t[1], t[2], (float) (rnd.nextDouble() * 6.28)};
        }
        return g;
    }

    private static void quadOnSphere(BufferBuilder b, double x, double y, double z, double sizeDeg, float r, float g, float bl, float a, Matrix4f m) {
        double half = Math.tan(sizeDeg * RAD / 2) * R;
        double rx = -z, rz = x; double rl = Math.sqrt(rx * rx + rz * rz); if (rl < 1e-6) { rx = 1; rz = 0; rl = 1; }
        rx /= rl; rz /= rl; // right = Y x dir, flat
        double ux = -y * rz, uy = x * rz - z * rx, uz = y * rx; // up = right x dir
        double px = x * R, py = y * R, pz = z * R;
        float[][] c = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};
        for (float[] k : c) {
            float vx = (float) (px + k[0] * half * rx + k[1] * half * ux), vy = (float) (py + k[1] * half * uy), vz = (float) (pz + k[0] * half * rz + k[1] * half * uz);
            if (m == null) b.addVertex(vx, vy, vz).setColor(r, g, bl, a); else b.addVertex(m, vx, vy, vz).setColor(r, g, bl, a);
        }
    }


    private static void drawBuffer(VertexBuffer v, Matrix4f mv, Matrix4f proj) {
        ShaderInstance shader = RenderSystem.getShader();
        v.bind(); v.drawWithShader(mv, proj, shader); VertexBuffer.unbind();
    }

    private static void flush(BufferBuilder b) {
        var mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    private static void additive() { RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE); }

    public void render(VoidTheme t, Quality q, Matrix4f modelView, Matrix4f projection, float time) {
        ensure(t, q);
        float tm = timeOverride != null ? timeOverride : time;
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        FogRenderer.setupNoFog();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        RenderSystem.disableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        drawBuffer(dome, modelView, projection);

        RenderSystem.enableBlend(); additive();
        Matrix4f cel = new Matrix4f(modelView).rotateX((float) (t.tiltDeg() * RAD)).rotateY((float) (tm * t.spinRate() * RAD));
        drawBuffer(core, cel, projection);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, NEBULA);
        drawBuffer(band, cel, projection);
        // a second, fainter pass of the same band on another tilt, turning the other way: fills the sky overhead and gives the layers parallax
        RenderSystem.setShaderColor(1f, 1f, 1f, 0.45f);
        Matrix4f cel2 = new Matrix4f(modelView).rotateX((float) (-48 * RAD)).rotateZ((float) (20 * RAD)).rotateY((float) (-tm * t.spinRate() * 0.6f * RAD));
        drawBuffer(band, cel2, projection);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        for (int g = 0; g < 3; g++) {
            float a = q.twinkle() ? twinkle(tm, g) : 0.85f;
            RenderSystem.setShaderColor(1f, 1f, 1f, a);
            drawBuffer(stars[g], cel, projection);
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        if (glints.length > 0) drawGlints(cel, tm);
        for (int i = 0; i < q.auroras && i < t.auroras().size(); i++) drawAurora(t.auroras().get(i), modelView, tm, q);
        drawBodyGlows(t, modelView, tm);

        RenderSystem.defaultBlendFunc();
        drawBodies(t, modelView, tm);
        drawIslands(t, q, modelView, tm, false);
        if (q.hazeLayers > 0) drawHaze(t, q, modelView, tm);
        drawIslands(t, q, modelView, tm, true);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
    }

    private static float twinkle(float t, int g) {
        float[] w1 = {0.061f, 0.083f, 0.109f}, w2 = {0.173f, 0.131f, 0.211f};
        return 0.60f + 0.22f * (float) Math.sin(t * w1[g] + g * 2.1) + 0.18f * (float) Math.sin(t * w2[g] + g * 4.7 + 1.3);
    }

    private void drawGlints(Matrix4f cel, float tm) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, GLINT);
        BufferBuilder b = begin(true);
        for (float[] g : glints) {
            float k = 0.55f + 0.45f * (float) Math.sin(tm * 0.047f + g[7]) * (float) Math.sin(tm * 0.021f + g[7] * 1.7f);
            k = Math.max(0.12f, k);
            double half = Math.tan(g[3] * RAD / 2) * R;
            double x = g[0], y = g[1], z = g[2];
            double rx = -z, rz = x, rl = Math.sqrt(rx * rx + rz * rz); if (rl < 1e-6) { rx = 1; rz = 0; rl = 1; }
            rx /= rl; rz /= rl;
            double ux = -y * rz, uy = x * rz - z * rx, uz = y * rx;
            float[][] c = {{-1, 1, 0, 0}, {1, 1, 1, 0}, {1, -1, 1, 1}, {-1, -1, 0, 1}};
            for (float[] p : c) {
                b.addVertex(cel, (float) (x * R + p[0] * half * rx + p[1] * half * ux), (float) (y * R + p[1] * half * uy), (float) (z * R + p[0] * half * rz + p[1] * half * uz))
                    .setUv(p[2], p[3]).setColor(g[4], g[5], g[6], k);
            }
        }
        flush(b);
    }

    private static void sv(BufferBuilder b, Matrix4f m, double az, double el, float u, float v, float r, float g, float bl, float a) {
        double[] d = dir(az, el);
        b.addVertex(m, (float) (d[0] * R), (float) (d[1] * R), (float) (d[2] * R)).setUv(u, v).setColor(r, g, bl, a);
    }

    private void drawAurora(VoidTheme.Aurora a, Matrix4f mv, float t, Quality q) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, AURORA);
        int n = q == Quality.HIGH ? 56 : 36, rows = 5;
        float uSpan = a.azSpan() / 90f;
        BufferBuilder b = begin(true);
        double[][] col = new double[n + 1][5]; // az, elBase, height, shear, alpha
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n, az = a.azCentre() + (f - 0.5) * a.azSpan();
            double wave = 3.2 * Math.sin(az * 0.07 + t * 0.011 + a.phase()) + 1.8 * Math.sin(az * 0.19 - t * 0.017 + a.phase() * 1.7);
            double h = a.heightDeg() * (0.78 + 0.22 * Math.sin(az * 0.11 + t * 0.013 + a.phase() * 2.3));
            double shear = 4.5 * Math.sin(az * 0.05 + t * 0.007 + a.phase());
            double env = Math.pow(Math.sin(Math.PI * f), 0.6);
            double pulse = 0.70 + 0.30 * Math.sin(t * 0.03 + az * 0.08 + a.phase());
            col[i] = new double[] {az, a.elBase() + wave, h, shear, a.intensity() * env * pulse};
        }
        for (int i = 0; i < n; i++) {
            for (int r = 0; r < rows; r++) {
                double v0 = (double) r / rows, v1 = (double) (r + 1) / rows;
                float u0 = (float) i / n * uSpan + t * 0.0007f, u1 = (float) (i + 1) / n * uSpan + t * 0.0007f;
                auroraVertex(b, mv, a, col[i], v0, u0); auroraVertex(b, mv, a, col[i + 1], v0, u1);
                auroraVertex(b, mv, a, col[i + 1], v1, u1); auroraVertex(b, mv, a, col[i], v1, u0);
            }
        }
        flush(b);
    }

    private static void auroraVertex(BufferBuilder b, Matrix4f mv, VoidTheme.Aurora a, double[] c, double v, float u) {
        double k = Math.pow(v, 0.8);
        float r = (float) (a.bottom()[0] + (a.top()[0] - a.bottom()[0]) * k), g = (float) (a.bottom()[1] + (a.top()[1] - a.bottom()[1]) * k), bl = (float) (a.bottom()[2] + (a.top()[2] - a.bottom()[2]) * k);
        sv(b, mv, c[0] + c[3] * v * v, c[1] + c[2] * v, u, (float) (1.0 - v), r, g, bl, (float) c[4]);
    }

    private static double az(VoidTheme.Island i, float t) { return i.az() + i.rate() * t; }

    private void drawBodyGlows(VoidTheme t, Matrix4f mv, float tm) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = begin(false);
        for (VoidTheme.Body body : t.bodies()) {
            double az = body.az() + body.rate() * tm;
            double[] c = dir(az, body.el());
            double rad = body.widthDeg() * 0.5 * body.glowScale();
            double[] rr = {0.0, 0.55, 1.0};
            float[] aa = {0.0f, 0.12f, 0.0f};
            int n = 20;
            for (int k = 0; k < 2; k++) for (int j = 0; j < n; j++) {
                double t0 = j * 2 * Math.PI / n, t1 = (j + 1) * 2 * Math.PI / n;
                glowV(b, mv, c, rad * rr[k], t0, body, aa[k]); glowV(b, mv, c, rad * rr[k], t1, body, aa[k]);
                glowV(b, mv, c, rad * rr[k + 1], t1, body, aa[k + 1]); glowV(b, mv, c, rad * rr[k + 1], t0, body, aa[k + 1]);
            }
        }
        flush(b);
    }

    private static void glowV(BufferBuilder b, Matrix4f mv, double[] c, double radDeg, double theta, VoidTheme.Body body, float a) {
        double[][] f = frame(c);
        double s = Math.tan(radDeg * RAD) * Math.sin(theta), u = Math.tan(radDeg * RAD) * Math.cos(theta);
        double x = c[0] + f[0][0] * s + f[1][0] * u, y = c[1] + f[0][1] * s + f[1][1] * u, z = c[2] + f[0][2] * s + f[1][2] * u;
        double l = Math.sqrt(x * x + y * y + z * z);
        b.addVertex(mv, (float) (x / l * R), (float) (y / l * R), (float) (z / l * R)).setColor(body.glow()[0], body.glow()[1], body.glow()[2], a);
    }

    private static double[][] frame(double[] c) {
        double rx = -c[2], rz = c[0], rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-6) { rx = 1; rz = 0; rl = 1; }
        rx /= rl; rz /= rl;
        return new double[][] {{rx, 0, rz}, {-c[1] * rz, c[0] * rz - c[2] * rx, c[1] * rx}};
    }

    private static void sprite(BufferBuilder b, Matrix4f mv, double[] c, double widthDeg, double aspect, float u0, float u1, float r, float g, float bl, float a) {
        double[][] f = frame(c);
        double hw = Math.tan(widthDeg * RAD / 2) * R, hh = hw / aspect;
        double[][] k = {{-1, 1, 0}, {1, 1, 1}, {1, -1, 2}, {-1, -1, 3}};
        for (double[] p : k) {
            float x = (float) (c[0] * R + p[0] * hw * f[0][0] + p[1] * hh * f[1][0]);
            float y = (float) (c[1] * R + p[0] * hw * f[0][1] + p[1] * hh * f[1][1]);
            float z = (float) (c[2] * R + p[0] * hw * f[0][2] + p[1] * hh * f[1][2]);
            float u = (p[2] == 0 || p[2] == 3) ? u0 : u1, v = (p[2] <= 1) ? 0f : 1f;
            b.addVertex(mv, x, y, z).setUv(u, v).setColor(r, g, bl, a);
        }
    }

    private void drawBodies(VoidTheme t, Matrix4f mv, float tm) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        for (VoidTheme.Body body : t.bodies()) {
            RenderSystem.setShaderTexture(0, tex(body.texture()));
            BufferBuilder b = begin(true);
            sprite(b, mv, dir(body.az() + body.rate() * tm, body.el()), body.widthDeg(), body.aspect(), 0f, 1f, 1f, 1f, 1f, 1f);
            flush(b);
        }
    }

    private void drawIslands(VoidTheme t, Quality q, Matrix4f mv, float tm, boolean near) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        int count = Math.min(q.islands, t.islands().size());
        int perIsland = count == 0 ? 0 : q.debris / count;
        for (int idx = 0; idx < count; idx++) {
            VoidTheme.Island is = t.islands().get(idx);
            if (is.near() != near) continue;
            double bob = q.twinkle() ? is.bobDeg() * Math.sin(tm * 0.021 + idx * 1.9) : 0;
            double az = az(is, tm), el = is.el() + bob;
            RenderSystem.setShaderTexture(0, tex(is.texture()));
            BufferBuilder b = begin(true);
            sprite(b, mv, dir(az, el), is.widthDeg(), is.aspect(), 0f, 1f, 1f, 1f, 1f, is.alpha());
            flush(b);
            if (perIsland > 0) {
                RenderSystem.setShaderTexture(0, DEBRIS);
                BufferBuilder d = begin(true);
                Random rnd = new Random(t.seed() + 977L * idx);
                for (int k = 0; k < Math.min(perIsland, is.debris() + 8); k++) {
                    double daz = (rnd.nextDouble() - 0.5) * is.widthDeg() * 2.6, del = (rnd.nextDouble() - 0.5) * is.widthDeg() * 1.1;
                    double sz = is.widthDeg() * (0.05 + 0.09 * rnd.nextDouble());
                    int cell = rnd.nextInt(4);
                    double ph = rnd.nextDouble() * 6.28, rate = 0.8 + 0.4 * rnd.nextDouble();
                    double dz = az + daz + (rate - 1) * is.rate() * tm, de = el + del + 0.25 * Math.sin(tm * 0.03 * rate + ph) * (is.widthDeg() / 8);
                    sprite(d, mv, dir(dz, de), sz, 1.0, cell / 4f, (cell + 1) / 4f, 1f, 1f, 1f, is.alpha());
                }
                flush(d);
            }
        }
    }

    private void drawHaze(VoidTheme t, Quality q, Matrix4f mv, float tm) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, HAZE);
        float[] h = t.hazeRgb();
        double[][] layers = {{-7, 15.5, 1, 0.000022, 0.50}, {-34, -9, 2, -0.000030, 0.55}};
        BufferBuilder b = begin(true);
        for (int l = 0; l < q.hazeLayers; l++) {
            double[] p = layers[l];
            int segs = 96;
            for (int i = 0; i < segs; i++) {
                float u0 = (float) (i * p[2] / segs + p[3] * tm), u1 = (float) ((i + 1) * p[2] / segs + p[3] * tm);
                double a0 = i * 360.0 / segs, a1 = (i + 1) * 360.0 / segs;
                sv(b, mv, a0, p[1], u0, 0f, h[0], h[1], h[2], (float) p[4]); sv(b, mv, a1, p[1], u1, 0f, h[0], h[1], h[2], (float) p[4]);
                sv(b, mv, a1, p[0], u1, 1f, h[0], h[1], h[2], (float) p[4]); sv(b, mv, a0, p[0], u0, 1f, h[0], h[1], h[2], (float) p[4]);
            }
        }
        flush(b);
    }
}
