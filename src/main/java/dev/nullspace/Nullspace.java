package dev.nullspace;

import dev.nullspace.client.ItemTints;
import dev.nullspace.client.PadLabels;
import dev.nullspace.client.sky.VoidSkyConfig;
import dev.nullspace.content.Content;
import dev.nullspace.integration.ftbchunks.FtbChunksIntegration;
import dev.nullspace.integration.ftbteams.FtbTeamsProvider;
import dev.nullspace.integration.opac.OpacIntegration;
import dev.nullspace.network.TerminalNetwork;
import dev.nullspace.server.ArchiveService;
import dev.nullspace.server.ServerConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.LoggerFactory;

@Mod(Nullspace.ID)
public final class Nullspace {
    public static final String ID = "nullspace";
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }
    public Nullspace(IEventBus bus, ModContainer container) {
        Content.register(bus);
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        if (FMLEnvironment.dist.isClient()) container.registerConfig(ModConfig.Type.CLIENT, VoidSkyConfig.SPEC);
        ArchiveService.install(bus, NeoForge.EVENT_BUS);
        if (FMLEnvironment.dist.isClient()) bus.addListener(ItemTints::register);
        if (FMLEnvironment.dist.isClient()) bus.addListener(PadLabels::registerRenderers);
        // optional claim mods: foreign types are only referenced from classes that load when the mod is present. an
        // untested version must not stop the game from starting, so a failing integration is logged and skipped
        if (ModList.get().isLoaded("openpartiesandclaims")) {
            try { OpacIntegration.init(); }
            catch (LinkageError | RuntimeException t) { LoggerFactory.getLogger(ID).warn("Open Parties and Claims integration disabled (unsupported version?)", t); }
        }
        if (ModList.get().isLoaded("ftbchunks")) {
            try { FtbChunksIntegration.init(); }
            catch (LinkageError | RuntimeException t) { LoggerFactory.getLogger(ID).warn("FTB Chunks integration disabled (unsupported version?)", t); }
        }
        // team access: ftb teams supplies the parties. a failing integration (unsupported version) leaves team access
        // absent and does not stop the game
        if (ModList.get().isLoaded("ftbteams")) {
            try { FtbTeamsProvider.init(); }
            catch (LinkageError | RuntimeException t) { LoggerFactory.getLogger(ID).warn("FTB Teams integration disabled (unsupported version?)", t); }
        }
        bus.addListener(TerminalNetwork::register);
    }
}
