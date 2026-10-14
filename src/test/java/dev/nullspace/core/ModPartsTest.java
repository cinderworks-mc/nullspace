package dev.nullspace.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModPartsTest {
    interface PartLike {}
    static class PartBase implements PartLike {}
    static class ColouredPart extends PartBase {}
    interface SubInterface extends PartLike {}
    static class ViaSubInterface implements SubInterface {}
    interface FacadeLike {}
    static class Facade implements FacadeLike {}

    private static final Set<String> PARTS = Set.of(PartLike.class.getName()), FACADES = Set.of(FacadeLike.class.getName());

    @Test void typesThatImplementThePartInterfaceAreParts() {
        assertEquals(ModParts.Kind.PART, ModParts.kind(PartLike.class, PARTS, FACADES), "the interface itself");
        assertEquals(ModParts.Kind.PART, ModParts.kind(PartBase.class, PARTS, FACADES), "an implementing class");
        assertEquals(ModParts.Kind.PART, ModParts.kind(ColouredPart.class, PARTS, FACADES), "a subclass of an implementing class (coloured cables)");
        assertEquals(ModParts.Kind.PART, ModParts.kind(ViaSubInterface.class, PARTS, FACADES), "through a sub-interface");
    }

    @Test void facadesAreTheirOwnKind() {
        assertEquals(ModParts.Kind.FACADE, ModParts.kind(Facade.class, PARTS, FACADES));
    }

    @Test void ordinaryTypesAreNeither() {
        for (Class<?> c : new Class<?>[] {Object.class, String.class, ArrayList.class})
            assertEquals(ModParts.Kind.NONE, ModParts.kind(c, PARTS, FACADES), c.getName());
    }

    @Test void theShippedNamesAreAe2sAndOrdinaryItemsAreNotParts() {
        assertTrue(ModParts.PART_TYPES.contains("appeng.api.parts.IPartItem"));
        assertTrue(ModParts.FACADE_TYPES.contains("appeng.api.implementations.items.IFacadeItem"));
        assertEquals(ModParts.Kind.NONE, ModParts.kind(Object.class));
    }
}
