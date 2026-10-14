package dev.nullspace.client;

import dev.nullspace.content.Content;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

// the finish brush's roller sleeve (grey ramp in the model, tint index 0) takes the colour of the stored finish, so the
// choice shows in the inventory, hand and on the ground. no finish keeps the soft green
public final class ItemTints {
    private ItemTints() {}

    public static void register(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> tintIndex == 0 ? 0xFF000000 | colour(stack.get(Content.SELECTED_FINISH.get())) : -1, Content.APPLICATOR.get());
    }

    static int colour(String finish) {
        if (finish == null) return 0xB8D4AE;
        return switch (finish.substring(finish.indexOf(':') + 1)) {
            case "ivory" -> 0xF0E6C0;
            case "green" -> 0x7FAE86;
            case "charcoal" -> 0x8A8F98;
            case "wood" -> 0xB07050;
            case "copper" -> 0xE8944A;
            case "amber" -> 0xFFC23A;
            case "window" -> 0x7F9BE0;
            default -> 0xB8D4AE;
        };
    }
}
