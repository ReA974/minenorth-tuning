package fr.minenorth.banc;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Enregistrements du banc de puissance (bloc, entité de bloc, objet). */
public final class ModBanc {
    private ModBanc() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MineNorthBanc.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MineNorthBanc.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MineNorthBanc.MOD_ID);

    public static final RegistryObject<Block> DYNO = BLOCKS.register("banc_puissance", DynoBlock::new);
    public static final RegistryObject<Item> DYNO_ITEM = ITEMS.register("banc_puissance", () -> new DynoItem(DYNO.get(), new Item.Properties()));
    /** Ordinateur de réglage : même service que le banc, sans la plateforme (le joueur construit son banc autour, jusqu'à 12 blocs). */
    public static final RegistryObject<Block> PC = BLOCKS.register("ordinateur_banc",
            () -> new DynoBlock(net.minecraft.world.level.block.Block.box(2, 0, 3, 14, 15, 14)));
    public static final RegistryObject<Item> PC_ITEM = ITEMS.register("ordinateur_banc", () -> new DynoItem(PC.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<DynoBE>> DYNO_BE = BLOCK_ENTITIES.register("banc_puissance",
            () -> BlockEntityType.Builder.of(DynoBE::new, DYNO.get(), PC.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
