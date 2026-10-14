package dev.nullspace.integration.tempad;

import com.mojang.authlib.GameProfile;
import dev.nullspace.core.ArchiveLink;
import dev.nullspace.core.Recall;
import dev.nullspace.core.RepeatedNotice;
import dev.nullspace.text.Msg;
import earth.terrarium.tempad.Tempad;
import earth.terrarium.tempad.api.ActionType;
import earth.terrarium.tempad.api.context.InventoryContext;
import earth.terrarium.tempad.api.locations.DirectLocation;
import earth.terrarium.tempad.api.tva_device.ChrononHandler;
import earth.terrarium.tempad.api.tva_device.UpgradeHandler;
import earth.terrarium.tempad.api.context.SyncableContext;
import earth.terrarium.tempad.api.event.TimedoorEvent;
import earth.terrarium.tempad.api.locations.IndirectLocation;
import earth.terrarium.tempad.api.locations.LocationGetter;
import earth.terrarium.tempad.api.locations.LocationHandler;
import earth.terrarium.tempad.api.locations.NamedGlobalVec3;
import earth.terrarium.tempad.api.locations.TempadLocations;
import earth.terrarium.tempad.api.sizing.DoorType;
import earth.terrarium.tempad.api.sizing.DynamicAngledPlacement;
import earth.terrarium.tempad.common.entity.TimedoorEntity;
import earth.terrarium.tempad.common.registries.ModComponents;
import kotlin.Unit;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// glue to tempad, pinned against 3.0.4. ownership and persistence stay out of here
public final class TempadGatewayBridge {
    public static final ResourceLocation PROVIDER = ResourceLocation.fromNamespaceAndPath("nullspace", "archives");
    public static final ResourceLocation ARCHIVE_LINK = ResourceLocation.fromNamespaceAndPath(ArchiveLink.UPGRADE_NAMESPACE, ArchiveLink.UPGRADE_PATH);
    static final String EXIT_PLAYER = "nullspace_exit_player";
    private static final String EMERGENCY_EXIT = "nullspace_emergency_exit";
    private static final String HUB_PLAYER = "nullspace_hub_player";
    private static final String CARD_TOKEN = "nullspace_card", CARD_USER = "nullspace_card_user";
    private static final ResourceLocation WALLET = ResourceLocation.fromNamespaceAndPath("tempad", "wallet");
    private static final ResourceLocation LOCATION_CARD = ResourceLocation.fromNamespaceAndPath("tempad", "location_card");
    public static final int HUB_DOOR_TICKS = 120;
    private final GatewayAccess access;

    private TempadGatewayBridge(GatewayAccess access) {
        this.access = Objects.requireNonNull(access);
    }

