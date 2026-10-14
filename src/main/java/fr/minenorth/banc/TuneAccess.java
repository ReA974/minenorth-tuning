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
    /** Adhérence des roues motrices : MTS plafonne la force motrice à masse x friction, la puissance seule ne sert à rien tant que les roues patinent. */
    public static final double[] GRIP = {1.00, 1.10, 1.20, 1.35};
    public static final int MAX_STAGE = 3;

    /** Options indépendantes des stages (rangées dans les données persistantes du véhicule, comme le stage). */
    public static final String BOOST_KEY = "mnrp_boost", AXLE_KEY = "mnrp_axle", TIRES_KEY = "mnrp_tires", BRAKES_KEY = "mnrp_brakes";
    /** Suralimentation (aucune / compresseur / turbo) : puissance ajoutée (part de la puissance d'usine) et usure du moteur. */
    public static final double[] BOOST_POWER = {0, 0.25, 0.45}, BOOST_WEAR = {1.00, 1.15, 1.50};
    /** Rapport de pont (origine / court / long) : un pont court accélère plus fort mais plafonne plus tôt. */
    public static final double[] AXLE = {1.00, 1.15, 0.88};
    /** Pneus (origine / sport / course) : adhérence de traction de toutes les roues et tenue latérale. */
    public static final double[] TIRE_MOTIVE = {1.00, 1.10, 1.20}, TIRE_LATERAL = {1.00, 1.15, 1.30};
    /** Freins (origine / sport / course). */
    public static final double[] BRAKES = {1.00, 1.25, 1.50};

    /** Réglage complet d'un véhicule : niveau de moteur et options. */
    public record Setup(int stage, int boost, int axle, int tires, int brakes) {
        public static final int STAGE = 0, BOOST = 1, AXLE_G = 2, TIRES = 3, BRAKES_G = 4;

        public static Setup read(Entity e) {
            var t = e.getPersistentData();
            return new Setup(clamp(t.getInt(TUNE), MAX_STAGE), clamp(t.getInt(BOOST_KEY), 2), clamp(t.getInt(AXLE_KEY), 2),
                    clamp(t.getInt(TIRES_KEY), 2), clamp(t.getInt(BRAKES_KEY), 2));
        }

        public void write(Entity e) {
            var t = e.getPersistentData();
            t.putInt(TUNE, stage); t.putInt(BOOST_KEY, boost); t.putInt(AXLE_KEY, axle); t.putInt(TIRES_KEY, tires); t.putInt(BRAKES_KEY, brakes);
        }

        public boolean stock() { return stage == 0 && boost == 0 && axle == 0 && tires == 0 && brakes == 0; }

        public int get(int group) {
            return switch (group) { case STAGE -> stage; case BOOST -> boost; case AXLE_G -> axle; case TIRES -> tires; default -> brakes; };
        }

        public Setup with(int group, int v) {
            return switch (group) {
                case STAGE -> new Setup(v, boost, axle, tires, brakes);
                case BOOST -> new Setup(stage, v, axle, tires, brakes);
                case AXLE_G -> new Setup(stage, boost, v, tires, brakes);
                case TIRES -> new Setup(stage, boost, axle, v, brakes);
                default -> new Setup(stage, boost, axle, tires, v);
            };
        }

        private static int clamp(int v, int max) { return Math.max(0, Math.min(max, v)); }
    }

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

    /** Friction d'usine d'une roue (définition MTS moins la perte d'usure), ou NaN si illisible ou pneu crevé. */
    private static double factoryFriction(Object wheel, String defField) {
        try {
            if (valueOf(field(wheel, "flatVar")) > 0) return Double.NaN;   // pneu crevé : on ne touche pas
            double base = number(field(field(wheel, "definition"), "ground"), defField, Double.NaN);
            java.lang.reflect.Method m = wheel.getClass().getDeclaredMethod("getFrictionLoss");
            m.setAccessible(true);
            return base - ((Number) m.invoke(wheel)).doubleValue();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private static List<Object> wheels(Entity e) {
        List<Object> out = new ArrayList<>();
        try {
            if (field(inner(e), "parts") instanceof Iterable<?> parts)
                for (Object p : parts) if (p.getClass().getSimpleName().equals("PartGroundDevice")) out.add(p);
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * Règle les roues (valeurs d'usine x facteur : répétable sans cumul) : traction x adhérence du stage (roues motrices seulement)
     * x pneus (toutes les roues), tenue latérale x pneus. Une roue illisible est ignorée.
     */
    private static void applyWheels(Entity e, Setup s, List<Object> engines) {
        java.util.Set<Object> driven = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object eng : engines) if (field(eng, "drivenWheels") instanceof Iterable<?> dw) for (Object w : dw) driven.add(w);
        for (Object w : wheels(e)) {
            double motive = factoryFriction(w, "motiveFriction"), lateral = factoryFriction(w, "lateralFriction");
            try {
                if (!Double.isNaN(motive) && motive > 0)
                    set(w, "motiveFrictionVar", motive * (driven.contains(w) ? GRIP[s.stage()] : 1.0) * TIRE_MOTIVE[s.tires()]);
                if (!Double.isNaN(lateral) && lateral > 0) set(w, "lateralFrictionVar", lateral * TIRE_LATERAL[s.tires()]);
            } catch (Throwable ignored) {}
        }
    }

    /** Pont et freins : variables du véhicule lui-même. Faux si la valeur n'a pas été prise (véhicule illisible). */
    private static boolean applyChassis(Entity e, Setup s) {
        Object v = inner(e);
        Object motorized = field(field(v, "definition"), "motorized");
        if (v == null || motorized == null) return s.axle() == 0 && s.brakes() == 0;
        try {
            double axle = number(motorized, "axleRatio", 0), brake = number(motorized, "brakingFactor", 0);
            if (axle > 0) set(v, "axleRatioVar", axle * AXLE[s.axle()]);
            if (brake > 0) set(v, "brakingFactorVar", brake * BRAKES[s.brakes()]);
            return true;
        } catch (Throwable t) {
            return s.axle() == 0 && s.brakes() == 0;
        }
    }

    /**
     * Applique un réglage complet (valeurs d'usine x facteur : appel répétable sans cumul).
     * Retourne faux si aucun moteur n'a pu être réglé ou si une valeur n'a pas été prise en compte.
     */
    public static boolean applyTune(Entity e, Setup s) {
        boolean any = false;
        List<Object> engines = engines(e);
        for (Object eng : engines) {
            Object def = engineDef(eng);
            if (def == null) continue;
            try {
                double power = number(def, "fuelConsumption", 0) * POWER[s.stage()];
                set(eng, "fuelConsumptionVar", power);
                set(eng, "maxRPMVar", number(def, "maxRPM", 0) * RPM[s.stage()]);
                setOptional(eng, "revLimitRPMVar", number(def, "revlimitRPM", 0) * RPM[s.stage()]);
                setOptional(eng, "maxSafeRPMVar", number(def, "maxSafeRPM", 0) * RPM[s.stage()]);
                setOptional(eng, "wearFactorVar", number(def, "engineWearFactor", 0) * WEAR[s.stage()]);
                // Suralimentation : MTS ajoute (consommation du compresseur x rendement) à la force motrice, et un rendement > 1 multiplie l'usure.
                double sc = number(def, "superchargerFuelConsumption", 0), eff = BOOST_WEAR[s.boost()];
                if (sc > 0) {
                    if (s.boost() > 0) return false;   // moteur déjà suralimenté d'usine : on n'y touche pas
                } else if (s.boost() > 0) {
                    set(eng, "superchargerEfficiencyVar", eff);
                    set(eng, "superchargerFuelConsumptionVar", number(def, "fuelConsumption", 0) * BOOST_POWER[s.boost()] / eff);
                } else {
                    try {
                        set(eng, "superchargerEfficiencyVar", number(def, "superchargerEfficiency", 0));
                        set(eng, "superchargerFuelConsumptionVar", 0);
                    } catch (Throwable ignored) {}
                }
                if (power > 0 && Math.abs(valueOf(field(eng, "fuelConsumptionVar")) - power) > 1e-3) return false;   // valeur refusée
                any = true;
            } catch (Throwable t) {
                return false;
            }
        }
        if (!any) return false;
        applyWheels(e, s, engines);
        return applyChassis(e, s);
    }
}
