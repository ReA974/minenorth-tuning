package fr.minenorth.banc.network;

import fr.minenorth.banc.DynoService;
import fr.minenorth.banc.MineNorthBanc;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Canal réseau du banc de puissance : l'état de l'écran (serveur → client) et l'action choisie (client → serveur). */
public final class BancNetwork {
    private BancNetwork() {}

    public static final int ACT_APPLY = 1, ACT_REFRESH = 2;
    private static final String PROTOCOL = "1";
    private static final int MAX_ROWS = 16, MAX_LINES = 12;
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MineNorthBanc.MOD_ID, "network"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        CHANNEL.registerMessage(0, State.class, State::encode, State::decode, State::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Action.class, Action::encode, Action::decode, Action::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void send(ServerPlayer p, State s) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), s); }

    private static String cut(String v, int max) { v = v == null ? "" : v; return v.length() > max ? v.substring(0, max) : v; }

    /** Un niveau de reprogrammation ; {@code current} = celui du véhicule. */
    public record Row(String label, String detail, boolean current) {
        static void encode(FriendlyByteBuf b, Row r) { b.writeUtf(cut(r.label, 64), 64); b.writeUtf(cut(r.detail, 96), 96); b.writeBoolean(r.current); }
        static Row decode(FriendlyByteBuf b) { return new Row(b.readUtf(64), b.readUtf(96), b.readBoolean()); }
    }

    /** Serveur → client : état de l'écran (l'ouvre ou le met à jour). */
    public record State(BlockPos pos, String company, String vehicle, String message, boolean ok, List<String> lines, List<Row> rows) {
        static void encode(State s, FriendlyByteBuf b) {
            b.writeBlockPos(s.pos); b.writeUtf(cut(s.company, 64), 64); b.writeUtf(cut(s.vehicle, 64), 64);
            b.writeUtf(cut(s.message, 120), 120); b.writeBoolean(s.ok);
            b.writeVarInt(s.lines.size()); for (String l : s.lines) b.writeUtf(cut(l, 120), 120);
            b.writeVarInt(s.rows.size()); for (Row r : s.rows) Row.encode(b, r);
        }
        static State decode(FriendlyByteBuf b) {
            BlockPos pos = b.readBlockPos();
            String company = b.readUtf(64), vehicle = b.readUtf(64), message = b.readUtf(120);
            boolean ok = b.readBoolean();
            int nl = b.readVarInt();
            if (nl < 0 || nl > MAX_LINES) throw new io.netty.handler.codec.DecoderException("Trop de lignes : " + nl);
            List<String> lines = new ArrayList<>(nl);
            for (int i = 0; i < nl; i++) lines.add(b.readUtf(120));
            int nr = b.readVarInt();
            if (nr < 0 || nr > MAX_ROWS) throw new io.netty.handler.codec.DecoderException("Trop de niveaux : " + nr);
            List<Row> rows = new ArrayList<>(nr);
            for (int i = 0; i < nr; i++) rows.add(Row.decode(b));
            return new State(pos, company, vehicle, message, ok, lines, rows);
        }
        static void handle(State s, Supplier<NetworkEvent.Context> c) {
            c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> fr.minenorth.banc.client.DynoScreen.show(s)));
            c.get().setPacketHandled(true);
        }
    }

    /** Client → serveur : appliquer le niveau {@code index} (ACT_APPLY) ou rafraîchir. Tout est revérifié côté serveur. */
    public record Action(BlockPos pos, int action, int index) {
        static void encode(Action a, FriendlyByteBuf b) { b.writeBlockPos(a.pos); b.writeVarInt(a.action); b.writeVarInt(a.index); }
        static Action decode(FriendlyByteBuf b) { return new Action(b.readBlockPos(), b.readVarInt(), b.readVarInt()); }
        static void handle(Action a, Supplier<NetworkEvent.Context> c) {
            c.get().enqueueWork(() -> { ServerPlayer sp = c.get().getSender(); if (sp != null) DynoService.handle(sp, a); });
            c.get().setPacketHandled(true);
        }
    }
}
