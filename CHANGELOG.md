# Changelog

## 1.0.3 (Minecraft 26.3)

### Rivers and lakes
- Water reaches higher: rivers and lakes now form up to 6,000 m of terrain height (was 4,000 m; `rivers.max_water_m`). An existing `config/orogeny.properties` keeps its old value until you change it.
- Lakes in high mountain basins can be as large as any other lake (they were kept to small ponds).
- Streams follow the real downhill direction, wandering and joining like real drainage, instead of running in dead-straight parallel lines at 45 degrees on long even slopes.
- Fewer streams in very dry mountains: the minimum runoff given to high ground now shrinks in deserts.
- Lakes in cold climates (mean temperature below 0 °C at the water) freeze over: a layer of ice on top with liquid water under it (`rivers.freeze_snowy_lakes`, on by default).
- Large lakes no longer stand a few blocks above the ground along their shores: the small-scale terrain noise is kept from cutting dips through a lake's rim.
- Axolotls only in lakes above 4,500 m (was 3,000 m).

### Configuration
- `config/orogeny.properties` lists every setting, grouped by topic with a comment for each. Settings missing from an existing file (from an older version) are added to its end with their defaults; your own values are kept.

### Fixes
- Canyon ravines stop short of rivers and lakes instead of cutting under them, so rivers no longer cross a ravine on a plug of rock.

## 1.0.2 (Minecraft 26.3)

### Fixes
- No lakes perched on the flanks of stratovolcanoes behind walls of water. Lakes in dormant craters and pit craters stay.

## 1.0.1 (Minecraft 26.3)

### Fixes
- Rock shelters only at the foot of real cliffs, where the ground levels out below them, and on a coarser grid: steep mountain faces are no longer pocked with dozens of overhangs.
- Mod links (homepage, source, issues) point at the renamed repository, github.com/calvin-bates/orogeny.

## 1.0.0 (Minecraft 26.3)

First public release of Orogeny, a fork of [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion-mc) by xandergos.

### Terrain
- Ported to Minecraft 26.3 with a build range of Y -64 to 2031.
- World scale (1 to 6) picked when you create a world, saved with it.
- Shelf, slope and abyss sea floor; summits eased under the build limit instead of cut flat.

### Rivers and lakes
- Hydrology from the generated terrain: streams, navigable rivers and main rivers carving their own valleys; lakes in filled basins, sitting flush with their shores.
- Deltas, oxbow lakes, alluvial fans, waterfalls, river sinks into caves; level water steps on steep ground; salt flats where arid lakes dry out.

### Oceans and coasts
- Volcanic islands drawn by the AI model, from young islands through flooded calderas and barrier-reef islands to atolls; model-drawn archipelagos; mushroom islands and mirage isles.
- Reefs, lagoons, carbonate banks, seamounts and guyots, trenches, rift seas and active margins, hydrothermal vents.
- Spits, tombolos, cuspate forelands, barrier islands, sea stacks, sea arches, sea caves and wave-cut overhangs.
- Orogeny's own iceberg fields and arctic ice islands (vanilla icebergs are off), sea ice in cold seas.

### Biomes
- 85 new surface and cave biomes placed by climate: vanilla-style patch biomes (meadow, cherry grove, dark forest, rare large pale gardens, old growth forests, dappled forest), real-world biomes (boreal forest, Siberian taiga, temperate and tropical rainforests, cloud forest, redwoods, aspen groves, steppe, prairie, tundra, bog, marsh, salt marsh, tidal flats and more), and showpieces (volcanoes and lava fields, glaciers, canyons, badlands mesas, hoodoos, dunes, karst towers in the tropics, Yellowstone with geysers, sulfur and cinnabar, Yosemite, painted mountains, white cliffs, geothermal basins).
- Active volcanoes with lava lakes in their craters and live lava streams pouring down their flows from the crater's rim.
- Landforms only appear where the terrain already suits them.

### Caves
- Orogeny's own noise caves under any terrain, measured from the ground above, with a solid roof.
- 36 cave biomes in common, uncommon, rare and very rare tiers, including vanilla's lush caves, deep dark and sulfur caves (with sulfur cubes, under volcanic ground and Yellowstone).
- Giant halls (geode hall, deep dark forest, selenite beams), ancient cities in caverns that hold them, underground lakes and lava, cenotes, blue holes, ravines, deep shafts, collapse domes and cave mouths.
- Cave formations: columns, stalactites and stalagmites, flowstone, rimstone pools, crystal rooms, boxwork, natural bridges, rock shelters, travertine terraces.

### Structures and gameplay
- Villages and outposts sit on ground pads; vanilla structures spawn everywhere except ancient cities, which stay under mountains.
- Axolotls in high alpine lakes; fish in lakes at any height; boats float back up to the surface.

### Tools and performance
- `/orogeny-explore`: browser map with relief, elevation, climate, rivers, biomes and caves views.
- `/orogeny-find`: teleport to the nearest biome, cave or landmark.
- Tiles around you are built ahead of time (`performance.prefetch_tiles`); prioritised inference queue; faster cave field.

### Inspiration
Several biomes and caves are inspired by other mods' designs and rebuilt from vanilla blocks to fit Orogeny's terrain. No code or assets from those mods are included.
- **[Terralith](https://modrinth.com/datapack/terralith)** by Stardust Labs: birch taiga, Siberian grove and taiga, forested and temperate highlands, steppe, painted mountains, white cliffs, desert oasis, blooming plateau and valley, lush valley, mirage isles, volcanoes and shield volcanoes, Yellowstone, Yosemite cliffs and lowlands; andesite, diorite, granite, tuff, frostfire, fungal, infested, thermal and mantle caves, and the underground jungle.
- **[William Wythers' Overhauled Overworld](https://modrinth.com/datapack/wwoo)**: cherry grove, boreal forest, taiga mountain, taiga clearing, autumnal forest (in the dappled forest), geothermal basins, tropical atolls; the deep dark forest and deep dark incursion caves.
- Also Biomes O' Plenty (redwoods, crystalline chasm, spider nest) and Darker Depths (molten cavern).
