# Cobble Upgrader

A Cobblemon add-on that lets you gamble **items and the Pokémon in your party or PC** for a shot at something better.
Bet a Pokémon or a stack of items, pick the Pokémon or item you want, and spin the wheel.

- **Minecraft 1.21.1 · Fabric · Cobblemon 1.8+** (also needs Fabric API and Fabric Language Kotlin, which Cobblemon already requires)
- **Server-side only:** players don't need it installed to use it on a server. In singleplayer, just drop it in your mods folder.

## Download

Go to **Releases → Latest build** and download the `.jar`, then put it in your `mods` folder.
Every push to `main` rebuilds it automatically.

## How to use

Type **`/upgrader`** in game.

1. **Pick a bet:** a Pokémon from your **party**, your **PC**, or **items** from your inventory
   (left-click = whole stack, right-click = one; then fine-tune with the +/- buttons).
2. **Pick a target:** any Pokémon species (shift-click for a shiny, set the level with +/-) or an item prize.
3. **Press UPGRADE.** Pokémon bets ask for a second click to confirm.

Your bet is used up whether you win or lose. A won Pokémon goes to your party, or to your PC if the party is full.
It gets random IVs, just like a wild catch.

Shortcut: `/upgrader target <pokemon> [level] [shiny]` opens the upgrader with that Pokémon already picked,
e.g. `/upgrader target charizard 50 shiny`.

## Odds

```
chance = (value of your bet / value of the target) x houseEdge      (capped at maxChance)
```

With the default house edge of 0.9 you lose about 10% of your value on average.
If the chance would be below `minChance` (0.1%), the upgrade is **refused** rather than rounded up, so you can't
bet a single dirt block for a Master Ball.

**Pokémon value** = species base value × level × shiny × IVs

| Factor | Default |
| --- | --- |
| Species base | `10 × (base stat total / 300)² × √(255 / catch rate)`: strong, hard-to-catch species are worth more |
| Legendary / Mythical / Ultra Beast / Paradox | ×8 / ×10 / ×5 / ×4 |
| Level | ×(1 + level × 0.04), so level 100 is ×5 |
| Shiny | ×10 |
| IVs | ×0.75 (0 IVs) to ×1.25 (perfect), and another ×1.5 for a perfect 6×31 |

**Item value** comes from the `itemPrices` list in the config (diamond = 30, netherite ingot = 450, master ball = 3000, …).
Items that aren't listed are worth 1 each (×4 uncommon, ×16 rare, ×64 epic). Only listed items can be won.

## Rules that protect players

- Can't be used during a battle.
- Pokémon that are untradeable or out in a pasture can't be bet.
- Closing the screen mid-spin doesn't change anything: the result was decided when you pressed UPGRADE, and you get it right away.
- Every Pokémon bet and win is written to the server log.

## Config

`config/cobbleupgrader.json` is created the first time you run the game. Edit it, then run `/upgrader reload` (needs operator).
You can change the house edge, odds limits, cooldown, every value multiplier, item prices, per-species prices
(`speciesValueOverrides`), and blacklists for species and items. You can also turn Pokémon/item bets, prizes,
shiny prizes and legendary prizes on or off, and set who can use `/upgrader` (`commandPermissionLevel`: 0 = everyone, 2 = ops).

## Building it yourself

Install Java 21, then run `./gradlew build` (on Windows: `gradlew.bat build`). The jar ends up in `build/libs/`.
