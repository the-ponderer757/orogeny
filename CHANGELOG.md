# Changelog

## 1.0.5 (Minecraft 26.3)

### Rivers and lakes
- Only lakes in snowy biomes freeze over: biomes where it snows rather than rains and snow lies on the ground (snowy plains, snowy taiga, groves, snowy slopes, frozen peaks, jagged peaks, arctic, glaciers, snowy beaches). Lakes in cold meadows, grasslands, tundra and other biomes without snow stay open water. Each lake is decided whole by the biomes in and around it, so a mountain lake with bare rocky shores still freezes evenly.
- Lake ice that already generated in a biome without snow (worlds made before 1.0.5 froze lakes in any cold climate) melts back into water over a few minutes while players are nearby (`rivers.melt_natural_ice`, on by default). Ice you place yourself never melts this way; sea ice, icebergs and cave ice are left alone. Ice placed in those lakes before updating to 1.0.5 can't be told apart from natural ice and may melt.

## 1.0.4 (Minecraft 26.3)

### Biomes and caves
- **Yellowstone** redesigned after the real park: lodgepole pine stands with open meadows, standing dead trunks from old fires and young regrowth; pale sinter flats with sulfur specks; yellow, orange and white rock on steep faces; hot springs ringed by bands of colour like Grand Prismatic Spring (yellow with sulfur, orange with cinnabar, brown, then a white sinter apron), some boiling, and bubbling mud pots. Geysers stay.
- **Yosemite** redesigned: pale granite walls with diorite, calcite and andesite bodies, grassy ledges and waterfalls; the valley floor is open woodland of ponderosa pine, incense cedar and black oak with the odd giant sequoia, and meadow flowers.
- **Fumarole caves** replace the frostfire caves: caves melted into ice by volcanic heat under cold ground, with ice walls and icicles, snow and ice floors, magma seams, steaming sulfur pools and sulfur crusts, and a pale haze with drifting ash.
- **Deep dark infestation** replaces the deep dark incursion: the deep dark creeping up into ordinary caves 60-140 blocks underground, with sculk veins over the walls, sculk mats, sculk stalks topped with sensors, sculk dripping from the ceiling and the odd shrieker.
- **Redwood forests** are much bigger: each forest is on average about ten times the area it was.

These four biomes no longer reuse other mods' design data. Worlds made before 1.0.4 keep their already generated terrain; new chunks use the new designs. Caves already saved as frostfire caves or deep dark incursion keep their blocks, but Minecraft will log a warning for the missing biome and show a default biome there.

### Servers
- New `world.scale` setting (1 to 6, default 2) in `config/orogeny.properties` picks the scale of a world created without the create-world screen, as on a dedicated server; the world keeps its scale after that. The log reports each world's scale when it loads.
- `/orogeny-find` and `/orogeny-column` are restricted to operators (permission level 2, like `/tp`) and the server console; in singleplayer the world's owner can always use them, even with cheats off.
- The README has a "Running on a server" section (installing, choosing the scale, `level-type=orogeny\:terrain_diffusion`).

## 1.0.3 (Minecraft 26.3)

### Rivers and lakes
- Water reaches higher: rivers and lakes now form up to 6,000 m of terrain height (was 4,000 m; `rivers.max_water_m`). If your config file already sets it, your value is kept.
- Lakes in high mountain basins can be as large as any other lake (they were kept to small ponds).
- Streams follow the real downhill direction, wandering and joining like real drainage, instead of running in dead-straight parallel lines at 45 degrees on long even slopes.
- Fewer streams in very dry mountains: the minimum runoff given to high ground now shrinks in deserts.
- Lakes in cold climates (mean temperature below 0 °C at the water) freeze over: a layer of ice on top with liquid water under it (`rivers.freeze_snowy_lakes`, on by default).
- Lakes no longer stand above the ground at their shores behind stair-stepped banks. A lake that crossed the cold limit for water (-15 °C) was cut along that line, leaving its colder part as dry ground below the water; the limit now keeps or drops each lake whole. Each lake's level is also fitted to its rim as the world's blocks draw it, and the small-scale terrain noise no longer cuts dips through a rim.
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
- Mod links (homepage, source, issues) point at the renamed repository, github.com/the-ponderer757/orogeny.

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
