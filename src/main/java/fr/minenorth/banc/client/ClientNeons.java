package fr.minenorth.banc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.minenorth.banc.MineNorthBanc;
import fr.minenorth.banc.NeonService;
import fr.minenorth.banc.TuneAccess;
import fr.minenorth.banc.network.BancNetwork;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rendu des néons sous caisse, chez chaque joueur, d'après l'état envoyé par le serveur (BancNetwork.NeonSync). Dessiné après les blocs
 * translucides (RenderLevelStageEvent : passe du monde, indépendante du rendu des entités MTS, compatible Embeddium / Oculus) avec le type de
 * rendu de la foudre : mélange additif, pleine luminosité, donc très visible la nuit. Deux couches : les tubes le long du bas de caisse
 * (suivent l'inclinaison du véhicule) et un halo coloré projeté sur le sol sous la voiture.
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT)
public final class ClientNeons {
    private ClientNeons() {}

    private static final Map<Integer, BancNetwork.NeonSync> STATE = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> MISSING = new ConcurrentHashMap<>();
    private static final double MAX_DISTANCE = 128, HALO = 0.9, TUBE = 0.07, SEGMENT = 0.25;
    /** Aperçu de l'écran du banc : remplace l'état du véhicule tant que l'écran est ouvert (null sinon). */
    private static volatile BancNetwork.NeonSync preview;

    public static void receive(BancNetwork.NeonSync s) {
        if (s.installed()) STATE.put(s.entityId(), s); else STATE.remove(s.entityId());
        MISSING.remove(s.entityId());
    }

