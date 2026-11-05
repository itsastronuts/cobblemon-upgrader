package dev.itsastronuts.cobbleupgrader.net;

import dev.itsastronuts.cobbleupgrader.CobbleUpgrader;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Packets between the server and the custom upgrader screen. */
public final class Payloads {
    private Payloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CobbleUpgrader.MOD_ID, name));
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Data.TYPE, Data.CODEC);
        PayloadTypeRegistry.playS2C().register(Spin.TYPE, Spin.CODEC);
        PayloadTypeRegistry.playS2C().register(Result.TYPE, Result.CODEC);
        PayloadTypeRegistry.playS2C().register(Error.TYPE, Error.CODEC);
        PayloadTypeRegistry.playC2S().register(Upgrade.TYPE, Upgrade.CODEC);
        PayloadTypeRegistry.playC2S().register(Close.TYPE, Close.CODEC);
    }

    // ---- Shared pieces -------------------------------------------------------------------

    /** The settings the screen needs to show the same odds and values as the server. */
    public record Settings(double houseEdge, double minChance, double maxChance, int minLevel, int maxLevel,
                           double levelWeight, double shinyMultiplier, boolean allowItemTargets,
                           boolean allowPokemonTargets, boolean allowShiny, boolean confirmBets, int spinTicks,
                           boolean allowPokemonBets, boolean allowInventoryBets, double defaultItemValue,
                           double uncommonMultiplier, double rareMultiplier, double epicMultiplier) {
        void write(FriendlyByteBuf buf) {
            buf.writeDouble(houseEdge);
            buf.writeDouble(minChance);
            buf.writeDouble(maxChance);
            buf.writeVarInt(minLevel);
            buf.writeVarInt(maxLevel);
            buf.writeDouble(levelWeight);
            buf.writeDouble(shinyMultiplier);
            buf.writeBoolean(allowItemTargets);
            buf.writeBoolean(allowPokemonTargets);
            buf.writeBoolean(allowShiny);
            buf.writeBoolean(confirmBets);
            buf.writeVarInt(spinTicks);
            buf.writeBoolean(allowPokemonBets);
            buf.writeBoolean(allowInventoryBets);
            buf.writeDouble(defaultItemValue);
            buf.writeDouble(uncommonMultiplier);
            buf.writeDouble(rareMultiplier);
            buf.writeDouble(epicMultiplier);
        }

        static Settings read(FriendlyByteBuf buf) {
            return new Settings(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readBoolean(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
    }

    /** One Pokémon in the player's party, with its value worked out by the server. */
    public record PartyMon(int slot, UUID uuid, String species, List<String> aspects, int level, boolean shiny,
                           int ivTotal, double value, String blocked, String held, boolean legendary) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(slot);
            buf.writeUUID(uuid);
            buf.writeUtf(species);
            buf.writeVarInt(aspects.size());
            for (String a : aspects) buf.writeUtf(a);
            buf.writeVarInt(level);
            buf.writeBoolean(shiny);
            buf.writeVarInt(ivTotal);
            buf.writeDouble(value);
            buf.writeUtf(blocked);
            buf.writeUtf(held);
            buf.writeBoolean(legendary);
        }

        static PartyMon read(FriendlyByteBuf buf) {
            int slot = buf.readVarInt();
            UUID uuid = buf.readUUID();
            String species = buf.readUtf();
            int n = buf.readVarInt();
            List<String> aspects = new ArrayList<>(n);
            for (int i = 0; i < n; i++) aspects.add(buf.readUtf());
            return new PartyMon(slot, uuid, species, aspects, buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
                    buf.readDouble(), buf.readUtf(), buf.readUtf(), buf.readBoolean());
        }
    }

    // ---- Server -> client ----------------------------------------------------------------

    /** Everything the screen shows. Sent when it opens and again after each spin. */
    public record Data(boolean open, Settings settings, List<PartyMon> party, Map<String, Double> items,
                       List<String> species, List<Double> speciesBase, Map<String, Double> prices,
                       List<String> blacklist) implements CustomPacketPayload {
        public static final Type<Data> TYPE = payloadType("data");
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.of((buf, p) -> p.write(buf), Data::read);

        void write(FriendlyByteBuf buf) {
            buf.writeBoolean(open);
            settings.write(buf);
            buf.writeVarInt(party.size());
            for (PartyMon mon : party) mon.write(buf);
            buf.writeVarInt(items.size());
            items.forEach((id, value) -> {
                buf.writeUtf(id);
                buf.writeDouble(value);
            });
            buf.writeVarInt(species.size());
            for (int i = 0; i < species.size(); i++) {
                buf.writeUtf(species.get(i));
                buf.writeDouble(speciesBase.get(i));
            }
            buf.writeVarInt(prices.size());
            prices.forEach((id, value) -> {
                buf.writeUtf(id);
                buf.writeDouble(value);
            });
            buf.writeVarInt(blacklist.size());
            for (String id : blacklist) buf.writeUtf(id);
        }

        static Data read(FriendlyByteBuf buf) {
            boolean open = buf.readBoolean();
            Settings settings = Settings.read(buf);
            int n = buf.readVarInt();
            List<PartyMon> party = new ArrayList<>(n);
            for (int i = 0; i < n; i++) party.add(PartyMon.read(buf));
            n = buf.readVarInt();
            Map<String, Double> items = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) items.put(buf.readUtf(), buf.readDouble());
            n = buf.readVarInt();
            List<String> species = new ArrayList<>(n);
            List<Double> base = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                species.add(buf.readUtf());
                base.add(buf.readDouble());
            }
            n = buf.readVarInt();
            Map<String, Double> prices = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) prices.put(buf.readUtf(), buf.readDouble());
            n = buf.readVarInt();
            List<String> blacklist = new ArrayList<>(n);
            for (int i = 0; i < n; i++) blacklist.add(buf.readUtf());
            return new Data(open, settings, party, items, species, base, prices, blacklist);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The spin started: where the needle will land and how long it takes. */
    public record Spin(float landingDegrees, int ticks, double chance) implements CustomPacketPayload {
        public static final Type<Spin> TYPE = payloadType("spin");
        public static final StreamCodec<RegistryFriendlyByteBuf, Spin> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeFloat(p.landingDegrees);
                    buf.writeVarInt(p.ticks);
                    buf.writeDouble(p.chance);
                },
                buf -> new Spin(buf.readFloat(), buf.readVarInt(), buf.readDouble()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Result(boolean won, String message) implements CustomPacketPayload {
        public static final Type<Result> TYPE = payloadType("result");
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.won);
                    buf.writeUtf(p.message);
                },
                buf -> new Result(buf.readBoolean(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Error(String message) implements CustomPacketPayload {
        public static final Type<Error> TYPE = payloadType("error");
        public static final StreamCodec<RegistryFriendlyByteBuf, Error> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.message),
                buf -> new Error(buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- Client -> server ----------------------------------------------------------------

    /**
     * Press of the UPGRADE button.
     * betKind: 0 = Pokémon from the party (bet), 1 = items from inventory slot (slot, itemId, amount).
     * targetKind: 0 = Pokémon (targetAmount = level), 1 = item (targetAmount = count).
     */
    public record Upgrade(int betKind, UUID bet, int slot, String itemId, int amount,
                          int targetKind, String targetId, int targetAmount, boolean shiny)
            implements CustomPacketPayload {
        public static final Type<Upgrade> TYPE = payloadType("upgrade");
        public static final StreamCodec<RegistryFriendlyByteBuf, Upgrade> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.betKind);
                    buf.writeUUID(p.bet);
                    buf.writeVarInt(p.slot);
                    buf.writeUtf(p.itemId);
                    buf.writeVarInt(p.amount);
                    buf.writeVarInt(p.targetKind);
                    buf.writeUtf(p.targetId);
                    buf.writeVarInt(p.targetAmount);
                    buf.writeBoolean(p.shiny);
                },
                buf -> new Upgrade(buf.readVarInt(), buf.readUUID(), buf.readVarInt(), buf.readUtf(), buf.readVarInt(),
                        buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Close() implements CustomPacketPayload {
        public static final Close INSTANCE = new Close();
        public static final Type<Close> TYPE = payloadType("close");
        public static final StreamCodec<RegistryFriendlyByteBuf, Close> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