    public static TempadGatewayBridge install(GatewayAccess access) {
        var bridge = new TempadGatewayBridge(access);
        TempadLocations.register(PROVIDER, (profile, upgrades, chronons) -> new LocationHandler() {
            private ServerPlayer player() {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                return server == null ? null : server.getPlayerList().getPlayer(profile.getId());
            }

            @Override public Map<UUID, NamedGlobalVec3> getLocations() { return listFor(access, player(), upgrades); }

            // an archive card names the owner as accessor and its token as id, and resolves to that archive's
            // entrance in any card state (a revoked or spent card still points somewhere so the refusal can say why).
            // not listed. who may enter is decided in onOpen and onEnter
            @Override public NamedGlobalVec3 get(UUID id) {
                Destination card = access.cardDestination(profile.getId(), id);
                return card != null ? card.named() : getLocations().get(id);
            }

            @Override public void minusAssign(UUID id) { /* ownership is not held in a gadget */ }

            @Override public LocationGetter getSerializable(UUID id) {
                NamedGlobalVec3 location = getLocations().get(id); // a card token is not in the list, so a card cannot be copied by Tempad's card writer
                return location == null ? null : new IndirectLocation(profile, location.getName(), PROVIDER, id);
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, bridge::onOpen);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, bridge::onEnter);
        NeoForge.EVENT_BUS.addListener(bridge::onExit);
        NeoForge.EVENT_BUS.addListener(bridge::onJoin);
        return bridge;
    }

    // what a tempad shows: every destination the player may enter, except that a full tempad (one that takes
    // upgrades) holds back the player's own archive until it has the archive link (ArchiveLink, server switch
    // tempad.archiveLink). the list, the open door packet and a written card all go through here. upgrades == null
    // is unfiltered. the archive dimension lists nothing, doors there lead out through the exit
    public static Map<UUID, NamedGlobalVec3> listFor(GatewayAccess access, ServerPlayer player, UpgradeHandler upgrades) {
        if (player == null || access.isArchiveDimension(player.level().dimension())) return Map.of();
        boolean required = access.archiveLinkRequired();
        boolean upgradable = upgrades != null && upgrades.willAccept(ARCHIVE_LINK);
        boolean installed = upgrades != null && upgrades.contains(ARCHIVE_LINK);
        Map<UUID, NamedGlobalVec3> locations = new LinkedHashMap<>();
        access.destinations(player).forEach((id, target) -> {
            if (ArchiveLink.lists(required, upgradable, installed, id, player.getUUID())) locations.put(id, target.named());
        });
        return locations;
    }

    public boolean linkProjector(ServerPlayer player, ItemStack projector, UUID archive) {
        if (!BuiltInRegistries.ITEM.getKey(projector.getItem())
                .equals(ResourceLocation.fromNamespaceAndPath("tempad", "timedoor_projector"))) return false;
        Destination destination = access.destinations(player).get(archive);
        if (destination == null) return false;
        projector.set(ModComponents.INSTANCE.getPortalTarget(),
                new IndirectLocation(player.getGameProfile(), destination.label(), PROVIDER, archive));
        player.getInventory().setChanged();
        return true;
    }

    public Component openArchive(ServerPlayer player, SyncableContext<?> context, UUID archive) {
        if (access.isArchiveDimension(player.level().dimension()))
            return Msg.no("message.nullspace.gateway.use_exit");
        Destination destination = access.destinations(player).get(archive);
        if (destination == null) return access.refusalFor(player, archive);
        if (player.getCooldowns().isOnCooldown(context.getStack().getItem()))
            return Msg.no("message.nullspace.gateway.cooldown");
        return TimedoorEntity.Companion.openTimedoor(player, context, PROVIDER, archive,
                destination.named(), door -> Unit.INSTANCE);
    }

    public Component openArchive(ServerPlayer player, int inventorySlot, UUID archive) {
        if (inventorySlot < 0 || inventorySlot >= player.getInventory().getContainerSize())
            return Msg.no("message.nullspace.gateway.bad_slot");
        return openArchive(player, new InventoryContext(player, inventorySlot), archive);
    }

    public Component openExit(ServerPlayer player) {
        if (!access.isArchiveDimension(player.level().dimension()))
            return Msg.no("message.nullspace.gateway.exit_outside");
        ReturnTarget target = access.safeReturn(player);
        if (target == null || access.isArchiveDimension(target.dimension()))
            return Msg.no("message.nullspace.gateway.no_return");
        NamedGlobalVec3 location = new NamedGlobalVec3(Component.translatable("message.nullspace.gateway.return_label"),
                target.position(), target.dimension(), target.yaw(), Tempad.Companion.getORANGE());
        var result = TimedoorEntity.Companion.getTimedoor(player.level(), location, false);
        boolean emergency = result.right().isPresent();
        if (emergency && access.allowEmergencyExit())
            result = TimedoorEntity.Companion.getTimedoor(player.level(), location, true);
        if (result.right().isPresent()) return result.right().get();
        TimedoorEntity door = result.left().orElseThrow();
        door.setOwner(player.getUUID());
        door.getPersistentData().putUUID(EXIT_PLAYER, player.getUUID());
        door.getPersistentData().putBoolean(EMERGENCY_EXIT, emergency);
        var sizing = new DynamicAngledPlacement();
        door.setSizing(sizing);
        // tempad stands the gateway about three blocks in front of the player and ignores walls, so a close click on
        // the exit could put it inside or behind a wall. pull the placement point back until the gateway is in free
        // space
        Vec3 forward = Vec3.directionFromRotation(0f, player.getYRot());
        boolean placed = false;
        for (int back = 0; back <= 3 && !placed; back++) {
            sizing.placeTimedoor(DoorType.ENTRY, player.position().subtract(forward.scale(back)), player.getYRot(), door);
            var at = new Vec3(door.getX(), door.getY() + 1.0, door.getZ());
            // free space around the gateway and nothing solid between the player and it (a gateway behind a wall cannot be walked into)
            placed = player.level().noBlockCollision(null, new AABB(door.getX() - 0.4, door.getY(), door.getZ() - 0.4, door.getX() + 0.4, door.getY() + 2.0, door.getZ() + 0.4))
                && player.level().clip(new ClipContext(player.getEyePosition(), at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType()
                    == HitResult.Type.MISS;
        }
        if (!placed) sizing.placeTimedoor(DoorType.ENTRY, player.position(), player.getYRot(), door);
        var event = new TimedoorEvent.Open(door, PROVIDER, null, player.getGameProfile());
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return event.getErrorMessage() == null
                ? Msg.no("message.nullspace.gateway.exit_blocked") : event.getErrorMessage();
        // one exit gateway per player: an older one keeps the target it was opened with, and tempad lets the older of
        // two overlapping gateways take the walker
        closeExits(player.getServer(), player.getUUID(), false);
        if (!player.level().addFreshEntity(door)) return Msg.no("message.nullspace.gateway.exit_failed");
        if (emergency) access.emergencyExitOpened(player, target);
        return null;
    }

    // removes the exit gateways opened for the player (everyone when null). with onlyUnused only those nobody has
    // walked into yet: tempad creates the receiving end and the link lazily and saves a gateway without a link as the
    // text null, which it cannot read back. returns how many went
    public int closeExits(MinecraftServer server, UUID player, boolean onlyUnused) {
        if (server == null) return 0;
        List<TimedoorEntity> found = new ArrayList<>();
        for (var level : server.getAllLevels()) {
            if (!access.isArchiveDimension(level.dimension())) continue;
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof TimedoorEntity door) || !(door.getPersistentData().hasUUID(EXIT_PLAYER) || door.getPersistentData().hasUUID(HUB_PLAYER))) continue;
                UUID reservedFor = door.getPersistentData().hasUUID(EXIT_PLAYER) ? door.getPersistentData().getUUID(EXIT_PLAYER) : door.getPersistentData().getUUID(HUB_PLAYER);
                if (player != null && !reservedFor.equals(player)) continue;
                if (onlyUnused && door.getLinkedPortalId() != null) continue;
                found.add(door);
            }
        }
        found.forEach(Entity::discard); // an original gateway takes its receiving end with it (TimedoorEntity.remove)
        return found.size();
    }

