package fr.minenorth.banc.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.minenorth.banc.MineNorthBanc;
import fr.minenorth.banc.TuneAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Flammes de nitro en 3D, dessinées chez chaque joueur d'après l'état envoyé par le serveur (BancNetwork.TuneSync : nitro en cours + niveau du
 * kit), donc visibles par tous les joueurs qui voient la voiture, sans aucune particule envoyée par le réseau.
 * <ul>
 * <li><b>Jet</b> par sortie d'échappement : trois cônes imbriqués en mélange additif (cœur bleu-blanc, flamme bleu → orange → rouge, enveloppe
 * rouge diffuse), qui pulsent, ondulent et s'allongent avec la vitesse ; trois « diamants de choc » dans le cœur.</li>
 * <li><b>Lueur</b> additive à la sortie, et <b>halo de chaleur</b> : l'image derrière le jet est recopiée puis redessinée avec un léger décalage
 * ondulant (vraie déformation de l'air). Désactivé avec un pack de shaders (Oculus) ou en graphismes « Fabulous », où l'image n'est pas accessible.</li>
 * <li><b>Backfire</b> au départ et à l'arrêt : gerbe d'étincelles qui rebondissent, boule de feu, détonations, et <b>flash</b> de lumière
 * dynamique si un mod de lumières dynamiques (Sodium/Lamb Dynamic Lights) est présent ; la voiture éclaire aussi un peu pendant l'injection.</li>
 * <li><b>Traînée</b> de braises laissées derrière la voiture et un peu de fumée.</li>
 * </ul>
 * Positions des sorties : réglage par modèle du serveur (config/minenorth-echappements.json) s'il existe, sinon lues dans la définition MTS
 * (TuneAccess.exhausts), sinon deux sorties à l'arrière d'après les boîtes de collision. Budget : 700 particules au total, jets jusqu'à 128 blocs.
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT)
public final class NitroFlames {
    private NitroFlames() {}

    private static final double MAX_DISTANCE = 128, FX_DISTANCE = 64, HAZE_DISTANCE = 40;
    private static final int MAX_PARTICLES = 700, RINGS = 8, SIDES = 10;
    /** Longueur du jet à l'arrêt (blocs) par niveau de kit, rayon à la sortie. */
    private static final double[] LENGTH = {1.2, 1.4, 1.9, 2.4};
    private static final double RADIUS = 0.15;

    /** Véhicule (identifiant d'entité) -> état des flammes. */
    private static final Map<Integer, Car> CARS = new HashMap<>();
    private static final List<Particle> PARTICLES = new ArrayList<>();
    /** Sorties imposées par modèle (« pack:modele »), reçues du serveur. */
    private static volatile Map<String, List<float[]>> fits = Map.of();
    private static final RandomSource RNG = RandomSource.create();

    private static final class Car {
        final int id;
        boolean on;
        int level = 1, flash, popIn = -1, popsLeft, missing, pipesAge;
        float intensity, prevIntensity, flicker = 1;
        double speedKmh, vx, vy, vz;
        List<double[]> pipes;

        Car(int id) { this.id = id; }
    }

    /** kind : 0 braise (traînée), 1 étincelle (traînée lumineuse, rebondit), 2 boule de feu du backfire (grossit et s'éteint). */
    private static final class Particle {
        double x, y, z, px, py, pz, vx, vy, vz;
        int age, life, kind;
        float size, r, g, b;
    }

    // ------------------------------------------------------------------ état reçu du serveur

    /** Appelé à chaque réception du réglage d'un véhicule (ClientTune) : départ / arrêt de la nitro. */
    public static void update(int entityId, boolean on, int level) {
        Car c = CARS.computeIfAbsent(entityId, Car::new);
        c.level = Mth.clamp(level, 1, 3);
        c.missing = 0;
        if (on != c.on) {   // un véhicule inconnu est considéré à l'arrêt : son premier « nitro en cours » déclenche le backfire
            Minecraft mc = Minecraft.getInstance();
            Entity v = mc.level == null ? null : mc.level.getEntity(entityId);
            if (v != null) backfire(mc, v, c, on);
        }
        c.on = on;
        if (!on && c.intensity <= 0 && c.popsLeft == 0) CARS.remove(entityId);
    }

