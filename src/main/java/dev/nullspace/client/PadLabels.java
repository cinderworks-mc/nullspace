package dev.nullspace.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.nullspace.content.Content;
import dev.nullspace.content.DestinationPadBlockEntity;
import dev.nullspace.core.Destination;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

public final class PadLabels implements BlockEntityRenderer<DestinationPadBlockEntity> {
    private static final int AMBER = 0xFFFFC23A, DIM = 0xFFB9B2A4, FAINT = 0xFF8A8478, PLAQUE = 0xB0140F0C, FULL_BRIGHT = 0xF000F0;
    private static final float SCALE = 0.02f;
    private static final float TEXT_LIFT = 0.6f;
    private static final int MAX_WIDTH = 104;   // pixels of text at SCALE: about 2 blocks wide, so neighbouring labels (3 blocks apart) do not overlap
    private final Font font;

    public PadLabels(BlockEntityRendererProvider.Context context) { this.font = context.getFont(); }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Content.DESTINATION_PAD_ENTITY.get(), PadLabels::new);
    }

    @Override public void render(DestinationPadBlockEntity pad, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        boolean bound = pad.bound();
        FormattedCharSequence title = clip(bound ? Component.literal(Destination.clip(pad.name(), Destination.LABEL_CHARS)) /* i18n-ok: player-chosen name */ : Component.translatable("block.nullspace.destination_pad.unset"), MAX_WIDTH);
        FormattedCharSequence sub = bound ? clip(dimensionName(pad.dimension()), MAX_WIDTH) : null;
        pose.pushPose();
        pose.translate(0.5, 1.12, 0.5);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.scale(SCALE, -SCALE, SCALE);
        Matrix4f matrix = pose.last().pose();
        int tw = font.width(title);
        line(title, -tw / 2f, 0, bound ? AMBER : FAINT, matrix, buffers);
        if (sub != null) {
            int sw = font.width(sub);
            line(sub, -sw / 2f, 10, DIM, matrix, buffers);
        }
        pose.popPose();
    }

    private void line(FormattedCharSequence text, float x, float y, int colour, Matrix4f matrix, MultiBufferSource buffers) {
        font.drawInBatch(text, x, y, 0x04FFFFFF, false, matrix, buffers, Font.DisplayMode.NORMAL, PLAQUE, FULL_BRIGHT);
        font.drawInBatch(text, x, y, colour, false, new Matrix4f(matrix).translate(0f, 0f, TEXT_LIFT), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, FULL_BRIGHT);
    }

    private FormattedCharSequence clip(Component text, int width) {
        var sequence = text.getVisualOrderText();
        if (font.width(sequence) <= width) return sequence;
        var cut = font.substrByWidth(text, width - font.width("…"));
        return FormattedCharSequence.composite(Language.getInstance().getVisualOrder(cut), Component.literal("…").getVisualOrderText()); // i18n-ok: ellipsis
    }

    static Component dimensionName(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return Component.literal(id); // i18n-ok: raw id
        String lang = "dimension." + key.getNamespace() + "." + key.getPath();
        return Language.getInstance().has(lang) ? Component.translatable(lang) : Component.literal(id); // i18n-ok: raw id
    }

    @Override public AABB getRenderBoundingBox(DestinationPadBlockEntity pad) {
        var p = pad.getBlockPos();
        return new AABB(p.getX() - 1, p.getY(), p.getZ() - 1, p.getX() + 2, p.getY() + 2, p.getZ() + 2);
    }

    @Override public int getViewDistance() { return 32; }
}