    private void onOpen(TimedoorEvent.Open event) {
        TimedoorEntity door = event.getEntity();
        if (door.level().isClientSide()) return;
        if (access.isArchiveDimension(door.level().dimension())) {
            if (!door.getPersistentData().hasUUID(EXIT_PLAYER) && !door.getPersistentData().hasUUID(HUB_PLAYER))
                event.fail(Msg.no("message.nullspace.gateway.not_a_network"));
            return;
        }
        if (!access.isArchiveDimension(door.getTargetDimension())) return;
        MinecraftServer server = door.getServer();
        ServerPlayer opener = server == null ? null : server.getPlayerList().getPlayer(event.getOpener().getId());
        if (opener == null) {
            event.fail(Msg.no("message.nullspace.gateway.opener_required"));
            return;
        }
        UUID card = cardFor(event, opener, door.getTargetPos());
        EntryDecision decision = card == null ? access.authorizeEntry(opener, door.getTargetDimension(), door.getTargetPos(), opener.getUUID())
            : access.authorizeEntry(opener, door.getTargetDimension(), door.getTargetPos(), opener.getUUID(), card, opener.getUUID());
        if (!decision.allowed()) {
            event.fail(decision.reason());
            // the projector item discards the failure message Tempad is handed, so say it ourselves too (the actionbar shows one line).
            opener.displayClientMessage(decision.reason(), true);
        } else if (card != null) {
            door.getPersistentData().putUUID(CARD_TOKEN, card);
            door.getPersistentData().putUUID(CARD_USER, opener.getUUID());
        }
    }

    // the archive card this opening used, or null. a projector card arrives as our provider plus the token; a wallet
    // card arrives as the wallet slot, so the opener's wallets are searched. only a token the target archive knows
    // counts
    private UUID cardFor(TimedoorEvent.Open event, ServerPlayer opener, Vec3 target) {
        List<UUID> candidates = new ArrayList<>();
        if (PROVIDER.equals(event.getProvider()) && event.getLocationId() != null) candidates.add(event.getLocationId());
        else if (WALLET.equals(event.getProvider())) candidates.addAll(walletCards(opener));
        return candidates.isEmpty() ? null : access.chooseCard(opener, target, candidates);
    }