    public static void receiveFits(Map<String, List<float[]>> m) {
        fits = Map.copyOf(m);
        for (Car c : CARS.values()) c.pipes = null;
    }

    /** Luminosité (0-15) du véhicule pour les lumières dynamiques : flash au backfire, lueur pendant l'injection. */
    static int luminance(Entity e) {
        Car c = CARS.get(e.getId());
        if (c == null) return 0;
        if (c.flash > 0) return 15;
        return c.intensity > 0.3f ? 10 : 0;
    }

    // ------------------------------------------------------------------ sorties d'échappement

    private static List<double[]> pipes(Entity v) {
        String key = TuneAccess.modelKey(v);
        List<float[]> fit = key.isEmpty() ? null : fits.get(key);
        List<double[]> out = new ArrayList<>();
        if (fit != null) {
            for (float[] f : fit) {
                double l = Math.max(1e-6, Math.sqrt(f[3] * f[3] + f[4] * f[4] + f[5] * f[5]));
                out.add(new double[]{f[0], f[1], f[2], f[3] / l, f[4] / l, f[5] / l});
            }
            return out;
        }
        out = TuneAccess.exhausts(v);
        if (!out.isEmpty()) return out;
        double[] g = TuneAccess.rearGeometry(v);   // {arrière, demi-largeur, bas}
        double rear = g != null ? g[0] + 0.02 : 1.3, side = g != null ? Math.min(0.55, g[1] * 0.4) : 0.45, y = (g != null ? g[2] : 0) + 0.3;
        out.add(new double[]{side, y, -rear, 0, 0, -1});
        out.add(new double[]{-side, y, -rear, 0, 0, -1});
        return out;
    }

    /** Pose du véhicule (voir TuneAccess.pose), avec repli sur l'entité Forge. */
    private static double[] pose(Entity v, float partial) {
        double[] p = TuneAccess.pose(v, partial);
        if (p != null) return p;
        double yaw = Math.toRadians(Mth.lerp(partial, v.yRotO, v.getYRot())), cs = Math.cos(yaw), sn = Math.sin(yaw);
        return new double[]{Mth.lerp(partial, v.xo, v.getX()), Mth.lerp(partial, v.yo, v.getY()), Mth.lerp(partial, v.zo, v.getZ()),
                cs, 0, -sn, 0, 1, 0, sn, 0, cs};
    }

    /** Sortie dans le monde : {x, y, z, dx, dy, dz}. */
    private static double[] pipeWorld(double[] p, double[] pipe) {
        double x = pipe[0], y = pipe[1], z = pipe[2], dx = pipe[3], dy = pipe[4], dz = pipe[5];
        double wx = p[3] * dx + p[4] * dy + p[5] * dz, wy = p[6] * dx + p[7] * dy + p[8] * dz, wz = p[9] * dx + p[10] * dy + p[11] * dz;
        double l = Math.max(1e-6, Math.sqrt(wx * wx + wy * wy + wz * wz));
        return new double[]{p[0] + p[3] * x + p[4] * y + p[5] * z, p[1] + p[6] * x + p[7] * y + p[8] * z, p[2] + p[9] * x + p[10] * y + p[11] * z,
                wx / l, wy / l, wz / l};
    }

    // ------------------------------------------------------------------ backfire, particules

    private static void backfire(Minecraft mc, Entity v, Car c, boolean start) {
        if (mc.player == null || v.distanceToSqr(mc.player) > FX_DISTANCE * FX_DISTANCE) return;
        if (c.pipes == null) c.pipes = pipes(v);
        c.flash = 6;
        pop(mc, v, c, start ? 14 : 10, start ? 0.75f : 0.6f);
        if (!start) { c.popsLeft = 2; c.popIn = 3 + RNG.nextInt(3); }
    }

