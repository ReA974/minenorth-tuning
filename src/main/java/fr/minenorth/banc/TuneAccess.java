package fr.minenorth.banc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Seul point de contact du banc avec Immersive Vehicles (MTS), par réflexion (aucune classe MTS à la compilation ; lu dans
 * Immersive Vehicles 24.0.0). Un véhicule est une entité {@code mts:builder_existing} dont le champ « entity » est le véhicule MTS.
 * Dans MTS, la puissance d'un moteur est son coefficient « fuelConsumption » (force motrice proportionnelle, consommation de
 * carburant proportionnelle) ; les moteurs portent des variables par instance (fuelConsumptionVar, maxRPMVar...) qu'on règle ici.
 * Tout est protégé : si MTS change, le banc répond « moteur non pris en charge » au lieu de planter.
 */
public final class TuneAccess {
    public static final String VEHICLE_ENTITY = "mts:builder_existing";
    /** Niveau de reprogrammation (0 = origine), rangé dans les données persistantes du véhicule (donc dans son NBT, même rangé au garage). */
    public static final String TUNE = "mnrp_tune";

    /** Niveaux : puissance (la « consommation » MTS est la force motrice), régime maximal, usure du moteur. */
    public static final double[] POWER = {1.00, 1.25, 1.50, 2.00};
    public static final double[] RPM = {1.00, 1.03, 1.06, 1.10};
    public static final double[] WEAR = {1.00, 1.30, 1.70, 2.30};
    public static final int MAX_STAGE = 3;

    /** Réglages d'un moteur : indice de puissance (coefficient MTS), régime maximal, nombre de rapports. */
    public record Engine(double power, double maxRpm, int gears) {}

    private TuneAccess() {}

    public static boolean isVehicle(Entity e) {
        ResourceLocation k = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return k != null && k.toString().equals(VEHICLE_ENTITY);
    }

    public static Entity nearest(ServerLevel level, Vec3 pos, double radius) {
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : level.getEntities((Entity) null, new AABB(pos, pos).inflate(radius), TuneAccess::isVehicle)) {
            double d = e.position().distanceToSqr(pos);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    private static Object field(Object o, String name) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static double number(Object o, String name, double fallback) {
        return field(o, name) instanceof Number n ? n.doubleValue() : fallback;
    }

    private static Object inner(Entity e) { return field(e, "entity"); }

    /** Nom du modèle (sous-définition MTS), ou l'identifiant système si illisible. */
    public static String name(Entity e) {
        try {
            if (field(field(inner(e), "subDefinition"), "name") instanceof String s && !s.isBlank()) return s;
        } catch (Throwable ignored) {}
        CompoundTag t = new CompoundTag();
        e.saveWithoutId(t);
        String sys = t.getString("systemName");
        return sys.isEmpty() ? "Véhicule" : sys;
    }

    private static List<Object> engines(Entity e) {
        List<Object> out = new ArrayList<>();
        try {
            if (field(inner(e), "parts") instanceof Iterable<?> parts)
                for (Object p : parts) if (p.getClass().getSimpleName().equals("PartEngine")) out.add(p);
        } catch (Throwable ignored) {}
        return out;
    }

    private static Object engineDef(Object engine) { return field(field(engine, "definition"), "engine"); }

    private static double valueOf(Object var) { return field(var, "currentValue") instanceof Number n ? n.doubleValue() : Double.NaN; }

    /** Réglages d'usine du premier moteur (définition MTS), ou null s'il n'y en a pas ou si MTS est illisible. */
    public static Engine factory(Entity e) {
        for (Object eng : engines(e)) {
            Object def = engineDef(eng);
            if (def == null) continue;
            int gears = field(def, "gearRatios") instanceof java.util.Collection<?> c ? c.size() : 0;
            return new Engine(number(def, "fuelConsumption", 0), number(def, "maxRPM", 0), gears);
        }
        return null;
    }

    /** Réglages actuellement appliqués (variables du moteur), ou null. */
    public static Engine current(Entity e) {
        for (Object eng : engines(e)) {
            Object def = engineDef(eng);
            if (def == null) continue;
            int gears = field(def, "gearRatios") instanceof java.util.Collection<?> c ? c.size() : 0;
            return new Engine(valueOf(field(eng, "fuelConsumptionVar")), valueOf(field(eng, "maxRPMVar")), gears);
        }
        return null;
    }

    public static int stageOf(Entity e) { return Math.max(0, Math.min(MAX_STAGE, e.getPersistentData().getInt(TUNE))); }

    private static void set(Object engine, String varField, double value) throws ReflectiveOperationException {
        Object var = field(engine, varField);
        if (var == null) throw new NoSuchFieldException(varField);
        if (Math.abs(valueOf(var) - value) < 1e-6) return;
        var.getClass().getMethod("setTo", double.class, boolean.class).invoke(var, value, true);
    }

    private static void setOptional(Object eng, String var, double value) {
        try { if (value > 0) set(eng, var, value); } catch (Throwable ignored) {}
    }

    /**
     * Applique un niveau aux moteurs (valeurs d'usine x facteur : appel répétable sans cumul).
     * Retourne faux si aucun moteur n'a pu être réglé ou si la valeur n'a pas été prise en compte.
     */
    public static boolean applyTune(Entity e, int stage) {
        stage = Math.max(0, Math.min(MAX_STAGE, stage));
        boolean any = false;
        for (Object eng : engines(e)) {
            Object def = engineDef(eng);
            if (def == null) continue;
            try {
                double power = number(def, "fuelConsumption", 0) * POWER[stage];
                set(eng, "fuelConsumptionVar", power);
                set(eng, "maxRPMVar", number(def, "maxRPM", 0) * RPM[stage]);
                setOptional(eng, "revLimitRPMVar", number(def, "revlimitRPM", 0) * RPM[stage]);
                setOptional(eng, "maxSafeRPMVar", number(def, "maxSafeRPM", 0) * RPM[stage]);
                setOptional(eng, "wearFactorVar", number(def, "engineWearFactor", 0) * WEAR[stage]);
                if (power > 0 && Math.abs(valueOf(field(eng, "fuelConsumptionVar")) - power) > 1e-3) return false;   // valeur refusée
                any = true;
            } catch (Throwable t) {
                return false;
            }
        }
        return any;
    }
}