    public static void preview(BancNetwork.NeonSync s) { preview = s; }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { STATE.clear(); MISSING.clear(); NeonShapes.clear(); preview = null; return; }
        if (mc.level.getGameTime() % 20 != 0) return;
        // véhicule disparu (hors de vue, rangé...) : oublié après 10 s ; le serveur renvoie l'état quand il réapparaît
        for (Iterator<Integer> it = STATE.keySet().iterator(); it.hasNext(); ) {
            int id = it.next();
            Entity v = mc.level.getEntity(id);
            if (v != null && !v.isRemoved()) { MISSING.remove(id); continue; }
            if (MISSING.merge(id, 1, Integer::sum) > 10) { it.remove(); MISSING.remove(id); NeonShapes.forget(id); }
        }
    }

    // ------------------------------------------------------------------ rendu

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        BancNetwork.NeonSync pv = preview;
        if (STATE.isEmpty() && pv == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera cam = e.getCamera();
        Vec3 c = cam.getPosition();
        float partial = e.getPartialTick();
        double time = (mc.level.getGameTime() + partial) / 20.0;
        PoseStack ps = e.getPoseStack();
        ps.pushPose();
        ps.translate(-c.x, -c.y, -c.z);
        Matrix4f m = ps.last().pose();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buf.getBuffer(RenderType.lightning());
        for (BancNetwork.NeonSync s : STATE.values()) {
            if (pv != null && pv.entityId() == s.entityId()) continue;
            if (s.on()) draw(mc, vc, m, s, partial, time, c);
        }
        if (pv != null) draw(mc, vc, m, pv, partial, time, c);
        buf.endBatch(RenderType.lightning());
        ps.popPose();
    }

    /** Couleur RGB (0..1) et intensité d'un point du tour de caisse ({@code along} : 0..1 le long du périmètre). */
    private static float[] colorAt(BancNetwork.NeonSync s, double time, double along) {
        int rgb = s.color();
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f, k = 1f;
        switch (s.mode()) {
            case NeonService.MODE_BREATH -> k = (float) (0.3 + 0.7 * (0.5 + 0.5 * Math.sin(time * Math.PI * 2 / 2.4)));
            case NeonService.MODE_RAINBOW -> {
                int h = Mth.hsvToRgb((float) ((time * 0.12 + along) % 1.0), 1f, 1f);
                r = ((h >> 16) & 255) / 255f; g = ((h >> 8) & 255) / 255f; b = (h & 255) / 255f;
            }
            case NeonService.MODE_CHASE -> {
                double d = Math.abs(((along - time * 0.45) % 1.0 + 1.0) % 1.0 - 0.5) * 2;   // 1 au point lumineux, 0 à l'opposé
                k = (float) (0.2 + 0.8 * Math.pow(d, 6));
            }
            default -> {}
        }
        return new float[]{r, g, b, k};
    }

    private static void draw(Minecraft mc, VertexConsumer vc, Matrix4f m, BancNetwork.NeonSync s, float partial, double time, Vec3 cam) {
        Entity v = mc.level.getEntity(s.entityId());
        if (v == null || v.isRemoved() || v.distanceToSqr(cam) > MAX_DISTANCE * MAX_DISTANCE) return;
        double[] p = TuneAccess.pose(v, partial);
        if (p == null) {   // repli : position de l'entité Forge et lacet seul
            double yaw = Math.toRadians(Mth.lerp(partial, v.yRotO, v.getYRot())), cs = Math.cos(yaw), sn = Math.sin(yaw);
            p = new double[]{Mth.lerp(partial, v.xo, v.getX()), Mth.lerp(partial, v.yo, v.getY()), Mth.lerp(partial, v.zo, v.getZ()),
                    cs, 0, -sn, 0, 1, 0, sn, 0, cs};
        }
        // forme du bas de caisse (modèle 3D du pack), en retrait de quelques cm pour rester sous la carrosserie, + correction du modèle (banc)
        double[] r = rect(NeonShapes.of(v), s.width(), s.front(), s.rear(), s.height());
        double x0 = r[0], x1 = r[1], z0 = r[2], z1 = r[3], y = r[4];

        // ---- halo au sol : plan horizontal sous la caisse, centre coloré qui s'estompe vers l'extérieur
        double cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        double[] center = world(p, cx, y, cz);
        BlockHitResult hit = mc.level.clip(new ClipContext(new Vec3(center[0], center[1] + 0.2, center[2]),
                new Vec3(center[0], center[1] - 3.0, center[2]), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, v));
        if (hit.getType() == HitResult.Type.BLOCK) {
            double gy = hit.getLocation().y + 0.03;
            float fade = (float) Mth.clamp(1.0 - (center[1] - gy) / 2.5, 0, 1);
            float[] col = colorAt(s, time, 0);
            float a = 0.75f * fade * (s.mode() == NeonService.MODE_CHASE ? 0.6f : col[3]);
            if (a > 0.01f) {
                // axes horizontaux du véhicule (lacet seul : le halo reste à plat sur le sol)
                double fx = p[5], fz = p[11], fl = Math.max(1e-6, Math.hypot(fx, fz)), rx = p[3], rz = p[9], rl = Math.max(1e-6, Math.hypot(rx, rz));
                fx /= fl; fz /= fl; rx /= rl; rz /= rl;
                double hw = (x1 - x0) / 2, hl = (z1 - z0) / 2;
                double[][] in = rect(center[0], center[2], rx, rz, fx, fz, hw * 0.9, hl * 0.9);
                double[][] out = rect(center[0], center[2], rx, rz, fx, fz, hw + HALO, hl + HALO);
                quad(vc, m, in[0][0], gy, in[0][1], in[1][0], gy, in[1][1], in[2][0], gy, in[2][1], in[3][0], gy, in[3][1], col, a, a);
                for (int i = 0; i < 4; i++) {
                    int j = (i + 1) % 4;
                    quad(vc, m, in[i][0], gy, in[i][1], in[j][0], gy, in[j][1], out[j][0], gy, out[j][1], out[i][0], gy, out[i][1], col, a, 0f);
                }
            }
        }

        // ---- tubes : tour du bas de caisse, découpé en segments (couleur et intensité par segment pour l'arc-en-ciel et le chenillard)
        double[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        double per = 2 * ((x1 - x0) + (z1 - z0)), done = 0;
        for (int i = 0; i < 4; i++) {
            double ax = corners[i][0], az = corners[i][1], bx = corners[(i + 1) % 4][0], bz = corners[(i + 1) % 4][1];
            double len = Math.hypot(bx - ax, bz - az);
            int n = Math.max(1, (int) Math.ceil(len / SEGMENT));
            // normale extérieure dans le plan du véhicule
            double nx = (bz - az) / len, nz = -(bx - ax) / len;
            if (nx * ((ax + bx) / 2 - (x0 + x1) / 2) + nz * ((az + bz) / 2 - (z0 + z1) / 2) < 0) { nx = -nx; nz = -nz; }
            for (int k = 0; k < n; k++) {
                double t0 = (double) k / n, t1 = (double) (k + 1) / n;
                double sx = ax + (bx - ax) * t0, sz = az + (bz - az) * t0, ex = ax + (bx - ax) * t1, ez = az + (bz - az) * t1;
                float[] col = colorAt(s, time, (done + len * (t0 + t1) / 2) / per);
                float[] core = {col[0] + (1 - col[0]) * 0.45f, col[1] + (1 - col[1]) * 0.45f, col[2] + (1 - col[2]) * 0.45f, col[3]};
                // tube (face du dessous + face extérieure), cœur presque blanc
                quadLocal(vc, m, p, sx - nx * TUBE, y, sz - nz * TUBE, ex - nx * TUBE, y, ez - nz * TUBE,
                        ex + nx * TUBE, y, ez + nz * TUBE, sx + nx * TUBE, y, sz + nz * TUBE, core, core[3], core[3]);
                quadLocal(vc, m, p, sx + nx * TUBE, y, sz + nz * TUBE, ex + nx * TUBE, y, ez + nz * TUBE,
                        ex + nx * TUBE, y + 0.06, ez + nz * TUBE, sx + nx * TUBE, y + 0.06, sz + nz * TUBE, core, core[3], core[3]);
                // lueur autour du tube, qui s'étale vers l'extérieur et le bas
                float ga = 0.55f * col[3];
                quadLocal(vc, m, p, sx + nx * TUBE, y, sz + nz * TUBE, ex + nx * TUBE, y, ez + nz * TUBE,
                        ex + nx * 0.45, y - 0.12, ez + nz * 0.45, sx + nx * 0.45, y - 0.12, sz + nz * 0.45, col, ga, 0f);
                quadLocal(vc, m, p, sx - nx * TUBE, y, sz - nz * TUBE, ex - nx * TUBE, y, ez - nz * TUBE,
                        ex - nx * 0.35, y - 0.05, ez - nz * 0.35, sx - nx * 0.35, y - 0.05, sz - nz * 0.35, col, ga * 0.6f, 0f);
            }
            done += len;
        }
    }

    /**
     * Rectangle des tubes dans le repère du véhicule : {x0, x1, z0, z1, y} d'après la forme du bas de caisse (NeonShapes) et les corrections
     * du banc. Le même calcul sert au rendu dans le monde et à l'aperçu 3D de l'écran du banc.
     */
    static double[] rect(double[] f, double width, double front, double rear, double height) {
        double mid = (f[0] + f[1]) / 2, half = Math.max(0.1, (f[1] - f[0]) / 2 - 0.06 + width);
        double z0 = f[2] + 0.15 - rear, z1 = Math.max(z0 + 0.3, f[3] - 0.15 + front);
        return new double[]{mid - half, mid + half, z0, z1, f[4] - 0.05 + height};
    }

    /** Coin d'un rectangle horizontal centré (cx, cz), d'axes droite (rx, rz) / avant (fx, fz) : {x, z} x 4 dans l'ordre du tour. */
    private static double[][] rect(double cx, double cz, double rx, double rz, double fx, double fz, double hw, double hl) {
        return new double[][]{
                {cx - rx * hw - fx * hl, cz - rz * hw - fz * hl}, {cx + rx * hw - fx * hl, cz + rz * hw - fz * hl},
                {cx + rx * hw + fx * hl, cz + rz * hw + fz * hl}, {cx - rx * hw + fx * hl, cz - rz * hw + fz * hl}};
    }

    /** Point du repère du véhicule (MTS) vers le monde : position + M x local. */
    private static double[] world(double[] p, double x, double y, double z) {
        return new double[]{p[0] + p[3] * x + p[4] * y + p[5] * z, p[1] + p[6] * x + p[7] * y + p[8] * z, p[2] + p[9] * x + p[10] * y + p[11] * z};
    }

    private static void quadLocal(VertexConsumer vc, Matrix4f m, double[] p, double ax, double ay, double az, double bx, double by, double bz,
                                  double cx, double cy, double cz, double dx, double dy, double dz, float[] col, float a0, float a1) {
        double[] a = world(p, ax, ay, az), b = world(p, bx, by, bz), c = world(p, cx, cy, cz), d = world(p, dx, dy, dz);
        quad(vc, m, a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2], col, a0, a1);
    }

    /** Quadrilatère visible des deux côtés ; a0 = opacité du bord AB, a1 = celle du bord CD (dégradé). */
    private static void quad(VertexConsumer vc, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz, float[] col, float a0, float a1) {
        vertex(vc, m, ax, ay, az, col, a0); vertex(vc, m, bx, by, bz, col, a0); vertex(vc, m, cx, cy, cz, col, a1); vertex(vc, m, dx, dy, dz, col, a1);
        vertex(vc, m, dx, dy, dz, col, a1); vertex(vc, m, cx, cy, cz, col, a1); vertex(vc, m, bx, by, bz, col, a0); vertex(vc, m, ax, ay, az, col, a0);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, double x, double y, double z, float[] col, float a) {
        vc.vertex(m, (float) x, (float) y, (float) z).color(col[0], col[1], col[2], Mth.clamp(a, 0f, 1f)).endVertex();
    }
}