    /** Une détonation : boule de feu + gerbe d'étincelles à chaque sortie, son, flash. */
    private static void pop(Minecraft mc, Entity v, Car c, int sparks, float pitch) {
        double[] p = pose(v, 1f);
        for (double[] pipe : c.pipes) {
            double[] w = pipeWorld(p, pipe);
            Particle ball = add(w[0] + w[3] * 0.25, w[1] + w[4] * 0.25, w[2] + w[5] * 0.25, w[3] * 0.06 + c.vx, w[4] * 0.06 + c.vy, w[5] * 0.06 + c.vz,
                    2, 5, 0.55f, 1f, 0.62f, 0.25f);
            if (ball == null) break;
            for (int i = 0; i < sparks; i++) {
                double sp = 0.18 + RNG.nextDouble() * 0.28;
                double dx = w[3] + (RNG.nextDouble() - 0.5) * 1.2, dy = w[4] + RNG.nextDouble() * 0.7, dz = w[5] + (RNG.nextDouble() - 0.5) * 1.2;
                double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                add(w[0], w[1], w[2], dx / l * sp + c.vx * 0.5, dy / l * sp + c.vy * 0.5, dz / l * sp + c.vz * 0.5,
                        1, 8 + RNG.nextInt(10), 0.035f, 1f, 0.72f + RNG.nextFloat() * 0.2f, 0.3f);
            }
        }
        double[] w = pipeWorld(p, c.pipes.get(0));
        mc.level.playLocalSound(w[0], w[1], w[2], SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.NEUTRAL, 1.4f, pitch + RNG.nextFloat() * 0.15f, false);
        mc.level.playLocalSound(w[0], w[1], w[2], SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 0.3f, 1.7f + RNG.nextFloat() * 0.2f, false);
        c.flash = Math.max(c.flash, 4);
    }

    private static Particle add(double x, double y, double z, double vx, double vy, double vz, int kind, int life, float size, float r, float g, float b) {
        if (PARTICLES.size() >= MAX_PARTICLES) return null;
        Particle q = new Particle();
        q.x = q.px = x; q.y = q.py = y; q.z = q.pz = z; q.vx = vx; q.vy = vy; q.vz = vz;
        q.kind = kind; q.life = life; q.size = size; q.r = r; q.g = g; q.b = b;
        PARTICLES.add(q);
        return q;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { CARS.clear(); PARTICLES.clear(); return; }
        if (mc.isPaused()) return;
        for (Iterator<Car> it = CARS.values().iterator(); it.hasNext(); ) {
            Car c = it.next();
            Entity v = mc.level.getEntity(c.id);
            if (v == null || v.isRemoved()) {
                if (++c.missing > 600) it.remove();
                continue;
            }
            c.missing = 0;
            c.prevIntensity = c.intensity;
            c.intensity = c.on ? Math.min(1f, c.intensity + 0.34f) : Math.max(0f, c.intensity - 0.25f);
            c.flicker = 0.85f + RNG.nextFloat() * 0.3f;
            if (c.flash > 0) c.flash--;
            if (c.pipes == null || ++c.pipesAge > 100) { c.pipes = pipes(v); c.pipesAge = 0; }
            double[] p1 = pose(v, 1f), p0 = pose(v, 0f);
            c.vx = p1[0] - p0[0]; c.vy = p1[1] - p0[1]; c.vz = p1[2] - p0[2];
            c.speedKmh = Math.sqrt(c.vx * c.vx + c.vy * c.vy + c.vz * c.vz) * 72;   // 1 bloc/tick = 20 m/s = 72 km/h
            boolean near = v.distanceToSqr(mc.player) < FX_DISTANCE * FX_DISTANCE;
            if (c.popsLeft > 0 && --c.popIn <= 0) {
                if (near) pop(mc, v, c, 6, 0.55f);
                c.popsLeft--;
                c.popIn = 3 + RNG.nextInt(4);
            }
            if (c.intensity > 0 && near) {
                double len = jetLength(c, (mc.level.getGameTime()) / 20.0);
                for (double[] pipe : c.pipes) {
                    double[] w = pipeWorld(p1, pipe);
                    // braises au bout du jet : elles restent dans le monde, la voiture s'en éloigne -> traînée
                    int n = 1 + (c.speedKmh > 60 ? 1 : 0);
                    for (int i = 0; i < n; i++) {
                        double s = len * (0.55 + RNG.nextDouble() * 0.45);
                        add(w[0] + w[3] * s, w[1] + w[4] * s, w[2] + w[5] * s,
                                w[3] * 0.04 + (RNG.nextDouble() - 0.5) * 0.05, 0.015 + RNG.nextDouble() * 0.03, w[5] * 0.04 + (RNG.nextDouble() - 0.5) * 0.05,
                                0, 10 + RNG.nextInt(10), 0.07f + RNG.nextFloat() * 0.05f, 1f, 0.45f + RNG.nextFloat() * 0.25f, 0.12f);
                    }
                    if ((mc.level.getGameTime() + c.id) % 3 == 0)
                        mc.level.addParticle(c.speedKmh > 40 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                                w[0] + w[3] * len, w[1] + w[4] * len + 0.1, w[2] + w[5] * len, w[3] * 0.02, 0.02, w[5] * 0.02);
                }
            }
            if (!c.on && c.intensity <= 0 && c.popsLeft == 0 && c.flash == 0) it.remove();
        }
        for (Iterator<Particle> it = PARTICLES.iterator(); it.hasNext(); ) {
            Particle q = it.next();
            q.px = q.x; q.py = q.y; q.pz = q.z;
            if (++q.age >= q.life) { it.remove(); continue; }
            q.x += q.vx; q.y += q.vy; q.z += q.vz;
            switch (q.kind) {
                case 0 -> { q.vx *= 0.9; q.vy = q.vy * 0.9 + 0.004; q.vz *= 0.9; }
                case 1 -> {
                    q.vx *= 0.94; q.vy = q.vy * 0.94 - 0.03; q.vz *= 0.94;
                    if (q.vy < 0 && !mc.level.getBlockState(BlockPos.containing(q.x, q.y, q.z)).getCollisionShape(mc.level, BlockPos.containing(q.x, q.y, q.z)).isEmpty()) {
                        q.y = q.py; q.vy = -q.vy * 0.35; q.vx *= 0.7; q.vz *= 0.7;   // rebond sur le bitume
                    }
                }
                default -> { q.vx *= 0.8; q.vy *= 0.8; q.vz *= 0.8; }
            }
        }
    }