    private static List<UUID> walletCards(ServerPlayer player) {
        List<UUID> tokens = new ArrayList<>();
        for (ItemStack wallet : carried(player)) {
            if (!BuiltInRegistries.ITEM.getKey(wallet.getItem()).equals(ResourceLocation.fromNamespaceAndPath("tempad", "card_wallet"))) continue;
            var contents = wallet.get(ModComponents.INSTANCE.getWalletContents());
            if (contents != null) contents.nonEmptyItems().forEach(card -> { UUID token = cardToken(card); if (token != null) tokens.add(token); });
        }
        return tokens;
    }

    private void onEnter(TimedoorEvent.Enter event) {
        TimedoorEntity door = event.getEntity();
        Entity entity = event.getTeleportee();
        if (door.level().isClientSide()) return;
        if (door.getPersistentData().hasUUID(HUB_PLAYER)) {
            // a Destination Pad door (and the receiving end of it outside): only the player who used the pad may cross, and the pad's rules are
            // checked again at the moment of crossing. The outside end leads back into the archive and goes through the normal entry check below.
            if (!(entity instanceof ServerPlayer player) || !door.getPersistentData().getUUID(HUB_PLAYER).equals(player.getUUID())) {
                deny(event, Msg.no("message.nullspace.gateway.not_your_pad_door"));
                return;
            }
            if (access.isArchiveDimension(door.level().dimension())) {
                if (access.isArchiveDimension(door.getTargetDimension())) { deny(event, Msg.no("message.nullspace.gateway.no_archive_hop")); return; }
                Component refusal = access.hubCrossing(player);
                if (refusal != null) deny(event, refusal);
                return;
            }
        } else if (access.isArchiveDimension(door.level().dimension())) {
            // a shared receiving gateway has one target; it cannot safely represent all visitors' returns.
            if (!door.getPersistentData().hasUUID(EXIT_PLAYER)
                    || (entity instanceof ServerPlayer player
                    && !door.getPersistentData().getUUID(EXIT_PLAYER).equals(player.getUUID()))) {
                deny(event, Msg.no("message.nullspace.gateway.not_your_exit"));
                return;
            }
            if (access.isArchiveDimension(door.getTargetDimension())) {
                deny(event, Msg.no("message.nullspace.gateway.no_archive_hop"));
                return;
            }
            // an exit that leads somewhere other than where the exit would lead now (a gateway left over from before the owner died outside and
            // respawned, say) closes instead of taking the owner to a stale place.
            if (entity instanceof ServerPlayer player && !door.getPersistentData().getBoolean(EMERGENCY_EXIT)) {
                ReturnTarget now = access.safeReturn(player);
                Vec3 at = door.getTargetPos();
                if (now != null && !Recall.sameSpot(door.getTargetDimension().location().toString(), at.x, at.y, at.z,
                        now.dimension().location().toString(), now.position().x, now.position().y, now.position().z)) {
                    LoggerFactory.getLogger("nullspace").info("Exit gateway of {} led to {} {} but the exit now leads to {} {}: closed", player.getGameProfile().getName(),
                            door.getTargetDimension().location(), at, now.dimension().location(), now.position());
                    deny(event, Msg.no("message.nullspace.gateway.stale_exit"));
                    door.discard();
                    return;
                }
            }
        }
        if (!access.isArchiveDimension(door.getTargetDimension())) return;
        // a door opened with an Archive Card carries the card for the player who opened it; anyone else crossing is judged on their own rights.
        UUID cardUser = door.getPersistentData().hasUUID(CARD_USER) ? door.getPersistentData().getUUID(CARD_USER) : null;
        UUID card = door.getPersistentData().hasUUID(CARD_TOKEN) && cardUser != null && (!(entity instanceof ServerPlayer p) || p.getUUID().equals(cardUser))
            ? door.getPersistentData().getUUID(CARD_TOKEN) : null;
        EntryDecision decision = card == null ? access.authorizeEntry(entity, door.getTargetDimension(), door.getTargetPos(), door.getOwner())
            : access.authorizeEntry(entity, door.getTargetDimension(), door.getTargetPos(), door.getOwner(), card, cardUser);
        if (!decision.allowed()) {
            event.setCanceled(true);
            if (entity instanceof ServerPlayer player) tell(event, player, decision.reason());
            return;
        }
        if (entity instanceof ServerPlayer player) {
            access.beforeEntry(player, decision.owner(),
                    new Origin(player.level().dimension(), player.position(), player.getYRot(), player.getXRot()), card);
        }
    }

    private void onExit(TimedoorEvent.Exit event) {
        if (!(event.getTeleportee() instanceof ServerPlayer player)) return;
        if (access.isArchiveDimension(player.level().dimension())) access.afterEntry(player);
        else if (access.isArchiveDimension(event.getEntity().getTargetDimension())) access.afterExit(player);
    }

