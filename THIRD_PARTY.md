# third party notices

nullspace's own source code, build scripts and data files are under the mit licence (`LICENSE`). its original art, sounds and models are under cc by 4.0 (`LICENSE-ASSETS.md`). `META-INF/neoforge.mods.toml` declares `MIT AND CC-BY-4.0`. these licences cover only nullspace's own work, not tempad or any other mod.

## tempad (required dependency, not bundled)

- tempad by terrarium, https://github.com/terrarium-earth/Tempad, version 3.0.4.
- licence: terrarium license v1. code (source, scripts, build files, data files, documentation and configs, as that licence defines them) is mit. everything else (textures, models, sounds and other non code assets) is all rights reserved.
- nullspace compiles against tempad's public api and its common classes (`TempadLocations`, `TimedoorEvent`, `TimedoorEntity`) and uses tempad's gateway visuals and sounds at runtime through the game. no tempad texture, model, sound, string or other non code asset is copied or redistributed. nullspace's art is drawn from scratch in aseprite and only follows tempad's style.
- the jar does not contain tempad code. tempad's own libraries (resourceful lib, resourceful config, kotlin for forge) are installed alongside it.

## platform

- minecraft is a trademark of mojang ab and microsoft. this mod is not affiliated with or endorsed by them. using it needs a legitimate copy of the game and follows the minecraft eula and brand guidelines.
- the lettering in the banner and social images under `assets/branding` uses the glyph shapes of minecraft's default font. those shapes belong to mojang and are not covered by the asset licence.
- neoforge (lgpl-2.1-only) is the modding platform and is not bundled. official mojang mappings are used through neoforge's tooling.

## optional mods (soft dependencies, compile only, not bundled)

these are declared `type="optional"` in `neoforge.mods.toml`. nullspace compiles against their public api and loads the matching code only when the mod is present. nothing of them is in the jar or the repository, and they keep their own licences.

- open parties and claims 0.32.7 (lgpl-3.0-only) and ftb chunks 2101.1.22 (all rights reserved), with ftb teams, ftb library and architectury for ftb chunks' types. used only to refuse claiming in the archive.
- ftb teams 2101.1.11 (all rights reserved). supplies parties for team access. the code that touches it is in `dev.nullspace.integration.ftbteams`.
- carry on (tested with 2.2.2.11, 2.2.4.4, 2.2.5.8 and 2.2.6.13). the guard in `integration/carryon` loads only when carry on is present.
- waystones 21.1.46 by blaytheninth (all rights reserved), with balm 21.0.66 for the event's base class, both from the modrinth maven. the guard in `integration/waystones` loads only when waystones is present.

## tools

- the `.aseprite` sources, `.bbmodel` models and `.svg` drawings are under `assets/source`.
- the gametests live in `src/gametest` and are not part of the released jar.