    /** Longueur du jet : niveau du kit, vitesse (jusqu'à x2,2), pulsation à deux fréquences, et montée / descente à l'allumage / à l'arrêt. */
    private static double jetLength(Car c, double time) {
        double phase = c.id * 1.7;
        double pulse = 1 + 0.10 * Math.sin(time * Math.PI * 2 * 7.3 + phase) + 0.06 * Math.sin(time * Math.PI * 2 * 13.1 + phase * 2);
        return LENGTH[c.level] * (1 + Math.min(1.2, c.speedKmh / 100)) * pulse;
    }

    // ------------------------------------------------------------------ rendu

    /** Mélange additif sans écriture de profondeur (les couches du jet ne se masquent pas entre elles), visible des deux côtés. */
    private static final class FlameType extends RenderType {
        private FlameType(String n, VertexFormat f, VertexFormat.Mode m, int s, boolean a, boolean b, Runnable x, Runnable y) { super(n, f, m, s, a, b, x, y); }

        static final RenderType FLAME = create("minenorthbanc_nitro_flame", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536, false, false,
                CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL).setOutputState(WEATHER_TARGET).createCompositeState(false));
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (CARS.isEmpty() && PARTICLES.isEmpty()) return;
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

        // jets visibles : {car, entité, pose}
        List<Object[]> jets = new ArrayList<>();
        for (Car car : CARS.values()) {
            float k = Mth.lerp(partial, car.prevIntensity, car.intensity);
            if (k <= 0.01f || car.pipes == null) continue;
            Entity v = mc.level.getEntity(car.id);
            if (v == null || v.isRemoved() || v.distanceToSqr(c) > MAX_DISTANCE * MAX_DISTANCE) continue;
            jets.add(new Object[]{car, v, pose(v, partial), k});
        }

        if (!jets.isEmpty()) heatHaze(mc, buf, e, m, c, jets, time);

