package dev.nullspace.integration.ftbchunks;

import dev.architectury.event.CompoundEventResult;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent;
import dev.nullspace.server.ArchiveService;
import net.minecraft.commands.CommandSourceStack;

// vetoes claiming and force loading in the archive dimension. the handlers have no side effects because ftb chunks
// also fires them for preview claims (the gui preview). only this package names ftb chunks or architectury types
public final class FtbChunksIntegration {
    private FtbChunksIntegration() {}

    static final String PROBLEM = "message.nullspace.claims_forbidden";

    public static void init() {
        ClaimedChunkEvent.BEFORE_CLAIM.register(FtbChunksIntegration::veto);
        ClaimedChunkEvent.BEFORE_LOAD.register(FtbChunksIntegration::veto);
    }

    private static CompoundEventResult<ClaimResult> veto(CommandSourceStack source, ClaimedChunk chunk) {
        if (chunk.getPos().dimension().equals(ArchiveService.DIMENSION)) return CompoundEventResult.interruptFalse(ClaimResult.customProblem(PROBLEM));
        return CompoundEventResult.pass();
    }
}