    // tempad makes the receiving end of a pad door outside the archive, linked by id and without our tag. it is
    // reserved to the same player as the original, so a stranger there cannot step through into the archive
    private void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof TimedoorEntity door) || door.getOriginal() || door.getLinkedPortalId() == null) return;
        if (!access.isArchiveDimension(door.getTargetDimension())) return;
        MinecraftServer server = event.getLevel().getServer();
        var archive = server == null ? null : server.getLevel(door.getTargetDimension());
        if (archive == null) return;
        Entity original = archive.getEntity(door.getLinkedPortalId());
        if (original != null && original.getPersistentData().hasUUID(HUB_PLAYER)) door.getPersistentData().putUUID(HUB_PLAYER, original.getPersistentData().getUUID(HUB_PLAYER));
    }

    // checks a pad trip against tempad's own rules (dimension travel switches, restriction tags) without creating
    // anything. null when allowed, else tempad's refusal text
    public Component hubRestriction(ServerPlayer player, ResourceKey<Level> dimension, Vec3 position, float yaw) {
        var location = new NamedGlobalVec3(Component.empty(), position, dimension, yaw, Tempad.Companion.getORANGE());
        var result = TimedoorEntity.Companion.getTimedoor(player.level(), location, false);
        return result.right().isPresent() ? result.right().get() : null;
    }

    public Component openHub(ServerPlayer player, Vec3 doorAt, float walkerYaw, Component name, ResourceKey<Level> dimension, Vec3 target, float targetYaw) {
        var location = new NamedGlobalVec3(name, target, dimension, targetYaw, Tempad.Companion.getORANGE());
        var result = TimedoorEntity.Companion.getTimedoor(player.level(), location, false);
        if (result.right().isPresent()) return result.right().get();
        TimedoorEntity door = result.left().orElseThrow();
        door.setOwner(player.getUUID());
        door.getPersistentData().putUUID(HUB_PLAYER, player.getUUID());
        door.setSizing(new DynamicAngledPlacement());
        door.setPos(doorAt.x, doorAt.y, doorAt.z);
        door.setYRot(walkerYaw + 180f);
        door.setClosingTime(HUB_DOOR_TICKS);
        var event = new TimedoorEvent.Open(door, PROVIDER, null, player.getGameProfile());
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return event.getErrorMessage() == null ? Msg.no("message.nullspace.gateway.exit_blocked") : event.getErrorMessage();
        closeExits(player.getServer(), player.getUUID(), false);
        if (!player.level().addFreshEntity(door)) return Msg.no("message.nullspace.gateway.exit_failed");
        return null;
    }

    public static boolean isBlankCard(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(LOCATION_CARD)
            && stack.get(ModComponents.INSTANCE.getPortalTarget()) == null;
    }

    // a location card pointing at the card token of the owner's archive: an indirect location to this provider whose
    // id is the token, so tempad asks us on every use and a revoked card stops working. the owner is the accessor.
    // info is the line tempad shows first, name replaces the item name
    public static ItemStack printedCard(UUID owner, String ownerName, UUID token, Component info, Component name) {
        var item = BuiltInRegistries.ITEM.get(LOCATION_CARD);
        if (item == Items.AIR) return null;
        ItemStack card = new ItemStack(item);
        card.set(ModComponents.INSTANCE.getPortalTarget(), new IndirectLocation(new GameProfile(owner, ownerName), info, PROVIDER, token));
        card.set(DataComponents.ITEM_NAME, name);
        return card;
    }

    public static UUID cardToken(ItemStack stack) {
        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(LOCATION_CARD)) return null;
        return stack.get(ModComponents.INSTANCE.getPortalTarget()) instanceof IndirectLocation indirect && PROVIDER.equals(indirect.getProvider()) ? indirect.getId() : null;
    }

    public record CardPlace(String name, String dimension, double x, double y, double z, float yaw) {}

    // the fixed place on a location card, or null for anything else (not a card, empty, or pointing at a provider
    // list). read only
    public static CardPlace cardPlace(ItemStack stack) {
        if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(ResourceLocation.fromNamespaceAndPath("tempad", "location_card"))) return null;
        Object target = stack.get(ModComponents.INSTANCE.getPortalTarget());
        if (!(target instanceof DirectLocation direct)) return null;
        NamedGlobalVec3 l = direct.getLocation();
        return new CardPlace(l.getName().getString(), l.getDimension().location().toString(), l.getPos().x, l.getPos().y, l.getPos().z, l.getAngle());
    }

    public static int chrononsCarried(ServerPlayer player) {
        int total = 0;
        for (ItemStack stack : carried(player)) { int c = chronons(stack); if (c > 0) total += c; }
        return total;
    }

    public static boolean chargeChronons(ServerPlayer player, int cost) {
        if (cost <= 0 || player.getAbilities().instabuild) return true;
        if (chrononsCarried(player) < cost) return false;
        int left = cost;
        for (ItemStack stack : carried(player)) {
            if (left <= 0) break;
            var handler = stack.getCapability(ChrononHandler.Capabilities.getItem());
            if (handler == null) continue;
            left -= handler.extract(Math.min(left, handler.getPower()), ActionType.Execute);
        }
        return left <= 0;
    }

    private static List<ItemStack> carried(ServerPlayer player) {
        List<ItemStack> list = new ArrayList<>(player.getInventory().items);
        list.addAll(player.getInventory().offhand);
        list.addAll(player.getInventory().armor);
        return list;
    }


    public static int chronons(ItemStack stack) {
        var handler = stack.getCapability(ChrononHandler.Capabilities.getItem());
        return handler == null ? -1 : handler.getPower();
    }

    // tempad runs the enter check every tick a player stands in a gateway, so a refusal is shown once per window
    private static final RepeatedNotice REFUSAL_NOTICE = new RepeatedNotice(100);

    private static void tell(TimedoorEvent.Enter event, ServerPlayer player, Component reason) {
        if (REFUSAL_NOTICE.mayTell(player.getUUID(), event.getEntity().getUUID(), player.getServer().getTickCount()))
            player.displayClientMessage(reason, true);
    }

    private static void deny(TimedoorEvent.Enter event, Component reason) {
        event.setCanceled(true);
        if (event.getTeleportee() instanceof ServerPlayer player) tell(event, player, reason);
    }

    public record Destination(UUID owner, ResourceKey<Level> dimension, Vec3 position, float yaw, Component label) {
        NamedGlobalVec3 named() { return new NamedGlobalVec3(label, position, dimension, yaw, Tempad.Companion.getORANGE()); }
    }
    public record Origin(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch) {}
    public record ReturnTarget(ResourceKey<Level> dimension, Vec3 position, float yaw) {}
    public record EntryDecision(boolean allowed, UUID owner, Component reason) {
        public static EntryDecision allow(UUID owner) { return new EntryDecision(true, owner, Component.empty()); }
        public static EntryDecision deny(Component reason) { return new EntryDecision(false, null, reason); }
    }

    public interface GatewayAccess {
        Map<UUID, Destination> destinations(ServerPlayer player);
        boolean isArchiveDimension(ResourceKey<Level> dimension);
        EntryDecision authorizeEntry(Entity entity, ResourceKey<Level> dimension, Vec3 position, UUID gatewayOwner);
        void beforeEntry(ServerPlayer player, UUID owner, Origin origin);
        default void beforeEntry(ServerPlayer player, UUID owner, Origin origin, UUID cardToken) { beforeEntry(player, owner, origin); }
        default EntryDecision authorizeEntry(Entity entity, ResourceKey<Level> dimension, Vec3 position, UUID gatewayOwner, UUID cardToken, UUID cardUser) {
            return authorizeEntry(entity, dimension, position, gatewayOwner);
        }
        default Destination cardDestination(UUID owner, UUID token) { return null; }
        // of the cards a player presented, the one that matters at that archive (one that lets them in first), null
        // when none is known there
        default UUID chooseCard(ServerPlayer player, Vec3 position, List<UUID> candidates) { return null; }
        default void afterEntry(ServerPlayer player) {}
        void afterExit(ServerPlayer player);
        ReturnTarget safeReturn(ServerPlayer player);
        // why this player cannot open a gateway to that archive (expired, used, not invited)
        // checked again when a player walks into a pad door inside the archive; null if the pad rules still allow it
        default Component hubCrossing(ServerPlayer player) { return null; }
        default Component refusalFor(ServerPlayer player, UUID archiveOwner) { return Msg.no("message.nullspace.gateway.no_archive"); }
        default boolean allowEmergencyExit() { return true; }
        default boolean archiveLinkRequired() { return false; }
        default void emergencyExitOpened(ServerPlayer player, ReturnTarget target) {}
    }
}