        VertexConsumer vc = buf.getBuffer(FlameType.FLAME);
        Vector3f left = cam.getLeftVector(), up = cam.getUpVector();
        for (Object[] j : jets) {
            Car car = (Car) j[0];
            double[] p = (double[]) j[2];
            float k = (float) j[3];
            double len = jetLength(car, time) * (0.35 + 0.65 * k);
            for (int i = 0; i < car.pipes.size(); i++) {
                double[] w = pipeWorld(p, car.pipes.get(i));
                jet(vc, m, w, len, k * car.flicker, car.level, time, car.id * 1.7 + i * 2.3);
                // lueur de sortie et diamants de choc (billboards face à la caméra)
                glow(vc, m, left, up, w[0] + w[3] * 0.1, w[1] + w[4] * 0.1, w[2] + w[5] * 0.1, 0.55 * (0.8 + 0.2 * car.flicker), 1f, 0.55f, 0.25f, 0.45f * k);
                for (int d = 0; d < 3; d++) {
                    double s = len * 0.5 * (0.10 + d * 0.10);
                    glow(vc, m, left, up, w[0] + w[3] * s, w[1] + w[4] * s, w[2] + w[5] * s, RADIUS * (0.75 - d * 0.12), 0.85f, 0.93f, 1f, (0.75f - d * 0.18f) * k);
                }
            }
            if (car.flash > 0) {
                double[] w = pipeWorld(p, car.pipes.get(0));
                glow(vc, m, left, up, w[0], w[1] + 0.2, w[2], 2.2, 1f, 0.6f, 0.3f, 0.10f * car.flash);
            }
        }
        for (Particle q : PARTICLES) particle(vc, m, left, up, c, q, partial);
        buf.endBatch(FlameType.FLAME);
        ps.popPose();
    }

    /** Couleur le long du jet (s : 0 à la sortie, 1 au bout) : bleu-blanc -> bleu -> jaune-orange -> orange -> rouge ; le niveau III finit en violet. */
    private static float[] gradient(double s, int level) {
        float[][] stops = {
                {0f, 0.80f, 0.90f, 1f}, {0.18f, 0.35f, 0.55f, 1f}, {level == 2 ? 0.45f : 0.38f, level == 2 ? 0.70f : 1f, level == 2 ? 0.75f : 0.78f, level == 2 ? 1f : 0.35f},
                {0.62f, level == 3 ? 0.95f : 1f, level == 3 ? 0.35f : 0.45f, level == 3 ? 0.55f : 0.08f}, {1f, level == 3 ? 0.65f : 0.85f, level == 3 ? 0.15f : 0.10f, level == 3 ? 0.95f : 0.04f}};
        for (int i = 0; i < stops.length - 1; i++) {
            float[] a = stops[i], b = stops[i + 1];
            if (s <= b[0]) {
                float t = (float) ((s - a[0]) / Math.max(1e-6, b[0] - a[0]));
                return new float[]{Mth.lerp(t, a[1], b[1]), Mth.lerp(t, a[2], b[2]), Mth.lerp(t, a[3], b[3])};
            }
        }
        float[] l = stops[stops.length - 1];
        return new float[]{l[1], l[2], l[3]};
    }

    /** Un jet : trois cônes imbriqués {rayon, longueur, opacité} ; profil évasé puis effilé, anneaux ondulants (turbulence). */
    private static void jet(VertexConsumer vc, Matrix4f m, double[] w, double len, float k, int level, double time, double phase) {
        double ax = w[3], ay = w[4], az = w[5];
        // base perpendiculaire à l'axe
        double ux, uy, uz;
        if (Math.abs(ay) < 0.9) { ux = az; uy = 0; uz = -ax; } else { ux = 1; uy = 0; uz = 0; }
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul;
        double vx = ay * uz - az * uy, vy = az * ux - ax * uz, vz = ax * uy - ay * ux;
        double[][] layers = {{1.35, 1.0, 0.16}, {0.85, 0.85, 0.32}, {0.42, 0.45, 0.42}};
        double[] cx = new double[RINGS + 1], cy = new double[RINGS + 1], cz = new double[RINGS + 1], rr = new double[RINGS + 1];
        float[][] col = new float[RINGS + 1][];
        float[] al = new float[RINGS + 1];
        for (int layer = 0; layer < layers.length; layer++) {
            double rk = layers[layer][0], ll = len * layers[layer][1];
            for (int j = 0; j <= RINGS; j++) {
                double s = (double) j / RINGS;
                double wob = 0.05 * s * len * Math.sin(s * 14 - time * 30 + phase + layer);
                double wob2 = 0.04 * s * len * Math.cos(s * 9 - time * 23 + phase * 1.3);
                cx[j] = w[0] + ax * s * ll + ux * wob + vx * wob2;
                cy[j] = w[1] + ay * s * ll + uy * wob + vy * wob2 + 0.04 * s * s * ll;   // l'air chaud monte un peu
                cz[j] = w[2] + az * s * ll + uz * wob + vz * wob2;
                rr[j] = RADIUS * rk * (0.55 + 1.7 * s) * Math.pow(1 - s, 0.85) * (1 + 0.12 * Math.sin(s * 11 - time * 25 + phase)) * (0.8 + 0.2 * k);
                col[j] = layer < 2 ? gradient(s * layers[layer][1], level) : gradient(Math.min(1, s * 0.35), level);
                al[j] = (float) (layers[layer][2] * Math.pow(1 - s, 0.6) * Math.min(1, s / 0.06 + 0.15) * k);
            }
            for (int j = 0; j < RINGS; j++) {
                for (int i = 0; i < SIDES; i++) {
                    double a0 = Math.PI * 2 * i / SIDES, a1 = Math.PI * 2 * (i + 1) / SIDES;
                    double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
                    ring(vc, m, cx[j], cy[j], cz[j], rr[j], c0, s0, ux, uy, uz, vx, vy, vz, col[j], al[j]);
                    ring(vc, m, cx[j], cy[j], cz[j], rr[j], c1, s1, ux, uy, uz, vx, vy, vz, col[j], al[j]);
                    ring(vc, m, cx[j + 1], cy[j + 1], cz[j + 1], rr[j + 1], c1, s1, ux, uy, uz, vx, vy, vz, col[j + 1], al[j + 1]);
                    ring(vc, m, cx[j + 1], cy[j + 1], cz[j + 1], rr[j + 1], c0, s0, ux, uy, uz, vx, vy, vz, col[j + 1], al[j + 1]);
                }
            }
        }
    }

    private static void ring(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz, double r, double c, double s,
                             double ux, double uy, double uz, double vx, double vy, double vz, float[] col, float a) {
        vc.vertex(m, (float) (cx + (ux * c + vx * s) * r), (float) (cy + (uy * c + vy * s) * r), (float) (cz + (uz * c + vz * s) * r))
                .color(col[0], col[1], col[2], Mth.clamp(a, 0f, 1f)).endVertex();
    }

    /** Disque lumineux face à la caméra : opaque au centre, transparent au bord (éventail de quadrilatères dont deux sommets sont au centre). */
    private static void glow(VertexConsumer vc, Matrix4f m, Vector3f left, Vector3f up, double x, double y, double z, double r,
                             float cr, float cg, float cb, float a) {
        if (a <= 0.005f) return;
        int n = 12;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            float x0 = (float) (x + (left.x() * Math.cos(a0) + up.x() * Math.sin(a0)) * r), y0 = (float) (y + (left.y() * Math.cos(a0) + up.y() * Math.sin(a0)) * r),
                    z0 = (float) (z + (left.z() * Math.cos(a0) + up.z() * Math.sin(a0)) * r);
            float x1 = (float) (x + (left.x() * Math.cos(a1) + up.x() * Math.sin(a1)) * r), y1 = (float) (y + (left.y() * Math.cos(a1) + up.y() * Math.sin(a1)) * r),
                    z1 = (float) (z + (left.z() * Math.cos(a1) + up.z() * Math.sin(a1)) * r);
            vc.vertex(m, (float) x, (float) y, (float) z).color(cr, cg, cb, Mth.clamp(a, 0f, 1f)).endVertex();
            vc.vertex(m, (float) x, (float) y, (float) z).color(cr, cg, cb, Mth.clamp(a, 0f, 1f)).endVertex();
            vc.vertex(m, x0, y0, z0).color(cr, cg, cb, 0f).endVertex();
            vc.vertex(m, x1, y1, z1).color(cr, cg, cb, 0f).endVertex();
        }
    }

    private static void particle(VertexConsumer vc, Matrix4f m, Vector3f left, Vector3f up, Vec3 cam, Particle q, float partial) {
        double x = Mth.lerp(partial, q.px, q.x), y = Mth.lerp(partial, q.py, q.y), z = Mth.lerp(partial, q.pz, q.z);
        float life = (q.age + partial) / q.life, fade = 1 - life;
        switch (q.kind) {
            case 0 -> glow(vc, m, left, up, x, y, z, q.size * (0.6 + 0.4 * fade), q.r, q.g * (0.6f + 0.4f * fade), q.b, 0.9f * fade);
            case 2 -> {
                glow(vc, m, left, up, x, y, z, q.size * (0.6 + 1.4 * life), q.r, q.g, q.b, 0.85f * fade);
                glow(vc, m, left, up, x, y, z, q.size * 0.45 * (1 + life), 1f, 0.95f, 0.8f, 0.9f * fade);
            }
            default -> {
                // étincelle : trait le long de la vitesse, d'épaisseur constante face à la caméra
                double tx = x - q.vx * 1.6, ty = y - q.vy * 1.6, tz = z - q.vz * 1.6;
                double dx = x - tx, dy = y - ty, dz = z - tz, ex = x - cam.x, ey = y - cam.y, ez = z - cam.z;
                double nx = dy * ez - dz * ey, ny = dz * ex - dx * ez, nz = dx * ey - dy * ex;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1e-9) return;
                double h = q.size / nl;
                nx *= h; ny *= h; nz *= h;
                float a = Mth.clamp(fade * 1.2f, 0f, 1f);
                vc.vertex(m, (float) (x - nx), (float) (y - ny), (float) (z - nz)).color(1f, 0.95f, 0.75f, a).endVertex();
                vc.vertex(m, (float) (x + nx), (float) (y + ny), (float) (z + nz)).color(1f, 0.95f, 0.75f, a).endVertex();
                vc.vertex(m, (float) (tx + nx), (float) (ty + ny), (float) (tz + nz)).color(q.r, q.g, q.b, 0f).endVertex();
                vc.vertex(m, (float) (tx - nx), (float) (ty - ny), (float) (tz - nz)).color(q.r, q.g, q.b, 0f).endVertex();
            }
        }
    }

    // ------------------------------------------------------------------ halo de chaleur (copie de l'image + redessin décalé)

    private static int hazeTex = -1, hazeW, hazeH;
    private static Boolean irisPresent;
    private static java.lang.reflect.Method irisInUse;
    private static Object irisApi;
    private static boolean hazeBroken;

    private static boolean shadersInUse() {
        try {
            if (irisPresent == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisInUse = api.getMethod("isShaderPackInUse");
                irisPresent = true;
            }
        } catch (Throwable t) {
            irisPresent = false;
        }
        try {
            return irisPresent && (boolean) irisInUse.invoke(irisApi);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void heatHaze(Minecraft mc, MultiBufferSource.BufferSource buf, RenderLevelStageEvent e, Matrix4f m, Vec3 cam, List<Object[]> jets, double time) {
        if (hazeBroken || Minecraft.useShaderTransparency() || shadersInUse()) return;
        try {
            buf.endBatch();   // ce qui est en attente est dessiné avant la copie de l'image
            RenderTarget main = mc.getMainRenderTarget();
            int w = main.width, h = main.height;
            if (hazeTex == -1 || w != hazeW || h != hazeH) {
                if (hazeTex != -1) TextureUtil.releaseTextureId(hazeTex);
                hazeTex = TextureUtil.generateTextureId();
                TextureUtil.prepareImage(hazeTex, w, h);
                RenderSystem.bindTexture(hazeTex);
                GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                hazeW = w; hazeH = h;
            }
            main.bindWrite(false);
            RenderSystem.bindTexture(hazeTex);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);

            Matrix4f proj = e.getProjectionMatrix();
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, hazeTex);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            int quads = 0;
            for (Object[] j : jets) {
                Car car = (Car) j[0];
                double[] p = (double[]) j[2];
                float k = (float) j[3];
                double len = jetLength(car, time);
                for (double[] pipe : car.pipes) {
                    double[] wp = pipeWorld(p, pipe);
                    if ((wp[0] - cam.x) * (wp[0] - cam.x) + (wp[1] - cam.y) * (wp[1] - cam.y) + (wp[2] - cam.z) * (wp[2] - cam.z) > HAZE_DISTANCE * HAZE_DISTANCE) continue;
                    quads += hazeRibbon(bb, m, proj, cam, wp, len, k, time + car.id);
                }
            }
            if (quads > 0) BufferUploader.drawWithShader(bb.end()); else bb.end();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        } catch (Throwable t) {
            hazeBroken = true;   // pilote ou mod incompatible : on garde les flammes, sans le halo
            com.mojang.logging.LogUtils.getLogger().warn("[Banc] Halo de chaleur désactivé : {}", t.toString());
        }
    }

    /** Ruban face à la caméra au-dessus et au bout du jet, découpé en grille ; chaque sommet lit l'image à sa place, décalée d'une ondulation. */
    private static int hazeRibbon(BufferBuilder bb, Matrix4f m, Matrix4f proj, Vec3 cam, double[] w, double len, float k, double time) {
        int nS = 7, nW = 3;
        double[][][] pts = new double[nS + 1][nW + 1][];
        for (int i = 0; i <= nS; i++) {
            double s = 0.15 + 1.35 * i / nS;   // de 15 % à 150 % de la longueur du jet
            double cx = w[0] + w[3] * s * len, cy = w[1] + w[4] * s * len + 0.12 * s * len, cz = w[2] + w[5] * s * len;
            double ex = cx - cam.x, ey = cy - cam.y, ez = cz - cam.z;
            double sx = w[4] * ez - w[5] * ey, sy = w[5] * ex - w[3] * ez, sz = w[3] * ey - w[4] * ex;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-6) return 0;
            double half = 0.22 + 0.3 * s;
            sx = sx / sl * half; sy = sy / sl * half; sz = sz / sl * half;
            for (int jj = 0; jj <= nW; jj++) {
                double t = (double) jj / nW * 2 - 1;
                double x = cx + sx * t, y = cy + sy * t, z = cz + sz * t;
                Vector4f v = new Vector4f((float) x, (float) y, (float) z, 1f);
                m.transform(v);
                proj.transform(v);
                if (v.w() <= 0.05f) return 0;
                double u = (v.x() / v.w() + 1) / 2, vv = (v.y() / v.w() + 1) / 2;
                boolean edge = jj == 0 || jj == nW || i == 0 || i == nS;
                double amp = edge ? 0 : 0.0035 * k * Math.min(1, 6 / Math.sqrt(ex * ex + ey * ey + ez * ez));
                u += amp * Math.sin(time * 26 + s * 9 + jj * 2.1);
                vv += amp * Math.cos(time * 31 + s * 7 + jj * 1.3);
                float a = edge ? 0f : (float) (0.9 * k * (1 - Math.abs(s - 0.7) / 0.85));
                pts[i][jj] = new double[]{x, y, z, u, vv, Math.max(0, a)};
            }
        }
        for (int i = 0; i < nS; i++)
            for (int jj = 0; jj < nW; jj++) {
                hv(bb, m, pts[i][jj]); hv(bb, m, pts[i][jj + 1]); hv(bb, m, pts[i + 1][jj + 1]); hv(bb, m, pts[i + 1][jj]);
            }
        return nS * nW;
    }

    private static void hv(BufferBuilder bb, Matrix4f m, double[] p) {
        bb.vertex(m, (float) p[0], (float) p[1], (float) p[2]).uv((float) p[3], (float) p[4]).color(1f, 1f, 1f, (float) p[5]).endVertex();
    }

    // ------------------------------------------------------------------ lumières dynamiques (facultatif)

    @Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        private Setup() {}

        /** Si Sodium Dynamic Lights / LambDynamicLights est là : le véhicule MTS éclaire pendant la nitro et flashe au backfire. */
        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent e) {
            e.enqueueWork(() -> {
                try {
                    Class<?> handlers = Class.forName("dev.lambdaurora.lambdynlights.api.DynamicLightHandlers");
                    Class<?> handler = Class.forName("dev.lambdaurora.lambdynlights.api.DynamicLightHandler");
                    EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(TuneAccess.VEHICLE_ENTITY));
                    if (type == null) return;
                    Object proxy = java.lang.reflect.Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[]{handler}, (o, method, args) -> switch (method.getName()) {
                        case "getLuminance" -> args[0] instanceof Entity en ? luminance(en) : 0;
                        case "isWaterSensitive" -> false;
                        case "hashCode" -> System.identityHashCode(o);
                        case "equals" -> o == args[0];
                        case "toString" -> "MineNorth nitro";
                        default -> null;
                    });
                    handlers.getMethod("registerDynamicLightHandler", EntityType.class, handler).invoke(null, type, proxy);
                    com.mojang.logging.LogUtils.getLogger().info("[Banc] Flash de nitro branché sur les lumières dynamiques.");
                } catch (ClassNotFoundException ignored) {
                    // pas de mod de lumières dynamiques
                } catch (Throwable t) {
                    com.mojang.logging.LogUtils.getLogger().warn("[Banc] Lumières dynamiques indisponibles pour la nitro : {}", t.toString());
                }
            });
        }
    }
}
