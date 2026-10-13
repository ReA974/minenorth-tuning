package fr.minenorth.banc;

import fr.minenorth.banc.network.BancNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MineNorthBanc.MOD_ID)
public class MineNorthBanc {
    public static final String MOD_ID = "minenorthbanc";

    public MineNorthBanc() {
        BancNetwork.register();
        ModBanc.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
