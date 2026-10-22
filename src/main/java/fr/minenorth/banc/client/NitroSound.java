package fr.minenorth.banc.client;

import fr.minenorth.banc.ModBanc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/** Son de nitro joué côté client : il suit le véhicule (au lieu de rester à l'endroit de l'activation) et s'arrête avec la nitro. */
final class NitroSound extends AbstractTickableSoundInstance {
    private static final Map<Integer, NitroSound> ACTIVE = new HashMap<>();
    private final int entityId;
    private boolean on = true;
    private int age;

    private NitroSound(Entity e) {
        super(ModBanc.NITRO_SOUND.get(), SoundSource.NEUTRAL, RandomSource.create());
        this.entityId = e.getId();
        this.looping = false;
        this.volume = 1.5f;
        this.attenuation = Attenuation.LINEAR;
        this.x = e.getX(); this.y = e.getY(); this.z = e.getZ();
    }

    /** Appelé à chaque réception du réglage : démarre ou coupe le son de ce véhicule. */
    static void update(int entityId, boolean nitroOn) {
        NitroSound cur = ACTIVE.get(entityId);
        if (!nitroOn) {
            if (cur != null) { cur.on = false; ACTIVE.remove(entityId); }
            return;
        }
        if (cur != null && !cur.isStopped()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (e == null) return;
        NitroSound s = new NitroSound(e);
        ACTIVE.put(entityId, s);
        mc.getSoundManager().play(s);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(entityId);
        if (!on || e == null || e.isRemoved()) { stop(); ACTIVE.remove(entityId, this); return; }
        this.x = e.getX(); this.y = e.getY(); this.z = e.getZ();
        if (++age >= 230) { stop(); ACTIVE.remove(entityId, this); update(entityId, true); }   // le son dure 11,8 s : on le relance tant que la nitro dure
    }
}
