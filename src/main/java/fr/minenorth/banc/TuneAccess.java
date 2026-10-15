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
    /**
     * Puissance de moteur inchangée (15/10) : plus de puissance que d'adhérence fait PATINER les roues dans MTS (la force est plafonnée par
     * masse x adhérence), et la voiture devient plus lente. L'effet du stage est une poussée directe (STAGE_ACCEL) tant que le conducteur accélère.
     */
    public static final double[] POWER = {1.00, 1.00, 1.00, 1.00};
    /** Poussée des stages : blocs/tick gagnés à chaque tick plein gaz (x1440 = km/h gagnés par seconde) ; plafond de vitesse en blocs/tick. */
    public static final double[] STAGE_ACCEL = {0, 0.004, 0.008, 0.014};
    public static final double STAGE_CAP = 2.6;
    public static final double[] RPM = {1.00, 1.00, 1.00, 1.00};   // régime inchangé (15/10) : seule la puissance monte
    public static final double[] WEAR = {1.00, 1.30, 1.70, 2.30};
    /** Adhérence des roues motrices : MTS plafonne la force motrice à masse x friction, la puissance seule ne sert à rien tant que les roues patinent. */
    public static final double[] GRIP = {1.00, 1.00, 1.00, 1.00};   // adhérence inchangée (15/10) : les stages ne touchent plus aux roues
    public static final int MAX_STAGE = 3;

    /** Options indépendantes des stages (rangées dans les données persistantes du véhicule, comme le stage). */
    public static final String BOOST_KEY = "mnrp_boost", AXLE_KEY = "mnrp_axle", TIRES_KEY = "mnrp_tires", BRAKES_KEY = "mnrp_brakes";
    /** Suralimentation (aucune / compresseur / turbo) : puissance ajoutée (part de la puissance d'usine) et usure du moteur. */
    public static final double[] BOOST_POWER = {0, 0.35, 0.65}, BOOST_WEAR = {1.00, 1.15, 1.50};
    /** Rapport de pont (origine / court / long) : un pont court accélère plus fort mais plafonne plus tôt. */
    public static final double[] AXLE = {1.00, 1.15, 0.88};
    /** Pneus (origine / sport / course) : adhérence de traction de toutes les roues et tenue latérale. */
    public static final double[] TIRE_MOTIVE = {1.00, 1.10, 1.20}, TIRE_LATERAL = {1.00, 1.15, 1.30};
    /**
     * Nitro (aucune / I / II / III) : kit installé au banc (clé ci-dessous), réserve de gaz en ticks (clé CHARGE), injection tant que la touche est
     * maintenue. Pendant l'injection : puissance multipliée par (1 + NITRO_POWER) et adhérence des roues motrices par NITRO_GRIP.
     */
    public static final String NITRO_KEY = "mnrp_nitro", CHARGE_KEY = "mnrp_nitro_charge";
    public static final double[] NITRO_POWER = {0, 0, 0, 0}, NITRO_GRIP = {1.00, 1.00, 1.00, 1.00},
            /** Poussée directe pendant l'injection (blocs/tick gagnés à chaque tick, vers l'avant) : MTS plafonne la force motrice par l'adhérence, une hausse de puissance seule ne se sent pas. */
            NITRO_ACCEL = {0, 0.008, 0.014, 0.022};
    /** Freins (origine / sport / course). */
    public static final double[] BRAKES = {1.00, 1.60, 2.30};

    /** Réglage complet d'un véhicule : niveau de moteur et options. */
    public record Setup(int stage, int boost, int axle, int tires, int brakes, int nitro) {
        public static final int STAGE = 0, BOOST = 1, AXLE_G = 2, TIRES = 3, BRAKES_G = 4, NITRO_G = 5;

        public static Setup read(Entity e) {
            var t = e.getPersistentData();
            // Turbo / compresseur supprimés (15/10) : la suralimentation mémorisée sur d'anciens véhicules est ignorée.
            return new Setup(clamp(t.getInt(TUNE), MAX_STAGE), 0, clamp(t.getInt(AXLE_KEY), 2),
                    clamp(t.getInt(TIRES_KEY), 2), clamp(t.getInt(BRAKES_KEY), 2), clamp(t.getInt(NITRO_KEY), 3));
        }

        public void write(Entity e) {
            var t = e.getPersistentData();
            t.putInt(TUNE, stage); t.putInt(BOOST_KEY, boost); t.putInt(AXLE_KEY, axle); t.putInt(TIRES_KEY, tires); t.putInt(BRAKES_KEY, brakes);
            t.putInt(NITRO_KEY, nitro);
        }

        public boolean stock() { return stage == 0 && boost == 0 && axle == 0 && tires == 0 && brakes == 0 && nitro == 0; }

        public int get(int group) {
            return switch (group) { case STAGE -> stage; case BOOST -> boost; case AXLE_G -> axle; case TIRES -> tires; case NITRO_G -> nitro; default -> brakes; };
        }

        public Setup with(int group, int v) {
            return switch (group) {
                case STAGE -> new Setup(v, boost, axle, tires, brakes, nitro);
                case BOOST -> new Setup(stage, v, axle, tires, brakes, nitro);
                case AXLE_G -> new Setup(stage, boost, v, tires, brakes, nitro);
                case TIRES -> new Setup(stage, boost, axle, v, brakes, nitro);
                case NITRO_G -> new Setup(stage, boost, axle, tires, brakes, v);
                default -> new Setup(stage, boost, axle, tires, v, nitro);
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

    /** Poussée directe de la nitro : ajoute {@code accel} à la vitesse du véhicule, vers l'avant (plafonnée à ~216 km/h). */
    public static void push(Entity e, double accel) { if (inForwardGear(e)) push(e, accel, 3.0); }

    /** Vrai si le premier moteur est en marche avant (rapport > 0) : au point mort (0) ou en marche arrière (< 0) on ne pousse jamais. Inconnu = vrai. */
    public static boolean inForwardGear(Entity e) {
        for (Object eng : engines(e)) {
            double gear = valueOf(field(eng, "currentGearVar"));
            return Double.isNaN(gear) || gear > 0;
        }
        return true;
    }

    /**
     * Poussée des stages : seulement si le moteur tourne, que le conducteur accélère (accélérateur > 0), sans frein ni frein à main, et pas en marche arrière.
     * Appelée à chaque tick par le serveur (TuneService) et par le client (ClientTune) : la voiture est simulée aux deux endroits.
     */
    public static void drivePush(Entity e, Setup s) {
        if (s.stage() <= 0) return;
        try {
            Object v = inner(e);
            if (v == null) return;
            double throttle = valueOf(field(v, "throttleVar"));
            if (!(throttle > 0.05)) return;
            if (valueOf(field(v, "brakeVar")) > 0.05) return;
            if (field(field(v, "parkingBrakeVar"), "isActive") instanceof Boolean b && b) return;
            for (Object eng : engines(e)) {
                if (field(eng, "running") instanceof Boolean r && !r) return;
                double gear = valueOf(field(eng, "currentGearVar"));
                if (!Double.isNaN(gear) && gear <= 0) return;
                break;
            }
            push(e, STAGE_ACCEL[s.stage()] * Math.min(1.0, throttle), STAGE_CAP);
        } catch (Throwable ignored) {}
    }

    public static void push(Entity e, double accel, double cap) {
        try {
            Object motion = field(inner(e), "motion");
            double[] h = heading(e);
            if (motion == null || h == null) return;
            double mx = number(motion, "x", 0), mz = number(motion, "z", 0);
            if (Math.hypot(mx, mz) > cap) return;
            Field fx = findField(motion.getClass(), "x"), fz = findField(motion.getClass(), "z");
            fx.setDouble(motion, mx + h[0] * accel);
            fz.setDouble(motion, mz + h[1] * accel);
        } catch (Throwable ignored) {}
    }

    /** Direction horizontale (avant) du véhicule : {x, z} normalisé, ou null si MTS est illisible. */
    public static double[] heading(Entity e) {
        try {
            Object p = field(inner(e), "headingVector");
            double x = number(p, "x", Double.NaN), z = number(p, "z", Double.NaN);
            double len = Math.sqrt(x * x + z * z);
            if (Double.isNaN(len) || len < 1e-6) return null;
            return new double[]{x / len, z / len};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Géométrie arrière, lue dans les boîtes de collision de la définition du véhicule (repère du modèle : +z vers l'avant, l'origine près de l'essieu
     * arrière) : {distance de l'arrière derrière l'origine, demi-largeur, bas des boîtes}. Null si illisible. Sert à placer les flammes d'échappement.
     */
    public static double[] rearGeometry(Entity e) {
        try {
            Object veh = inner(e);
            if (veh == null || !(field(original(veh), "collisionGroups") instanceof Iterable<?> groups)) return null;
            double minZ = Double.MAX_VALUE, maxX = 0, minY = Double.MAX_VALUE;
            boolean any = false;
            for (Object g : groups) {
                if (field(g, "isInterior") instanceof Boolean b && b) continue;
                if (field(g, "isForBullets") instanceof Boolean b && b) continue;
                if (!(field(g, "collisions") instanceof Iterable<?> boxes)) continue;
                for (Object box : boxes) {
                    Object pos = field(box, "pos");
                    double w = number(box, "width", 0), h = number(box, "height", 0);
                    double x = number(pos, "x", Double.NaN), y = number(pos, "y", Double.NaN), z = number(pos, "z", Double.NaN);
                    if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) continue;
                    minZ = Math.min(minZ, z - w / 2);
                    maxX = Math.max(maxX, Math.abs(x) + w / 2);
                    minY = Math.min(minY, y - h / 2);
                    any = true;
                }
            }
            return any ? new double[]{Math.max(0.2, -minZ), maxX, Math.max(-0.2, minY)} : null;
        } catch (Throwable t) {
            return null;
        }
    }

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

    private static Object engineDef(Object engine) { return field(original(engine), "engine"); }

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
            double base = number(field(original(wheel), "ground"), defField, Double.NaN);
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
        Object motorized = v == null ? null : field(original(v), "motorized");
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

    /** Le moteur (définition du pack) porte-t-il des « variableModifiers » (cas des moteurs GVP) ? */
    private static boolean hasVariableModifiers(Object engine) {
        return field(field(engine, "definition"), "variableModifiers") instanceof java.util.Collection<?> c && !c.isEmpty();
    }

    private static String f(double v) { return Double.isNaN(v) ? "?" : String.format(java.util.Locale.ROOT, "%.3f", v); }

    /**
     * Diagnostic (/banc debug) : valeurs d'usine et valeurs réellement en vigueur dans MTS (freins, pont, moteur, roues motrices), pour vérifier
     * que le réglage est bien pris en compte par le véhicule.
     */
    public static List<String> diagnose(Entity e) {
        List<String> out = new ArrayList<>();
        Setup s = Setup.read(e);
        out.add("Réglage : stage " + s.stage() + ", suralim. " + s.boost() + ", pont " + s.axle() + ", pneus " + s.tires() + ", freins " + s.brakes());
        Object v = inner(e);
        Object motorized = v == null ? null : field(original(v), "motorized");
        if (v == null || motorized == null) { out.add("Véhicule MTS illisible."); return out; }
        out.add("Freins : usine " + f(number(motorized, "brakingFactor", Double.NaN)) + " -> actuel " + f(valueOf(field(v, "brakingFactorVar")))
                + " (attendu x" + f(BRAKES[s.brakes()]) + ")");
        out.add("Pont : usine " + f(number(motorized, "axleRatio", Double.NaN)) + " -> actuel " + f(valueOf(field(v, "axleRatioVar"))));
        int i = 0;
        for (Object eng : engines(e)) {
            Object def = engineDef(eng);
            out.add("Moteur " + (++i) + " : puissance usine " + f(number(def, "fuelConsumption", Double.NaN)) + " -> actuelle " + f(valueOf(field(eng, "fuelConsumptionVar")))
                    + ", régime max " + f(number(def, "maxRPM", Double.NaN)) + " -> " + f(valueOf(field(eng, "maxRPMVar")))
                    + ", suralim. " + f(valueOf(field(eng, "superchargerFuelConsumptionVar"))));
            out.add("  modificateurs de variables du pack (GVP) : " + (hasVariableModifiers(eng) ? "OUI (la valeur lue varie : au point mort la puissance lue peut être 0)" : "non"));
            // Plafond de régime réel dans MTS : le limiteur (revlimitRPM, -1 = aucun) sinon le régime « sûr » (maxSafeRPM).
            out.add("  limiteur : usine " + f(number(def, "revlimitRPM", Double.NaN)) + " -> " + f(valueOf(field(eng, "revLimitRPMVar")))
                    + " | régime sûr : usine " + f(number(def, "maxSafeRPM", Double.NaN)) + " -> " + f(valueOf(field(eng, "maxSafeRPMVar")))
                    + " | ralenti " + f(valueOf(field(eng, "idleRPMVar"))));
            if (field(eng, "drivenWheels") instanceof Iterable<?> dw) {
                int n = 0;
                for (Object w : dw) {
                    if (++n > 4) break;
                    out.add("  roue motrice " + n + " : adhérence usine " + f(factoryFriction(w, "motiveFriction")) + " -> actuelle " + f(valueOf(field(w, "motiveFrictionVar"))));
                }
            }
        }
        if (i == 0) out.add("Aucun moteur lisible.");
        return out;
    }

    /**
     * Applique un réglage complet (valeurs d'usine x facteur : appel répétable sans cumul).
     * Retourne faux si aucun moteur n'a pu être réglé ou si une valeur n'a pas été prise en compte.
     */
    private interface Step { void run() throws Throwable; }

    /** Exécute une étape du réglage ; une étape qui échoue n'empêche pas les suivantes (journalisée si {@code log} n'est pas nul). */
    private static boolean step(List<String> log, String name, Step st) {
        try {
            st.run();
            return true;
        } catch (Throwable t) {
            if (log != null) log.add("ÉCHEC " + name + " : " + t);
            return false;
        }
    }

    /** Pose une valeur puis relit : journalise un écart (modificateurs du pack, ou valeur refusée par MTS). */
    private static void setChecked(List<String> log, String name, Object holder, String varField, double value) throws ReflectiveOperationException {
        set(holder, varField, value);
        double now = valueOf(field(holder, varField));
        if (log != null && Math.abs(now - value) > 1e-3) log.add("Écart " + name + " : posé " + f(value) + ", relu " + f(now) + " (modificateurs du pack ou valeur refusée)");
    }

    public static boolean applyTune(Entity e, Setup s) {
        return applyTune(e, s, null);
    }

    /**
     * Applique un réglage complet (valeurs d'usine x facteur : appel répétable sans cumul). Chaque étape (puissance, régime, limiteur, usure,
     * suralimentation, roues, pont, freins) est indépendante : l'échec de l'une ne bloque pas les autres (les moteurs GVP ont des modificateurs de
     * variables qui faisaient avorter l'ancien code avant les freins). Retourne faux si rien n'a pu être appliqué, ou si la suralimentation demandée
     * est impossible (moteur déjà suralimenté d'usine). {@code log} reçoit le détail (commande /mnbanc apply).
     */
    public static boolean applyTune(Entity e, Setup s, List<String> log) {
        // MTS remet TOUTES les variables d'un véhicule à leurs valeurs de définition à chaque tick (EntityManager.tickAll -> setVariableDefaults) :
        // poser une variable ne dure que 50 ms. On remplace donc, pour CHAQUE pièce réglée (moteur, roues, véhicule), sa définition par une copie
        // modifiée (propre à l'instance) : MTS relit alors nos valeurs à chaque tick.
        // (l'ancien repli par variables est supprimé : poser une variable envoyait des paquets de synchronisation depuis le client et ne durait qu'un tick)
        boolean defsOk = applyDefinitions(e, s, log);
        return defsOk || s.stage() > 0 || s.nitro() > 0;   // un stage / un kit nitro n'ont plus besoin de définition modifiée pour agir (poussée)
    }

    // ------------------------------------------------------------------ définitions par instance

    private static final java.util.Map<Object, Object> ORIGINAL = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Object UNSAFE;
    private static final java.lang.reflect.Method ALLOCATE;

    static {
        Object u = null;
        java.lang.reflect.Method m = null;
        try {
            Class<?> c = Class.forName("sun.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = f.get(null);
            m = c.getMethod("allocateInstance", Class.class);
        } catch (Throwable ignored) {}
        UNSAFE = u;
        ALLOCATE = m;
    }

    private static Field findField(Class<?> c, String name) {
        for (; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /** Copie superficielle (tous les champs d'instance) : les listes et sous-objets sont partagés, seuls les champs qu'on modifie sont remplacés. */
    private static Object shallowCopy(Object o) throws Exception {
        Object n = ALLOCATE.invoke(UNSAFE, o.getClass());
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(n, f.get(o));
            }
        }
        return n;
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        Field f = findField(o.getClass(), name);
        if (f == null) throw new NoSuchFieldException(name);
        f.set(o, value);
    }

    private static void setNum(Object o, String name, double v) throws Exception {
        Field f = findField(o.getClass(), name);
        if (f == null) throw new NoSuchFieldException(name);
        Class<?> t = f.getType();
        if (t == int.class) f.setInt(o, (int) Math.round(v));
        else if (t == float.class) f.setFloat(o, (float) v);
        else if (t == double.class) f.setDouble(o, v);
        else throw new IllegalArgumentException(name + " : type " + t);
    }

    /** Définition d'usine de la pièce / du véhicule (celle d'avant notre première copie). */
    private static Object original(Object holder) {
        synchronized (ORIGINAL) {
            return ORIGINAL.computeIfAbsent(holder, h -> field(h, "definition"));
        }
    }

    private static void putDefinition(Object holder, Object def) throws Exception {
        Field f = findField(holder.getClass(), "definition");
        if (f == null) throw new NoSuchFieldException("definition");
        f.set(holder, def);
    }

    private static boolean allOne(double... v) {
        for (double d : v) if (Math.abs(d - 1.0) > 1e-9) return false;
        return true;
    }

    private static boolean applyDefinitions(Entity e, Setup s, List<String> log) {
        if (UNSAFE == null || ALLOCATE == null) {
            if (log != null) log.add("Copie de définitions impossible (Unsafe indisponible).");
            return false;
        }
        boolean[] done = {false};
        List<Object> engines = engines(e);
        java.util.Set<Object> driven = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object eng : engines) if (field(eng, "drivenWheels") instanceof Iterable<?> dw) for (Object w : dw) driven.add(w);
        for (Object eng : engines) {
            step(log, "moteur (définition)", () -> {
                Object od = original(eng), oe = field(od, "engine");
                if (oe == null) throw new IllegalStateException("définition de moteur illisible");
                double nitroMul = 1 + (NitroService.isActive(e) ? NITRO_POWER[s.nitro()] : 0);
                if (allOne(POWER[s.stage()] * nitroMul, RPM[s.stage()], WEAR[s.stage()]) && s.boost() == 0) {
                    putDefinition(eng, od);
                    done[0] = true;
                    return;
                }
                Object nd = shallowCopy(od), ne = shallowCopy(oe);
                double fuel = number(oe, "fuelConsumption", 0), rpm = RPM[s.stage()];
                setNum(ne, "fuelConsumption", fuel * POWER[s.stage()] * nitroMul);
                setNum(ne, "maxRPM", number(oe, "maxRPM", 0) * rpm);
                if (number(oe, "revlimitRPM", 0) > 0) setNum(ne, "revlimitRPM", number(oe, "revlimitRPM", 0) * rpm);
                if (number(oe, "maxSafeRPM", 0) > 0) setNum(ne, "maxSafeRPM", number(oe, "maxSafeRPM", 0) * rpm);
                if (number(oe, "engineWearFactor", 0) > 0) setNum(ne, "engineWearFactor", number(oe, "engineWearFactor", 0) * WEAR[s.stage()]);
                double sc = number(oe, "superchargerFuelConsumption", 0);
                if (s.boost() > 0) {
                    if (sc > 0) {
                        if (log != null) log.add("Suralimentation refusée : moteur déjà suralimenté d'usine.");
                    } else {
                        double eff = BOOST_WEAR[s.boost()];
                        setNum(ne, "superchargerEfficiency", eff);
                        setNum(ne, "superchargerFuelConsumption", fuel * BOOST_POWER[s.boost()] / eff);
                    }
                }
                setField(nd, "engine", ne);
                putDefinition(eng, nd);
                done[0] = true;
            });
        }
        for (Object w : wheels(e)) {
            step(log, "roue (définition)", () -> {
                Object od = original(w), og = field(od, "ground");
                if (og == null) return;
                double stage = driven.contains(w) ? GRIP[s.stage()] * (NitroService.isActive(e) ? NITRO_GRIP[s.nitro()] : 1.0) : 1.0, tire = TIRE_MOTIVE[s.tires()], lat = TIRE_LATERAL[s.tires()];
                if (allOne(stage, tire, lat)) {
                    putDefinition(w, od);
                    return;
                }
                Object nd = shallowCopy(od), ng = shallowCopy(og);
                setNum(ng, "motiveFriction", number(og, "motiveFriction", 0) * stage * tire);
                setNum(ng, "lateralFriction", number(og, "lateralFriction", 0) * lat);
                setField(nd, "ground", ng);
                putDefinition(w, nd);
                done[0] = true;
            });
        }
        Object veh = inner(e);
        if (veh != null) {
            step(log, "véhicule (définition : pont, freins)", () -> {
                Object od = original(veh), om = field(od, "motorized");
                if (om == null) throw new IllegalStateException("définition de véhicule illisible");
                if (allOne(AXLE[s.axle()], BRAKES[s.brakes()])) {
                    putDefinition(veh, od);
                    return;
                }
                Object nd = shallowCopy(od), nm = shallowCopy(om);
                double axle = number(om, "axleRatio", 0), brake = number(om, "brakingFactor", 0);
                if (axle > 0) setNum(nm, "axleRatio", axle * AXLE[s.axle()]);
                setNum(nm, "brakingFactor", (brake > 0 ? brake : 1.0) * BRAKES[s.brakes()]);
                setField(nd, "motorized", nm);
                putDefinition(veh, nd);
                done[0] = true;
            });
        }
        if (log != null) log.add("Définitions modifiées (relues par MTS à chaque tick) : " + (done[0] ? "oui" : "non"));
        return done[0];
    }

    private static boolean applyTuneVars(Entity e, Setup s, List<String> log) {
        boolean any = false, boostRefused = false;
        List<Object> engines = engines(e);
        if (log != null) log.add("Moteurs trouvés : " + engines.size());
        for (Object eng : engines) {
            Object def = engineDef(eng);
            if (def == null) {
                if (log != null) log.add("Moteur sans définition lisible.");
                continue;
            }
            double fuel = number(def, "fuelConsumption", 0);
            boolean ok = step(log, "puissance", () -> setChecked(log, "puissance", eng, "fuelConsumptionVar", fuel * POWER[s.stage()] * (1 + (NitroService.isActive(e) ? NITRO_POWER[s.nitro()] : 0))));
            step(log, "régime max", () -> setChecked(log, "régime max", eng, "maxRPMVar", number(def, "maxRPM", 0) * RPM[s.stage()]));
            double rev = number(def, "revlimitRPM", 0), safe = number(def, "maxSafeRPM", 0), wear = number(def, "engineWearFactor", 0);
            if (rev > 0) step(log, "limiteur", () -> setChecked(log, "limiteur", eng, "revLimitRPMVar", rev * RPM[s.stage()]));
            if (safe > 0) step(log, "régime sûr", () -> setChecked(log, "régime sûr", eng, "maxSafeRPMVar", safe * RPM[s.stage()]));
            if (wear > 0) step(log, "usure", () -> setChecked(log, "usure", eng, "wearFactorVar", wear * WEAR[s.stage()]));
            // Suralimentation : MTS ajoute (consommation du compresseur x rendement) à la force motrice, et un rendement > 1 multiplie l'usure.
            double sc = number(def, "superchargerFuelConsumption", 0), eff = BOOST_WEAR[s.boost()];
            if (sc > 0) {
                if (s.boost() > 0) {
                    boostRefused = true;   // moteur déjà suralimenté d'usine : on n'y touche pas
                    if (log != null) log.add("Suralimentation refusée : moteur déjà suralimenté d'usine.");
                }
            } else if (s.boost() > 0) {
                step(log, "suralimentation (rendement)", () -> setChecked(log, "rendement compresseur", eng, "superchargerEfficiencyVar", eff));
                step(log, "suralimentation (puissance)", () -> setChecked(log, "puissance compresseur", eng, "superchargerFuelConsumptionVar", fuel * BOOST_POWER[s.boost()] / eff));
            } else {
                step(null, "", () -> set(eng, "superchargerEfficiencyVar", number(def, "superchargerEfficiency", 0)));
                step(null, "", () -> set(eng, "superchargerFuelConsumptionVar", 0));
            }
            any |= ok;
        }
        boolean wheelsOk = !engines.isEmpty() && step(log, "roues", () -> applyWheels(e, s, engines));
        boolean chassisOk = step(log, "pont / freins", () -> {
            if (!applyChassis(e, s)) throw new IllegalStateException("valeur non prise en compte");
        });
        if (log != null) log.add("Résultat : puissance " + (any ? "posée" : "NON posée") + ", roues " + (wheelsOk ? "ok" : "non") + ", pont/freins " + (chassisOk ? "ok" : "non"));
        return (any || chassisOk) && !boostRefused;
    }
}
