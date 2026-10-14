package dev.nullspace.server;

import dev.nullspace.core.Permission;
import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ServerConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_ROOMS, MAX_GUESTS, MAX_TIMED_HOURS, MAX_ANCHORS, MAX_ANCHOR_CHUNKS,
            FIRST_EXPANSION, SECOND_EXPANSION, CEILING_FIRST, CEILING_SECOND, HALL_MODULES, HALL_NETHERITE, MAX_ROOM_WIDTH, MAX_CEILING_HEIGHT, ANNEX_BASE, ANNEX_STEP, RECALL_MINUTES,
            HUB_MAX_DESTINATIONS, HUB_LIFT_HOLD_TICKS, HUB_PAD_COOLDOWN_TICKS, HUB_CHRONON_COST, TEAMS_MAX_SIZE, CARDS_MAX_LIVE;
    public static final ModConfigSpec.BooleanValue ANCHORS, SPAWN, KEEP_INVENTORY, KEEP_XP, OPTIONAL_PRESETS,
            TEMPAD_PARTS, ENTITY_TRANSPORT, OP_RECOVERY, FAIL_CLOSED_AUTOMATION, EMERGENCY_EXIT, RECALL, HUB, HUB_USE_DOOR, TEMPAD_CIRCLE, ARCHIVE_LINK, TEAMS_SERVER_TEAMS, CARDS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> TEAMS_MODE, TEAMS_MINIMUM_RANK;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> GUEST_PERMISSIONS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PRESETS;
    static {
        var b = new ModConfigSpec.Builder();
        b.comment("Rooms are 9, 13 or 17 wide (the starter room can go on to a 21 wide Grand Hall) and 6, 9 or 12 clear blocks high. Existing geometry never shrinks.",
                "Caps and costs apply only to future additions. Allocation spacing is a persistent format invariant.").push("rooms");
        MAX_ROOMS=b.defineInRange("maximumRooms",9,1,49);
        MAX_ROOM_WIDTH=b.comment("Widest a room may be enlarged to: 9, 13, 17 or 21 (a value in between rounds down). 21 is the Grand Hall, only for the starter room; 17 switches the Hall off.",
            "Rooms that are already wider keep their size; only the next enlargement is refused.").defineInRange("maximumRoomWidth",21,9,21);
        MAX_CEILING_HEIGHT=b.comment("Tallest clear height a room's ceiling may be raised to: 6, 9 or 12 (a value in between rounds down). 6 switches ceiling raising off.",
            "Rooms that are already taller keep their height; only the next raise is refused.").defineInRange("maximumCeilingHeight",12,6,12);
        b.pop().push("progression");
        FIRST_EXPANSION=b.comment("Spatial Expanders for tier 9 to 13, plus four diamonds.").defineInRange("firstExpansionModules",1,1,64);
        SECOND_EXPANSION=b.comment("Spatial Expanders for tier 13 to 17, plus one netherite ingot.").defineInRange("secondExpansionModules",2,1,64);
        CEILING_FIRST=b.comment("Spatial Expanders for raising a ceiling from 6 to 9 blocks, plus two diamonds.").defineInRange("ceilingFirstModules",1,1,64);
        CEILING_SECOND=b.comment("Spatial Expanders for raising a ceiling from 9 to 12 blocks, plus four diamonds.").defineInRange("ceilingSecondModules",2,1,64);
        HALL_MODULES=b.comment("Spatial Expanders for the Grand Hall (starter room, 17 to 21 wide).").defineInRange("grandHallModules",4,1,64);
        HALL_NETHERITE=b.comment("Netherite ingots for the Grand Hall.").defineInRange("grandHallNetherite",2,0,9);
        TEMPAD_PARTS=b.comment("Also ask for Tempad's own parts: one Chronon Cell per ceiling raise, two Time Steel for the Grand Hall. false drops them (Tempad stays a required dependency either way).").define("requireTempadIngredients",true);
        ANNEX_BASE=b.comment("Annex Permits for second room; each later room adds annexStep. Extra diamonds equal existing room count.").defineInRange("annexBase",1,1,64);
        ANNEX_STEP=b.defineInRange("annexStep",1,0,8);
        b.pop().push("access");
        MAX_GUESTS=b.defineInRange("maximumGuests",32,0,256);
        MAX_TIMED_HOURS=b.defineInRange("maximumInvitationHours",720,1,87600);
        SPAWN=b.comment("Global veto, even if an owner or guest has SET_SPAWN.").define("allowArchiveSpawn",true);
        ENTITY_TRANSPORT=b.comment("Allow non-player entities that Tempad itself supports.").define("allowEntityTransport",true);
        FAIL_CLOSED_AUTOMATION=b.comment("Deny block/item use when a guest lacks either storage or station permission for unclassified modded blocks.").define("conservativeModdedInteractions",true);
        GUEST_PERMISSIONS=b.comment("Permissions owners may grant guests (ENTER, STORAGE, INTERACT_BLOCK, BUILD, INTERACT_ENTITY, SET_SPAWN). ADMIN can never be granted. Removing one revokes it everywhere immediately. Leave ENTER out to switch guest visits off entirely (owners still enter).")
            .defineListAllowEmpty("allowedGuestPermissions", List.of("ENTER","STORAGE","INTERACT_BLOCK","BUILD","INTERACT_ENTITY","SET_SPAWN"), () -> "ENTER", x -> x instanceof String t && !t.equalsIgnoreCase("ADMIN") && Arrays.stream(Permission.values()).anyMatch(p -> p.name().equalsIgnoreCase(t)));
        EMERGENCY_EXIT=b.comment("If Tempad's own restrictions changed while a visitor was inside, let the permanent exit still evacuate them to their recorded entry point (never to an arbitrary place).").define("allowEmergencyExit",true);
        b.pop().push("activity");
        ANCHORS=b.comment("Global veto takes effect on the next one-second ticket reconciliation; installed upgrades remain installed.").define("allowContinuityAnchors",true);
        MAX_ANCHORS=b.defineInRange("maximumActiveAnchors",8,0,128);
        MAX_ANCHOR_CHUNKS=b.defineInRange("maximumChunksPerAnchor",81,1,256);
        b.pop().push("recall");
        RECALL=b.comment("Recall Module: lets the Archive Exit lead to where the owner last died outside the archive. Global veto: off refuses new installs and ignores installed modules and any remembered spot.").define("enabled",true);
        RECALL_MINUTES=b.comment("How long a remembered death spot stays usable, in minutes (1 to 1440).").defineInRange("expiryMinutes",60,1,1440);
        b.pop().push("hub");
        HUB=b.comment("Waypoint Hall: the sunken hall under the starter room, with its lift and Destination Pads. Global veto: off refuses new installs and stops the lift and the pads (the hall's own Archive Exit still works). Installed halls stay installed.").define("enabled",true);
        HUB_MAX_DESTINATIONS=b.comment("Most destinations an archive can save (the hall has twelve pedestals, so up to twelve are shown at once; the rest wait in the list).").defineInRange("maxDestinations",12,1,48);
        HUB_LIFT_HOLD_TICKS=b.comment("How long, in ticks (20 = one second), the owner holds sneak on the starter room's lift pad before it takes them down.").defineInRange("liftHoldTicks",20,5,200);
        HUB_PAD_COOLDOWN_TICKS=b.comment("Ticks a player must wait between two uses of Destination Pads.").defineInRange("padCooldownTicks",100,0,12000);
        HUB_USE_DOOR=b.comment("true: a pad opens a short real Tempad door (Tempad's own rules and other mods' door vetoes apply). false: the pad teleports the player directly after the same safety checks.").define("useDoor",true);
        HUB_CHRONON_COST=b.comment("Chronons charged from a Tempad device in the player's inventory for each pad trip (0 = free, the default; creative players never pay).").defineInRange("chrononCost",0,0,100000);
        b.pop().push("teams");
        TEAMS_MODE=b.comment("Team access (needs a team mod; FTB Teams is supported). Owners can let their party teammates into their archive; nothing happens until an owner switches it on for their own archive.",
            "off: the feature is switched off and every team grant stops at once. members: party members (rank member or better). members_and_allies: also the party's allies.",
            "The most an owner can give a team is what allowedGuestPermissions (access section) allows guests; ADMIN can never be granted.")
            .define("mode", "members", x -> x instanceof String t && List.of("off", "members", "members_and_allies").contains(t.toLowerCase(Locale.ROOT)));
        TEAMS_MINIMUM_RANK=b.comment("The lowest party rank that gets in: member or officer. Allies (members_and_allies) are not ranked and are not affected by this.")
            .define("minimumRank", "member", x -> x instanceof String t && List.of("member", "officer").contains(t.toLowerCase(Locale.ROOT)));
        TEAMS_MAX_SIZE=b.comment("A party with more members than this gets no archive access (a big public party is not a circle of friends). Counts members of rank member or better.").defineInRange("maxTeamSize", 16, 1, 1024);
        TEAMS_SERVER_TEAMS=b.comment("Also accept admin-made server teams (they can hold everybody). false: only players' own parties.").define("serverTeams", false);
        b.pop().push("cards");
        CARDS_ENABLED=b.comment("Archive Cards: Tempad Location Cards an owner prints at the Archive Terminal that let the holder in on a single-visit, timed or until-revoked pass.",
            "false: no card can be printed and every card in the world stops working at once (visitors who came in on a card are returned). What a card may give is limited by allowedGuestPermissions (access section); ADMIN can never be on a card; a timed card cannot last longer than maxTimedHours.")
            .define("enabled", true);
        CARDS_MAX_LIVE=b.comment("How many cards one owner can have in use at once (revoked, spent and expired cards do not count).").defineInRange("maxLive", 8, 1, 32);
        b.pop().push("death");
        KEEP_INVENTORY=b.comment("Allow owner opt-in inventory retention. Automatically vetoed when a known grave mod is loaded.").define("allowKeepInventory",false);
        KEEP_XP=b.comment("Allow owner opt-in XP retention. Automatically vetoed when a known grave mod is loaded.").define("allowKeepExperience",false);
        b.pop().push("presets");
        OPTIONAL_PRESETS=b.define("enableOptionalModPresets",true);
        PRESETS=b.comment("Datapack preset IDs offered at first claim. Missing blocks use each entry's fallback.").defineListAllowEmpty("enabled", List.of("nullspace:classic","nullspace:create","nullspace:sophisticated","nullspace:handcrafted","nullspace:supplementaries","nullspace:farmersdelight"), ()->"nullspace:classic", x->x instanceof String s && s.contains(":"));
        b.pop().push("tempad");
        TEMPAD_CIRCLE=b.comment("Archive circle: an extra Screening Device / Chronomark setting that lets players linked by an archive invitation (in either direction, invitation still valid) see each other's live position in their Tempad. Privacy: it shares coordinates. false: the setting stays in the list but nobody is ever let through (takes effect at the next Tempad list refresh).").define("archiveCircle",true);
        ARCHIVE_LINK=b.comment("Archive Link Tape: a full Tempad lists its player's OWN archive in its destination list only when the Archive Link Tape (installed at a Tempad Workstation) is on it. Archives the player was invited to are listed as before. Entry rules, chronon costs and cooldowns are not touched. false: the upgrade does nothing and every full Tempad lists its player's own archive without it (the behaviour before the upgrade existed). The cartridge-linked Timedoor Projector is never affected.").define("archiveLink",true);
        b.pop().push("recovery");
        OP_RECOVERY=b.comment("Enable operator recovery commands; destructive removal requires backup token and an empty leaf room.").define("enableOperatorRecovery",true);
        b.pop(); SPEC=b.build();
    }
    private ServerConfig() {}
}
