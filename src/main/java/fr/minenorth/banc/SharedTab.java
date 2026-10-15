package fr.minenorth.banc;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Le banc de puissance va dans l'onglet créatif commun « MineNorth » (fourni par minenorth_api). */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SharedTab {
    private SharedTab() {}

    @SubscribeEvent
    public static void fill(BuildCreativeModeTabContentsEvent e) {
        if (!e.getTabKey().location().equals(new ResourceLocation("minenorthapi", "minenorth"))) return;
        e.accept(ModBanc.BENCH_ITEM);
        e.accept(ModBanc.NITRO_CART_ITEM);
        e.accept(ModBanc.NITRO_BOTTLE);
    }
}
