# contributing

bug reports are the most useful thing you can send. small focused pull requests are fine, and for anything bigger please open an issue first.

## reporting an issue

1. look through the open and closed issues first.
2. open a new issue and pick the form that fits: bug report, feature request or compatibility report. blank issues are off on purpose.
3. give the versions (minecraft, neoforge, tempad, nullspace), the other mods you run, and numbered steps to reproduce. whether it still happens with only tempad and nullspace is the most useful thing you can tell me.
4. attach the logs.
   - client or single player: `logs/latest.log` in the game folder, and the newest file in `crash-reports/` if the game crashed.
   - dedicated server: the server's `logs/latest.log`.
   - attach the file itself, not a screenshot of it. a paste link is fine for very large ones.
5. check the log for private things first. it holds your player name, the ip of the server you joined, and your computer's user name in file paths. replace what you don't want to share.

a way to break into an archive, duplicate items or crash a server should not go in a public issue. see [SECURITY.md](SECURITY.md).

## labels

`bug`, `feature`, `compat`, `docs`, `art`, `gui`, `perf`, `security`, `needs-repro`, `good-first-issue`, `wontfix`, plus area labels like `tempad`, `carryon`, `ftb` and `jei-emi`.

## building and testing

you need java 21. gradle fetches the rest.

```sh
./gradlew build                  # compiles, runs the unit tests, builds build/libs/nullspace-1.21.1-<version>.jar
./gradlew test                   # unit tests only
./gradlew runGameTestServer      # the gametests on a real server
```

the unit tests are in `src/test`. they cover the core rules (permissions, layout, cost, saved data, text) without starting minecraft.

the gametests are in `src/gametest`. they start a headless server, build real archives and check rooms, the exit door, the hall, the terminal and the shell's protection. the run takes about ten seconds and writes its world into `run-gametest/`, which git ignores. the test code is on the classpath of the dev runs only and is never in the jar. send a pull request with a gametest when you change protection, travel, rooms or persistence code.

## art and licences

code is mit (`LICENSE`). original art, models and sounds are cc by 4.0 (`LICENSE-ASSETS.md`). please don't add anything from tempad or any other mod, whether textures, models, sounds or screenshots that show them. tempad's non code assets are all rights reserved. see [THIRD_PARTY.md](../THIRD_PARTY.md).
