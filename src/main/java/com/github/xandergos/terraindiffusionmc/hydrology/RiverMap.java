package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;

/**
 * Turns an elevation + climate window into river / lake classes using {@link DrainageNetwork}.
 *
 * <p>Pure Java (no Minecraft types). All coordinates are native pixels. The caller should pass a
 * window with a margin of at least {@link Params#flowSteps} pixels around the area of interest;
 * results inside that area are then identical no matter how the windows are cut.
 */
public final class RiverMap {
    public static final byte NONE = 0, STREAM = 1, RIVER = 2, MAIN = 3, LAKE = 4;

    public static final class Params {
        /** Discharge thresholds in "cell equivalents" of runoff (1 cell = one native pixel of ~800 mm rain). */
        public float streamQ = 2400f, riverQ = 9000f, mainQ = 25000f;
        /** Only upstream area within this many flow steps counts (keeps regions seamless). */
        public int flowSteps = 80;
        /** Minimum lake size (native pixels) and depth (metres of fill). */
        public int lakeMinArea = 150;
        /**
         * Blocks per native pixel in the world the lakes are for (its scale), or 1 to skip {@link #fitLakesToBlocks}. Set
         * before each region is computed (the scale can change between worlds; the region cache is cleared then).
         */
        public volatile int blockScale = 1;
        /** Larger depressions are artefacts of the generated terrain (a basin the sea would drain), not lakes. */
        public int lakeMaxArea = 2500;
        public float lakeMinDepth = 2f;
        /** No water above this terrain height (metres) or below this temperature (deg C). */
        public float maxElevM = 6000f, minTempC = -15f;
        /**
         * A closed basin too big to be a lake, at least this deep (metres) and on ground higher than {@link #basinMinElev}
         * (hills and mountains), drains to its lowest point instead of being filled to its rim, and gets a small lake
         * of up to {@link #lakeMaxArea} pixels there, below the basin's rim. Filled flat, its streams were routed across
         * the imaginary flat water surface: along and up the real slopes, carved deep into the hillsides.
         */
        public float basinMinDepth = 15f, basinMinElev = 700f;
    }

    /** Per-cell results for the whole window. */
    public static final class Result {
        public final byte[] cls;
        /** Downstream cell index, or -1. */
        public final int[] downstream;
        /** Water surface elevation in metres (depression-filled terrain). */
        public final float[] surface;

        /** Terrain elevation in metres (before filling), or null. */
        public final float[] elev;
        /**
         * Channel size per cell, a continuous version of the class: 1 for the smallest stream, 2 where a stream becomes a
         * river, 3 where a river becomes a main river (log of the discharge between the thresholds), never smaller
         * downstream; 0 off the channels. Channel width and depth follow it, so a stream widens gradually into a river
         * instead of jumping from a few blocks to a wide river at one pixel. Null for results built without it.
         */
        public float[] size;

        Result(byte[] cls, int[] downstream, float[] surface) {
            this(cls, downstream, surface, null);
        }

        Result(byte[] cls, int[] downstream, float[] surface, float[] elev) {
            this.elev = elev;
            this.cls = cls;
            this.downstream = downstream;
            this.surface = surface;
        }
    }

    private static final FastNoiseLite JITTER = new FastNoiseLite(99);
    static {
        JITTER.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        JITTER.SetFrequency(1f / 9f);
    }

    private RiverMap() { }

    /** Convenience for callers that only need the classes. */
    public static byte[] compute(float[] elev, float[] climate, int H, int W, int i0, int j0, Params p) {
        return computeFull(elev, climate, H, W, i0, j0, p).cls;
    }

    /**
     * @param elev    elevation in metres, row-major H*W
     * @param climate 5*H*W climate (temp, t_season, precip, p_cv, ...) or null
     * @param i0      world row of the window's first row (for position-stable jitter noise)
     * @param j0      world column of the window's first column
     */
    /** Clears every lake component (4-connected) with a pixel within {@link #HOT_LAKE_GAP} of a hot pixel. */
    private static void dropLakesNearHot(boolean[] lake, float[] hot, int H, int W, int[] stack, int[] members) {
        int n = H * W, g = HOT_LAKE_GAP;
        // Hot ground grown by the gap (Chebyshev), as two separable passes.
        boolean any = false;
        boolean[] rowGrown = new boolean[n];
        for (int i = 0; i < n && !any; i++) any = hot[i] >= 2f;
        if (!any) return;
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                if (hot[r * W + c] < 2f) continue;
                for (int cc = Math.max(0, c - g); cc <= Math.min(W - 1, c + g); cc++) rowGrown[r * W + cc] = true;
            }
        boolean[] near = new boolean[n];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                if (!rowGrown[r * W + c]) continue;
                for (int rr = Math.max(0, r - g); rr <= Math.min(H - 1, r + g); rr++) near[rr * W + c] = true;
            }
        boolean[] seen = new boolean[n];
        for (int s = 0; s < n; s++) {
            if (!lake[s] || seen[s]) continue;
            int sp = 0, count = 0;
            boolean touches = false;
            stack[sp++] = s;
            seen[s] = true;
            while (sp > 0) {
                int i = stack[--sp];
                members[count++] = i;
                touches |= near[i];
                int r = i / W, c = i % W;
                if (r > 0 && lake[i - W] && !seen[i - W]) { seen[i - W] = true; stack[sp++] = i - W; }
                if (r < H - 1 && lake[i + W] && !seen[i + W]) { seen[i + W] = true; stack[sp++] = i + W; }
                if (c > 0 && lake[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1; }
                if (c < W - 1 && lake[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1; }
            }
            if (!touches) continue;
            for (int k = 0; k < count; k++) lake[members[k]] = false;
        }
    }

    /**
     * Clears every lake component (4-connected) with more than {@link #CONE_LAKE_SHARE} of its pixels on a volcano's
     * cone or lava flows (mask values from {@link #VOLC_CONE} up to, not including, {@link #VOLC_CRATER}). The cone is
     * reshaped steeper than the model drew it, and the hollows left on its flanks ponded as lakes perched on the
     * slope, standing over the ground below them behind walls of water (Calvin, Nov 1, X -14253 Z 9538). A lake in a
     * dormant crater or pit crater stays.
     */
    private static void dropLakesOnCones(boolean[] lake, float[] hot, int H, int W, int[] stack, int[] members) {
        int n = H * W;
        boolean[] seen = new boolean[n];
        for (int s = 0; s < n; s++) {
            if (!lake[s] || seen[s]) continue;
            int sp = 0, count = 0, onCone = 0;
            stack[sp++] = s;
            seen[s] = true;
            while (sp > 0) {
                int i = stack[--sp];
                members[count++] = i;
                if (hot[i] >= VOLC_CONE && hot[i] < VOLC_CRATER) onCone++;
                int r = i / W, c = i % W;
                if (r > 0 && lake[i - W] && !seen[i - W]) { seen[i - W] = true; stack[sp++] = i - W; }
                if (r < H - 1 && lake[i + W] && !seen[i + W]) { seen[i + W] = true; stack[sp++] = i + W; }
                if (c > 0 && lake[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1; }
                if (c < W - 1 && lake[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1; }
            }
            if (onCone <= CONE_LAKE_SHARE * count) continue;
            for (int k = 0; k < count; k++) lake[members[k]] = false;
        }
    }

    public static Result computeFull(float[] elev, float[] climate, int H, int W, int i0, int j0, Params p) {
        return computeFull(elev, climate, null, H, W, i0, j0, p);
    }

    /** Volcanic mask values (see {@link #computeFull}): a dormant crater, and a stratovolcano's lower cone. */
    public static final float VOLC_CRATER = 1.5f, VOLC_CONE = 0.5f;
    /** A lake with more than this share of its pixels on a volcano's cone or lava flows is dropped. */
    static final float CONE_LAKE_SHARE = 0.3f;

    /** Native pixels from an active volcano's hot ground within which no lake is kept (see {@link #computeFull}). */
    static final int HOT_LAKE_GAP = 4;
    /** Share of the rain that runs off fresh volcanic rock. */
    static final float POROUS_RUNOFF = 0.03f;

    /**
     * @param hot per pixel, 2 on an active volcano's hot ground (lava lake, hot crater, live lava channel), 1 on other
     *            fresh volcanic rock (almost no runoff there), 0 elsewhere, or null. A lake within {@link #HOT_LAKE_GAP} pixels of it is dropped whole: the hydrology ponded the
     *            hollows of the crater and its rim, and only the part on the hot ground itself was taken out later, so
     *            half-lakes stood beside the crater with walls of water (Calvin, Nov 1, an island volcano). Decided on
     *            the region's data, so every tile sees the same lakes.
     */
    public static Result computeFull(float[] elev, float[] climate, float[] hot, int H, int W, int i0, int j0, Params p) {
        int n = H * W;
        float[] routing = new float[n];
        float[] runoff = new float[n];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                float e = elev[i];
                if (e > 0f) {
                    // Tiny tie-breaking jitter only; land must never be pushed to or below sea level,
                    // or the coast gets fake "ocean" cells that make rivers stop short of the sea.
                    e = Math.max(0.05f, e + JITTER.GetNoise(j0 + c, i0 + r) * 0.3f);
                }
                routing[i] = e;
                float precip = climate != null ? Math.max(0f, climate[2 * n + i]) : 800f;
                float ro = Math.max(0.05f, Math.min(3f, precip / 800f));
                // Mountains wring rain and snowmelt out of the air even in a dry climate, so high ground never yields
                // less than a moderate runoff (otherwise whole mountain valleys in dry regions had no streams at all).
                // The floor itself shrinks in a desert (Calvin, Nov 1: a hyper-arid coastal range was combed with
                // streams): full at 280 mm a year, about 60 % at 100 mm, a third at 30 mm.
                float dry = (float) Math.sqrt(Math.min(1f, precip / 280f));
                if (e > 1500f) ro = Math.max(ro, 0.35f * dry);
                else if (e > 700f) ro = Math.max(ro, 0.2f * dry);
                // Fresh volcanic rock is porous: rain sinks into it, so streams are not born on a volcano's cone or its
                // lava flows (they burst out at the rock's edge as full streams, a wide sheet of water against the
                // rock, Calvin, Nov 1, X -42141 Z -55754). Water flowing in from outside still runs through.
                if (hot != null && hot[i] >= 1f) ro *= POROUS_RUNOFF;
                runoff[i] = ro;
            }
        }

        DrainageNetwork net = DrainageNetwork.compute(routing, runoff, H, W, 0f, true, null, i0, j0);
        boolean[] lake = new boolean[n];
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int[] members = new int[n];

        // --- big closed basins in the hills and mountains drain to their lowest point (see Params#basinMinDepth).
        // A basin can hold smaller basins, so this repeats a few times on the re-computed network.
        boolean[] sinks = null;
        float[] spill = null;   // per sink: the basin's spill level (its rim), which its lake stays under
        for (int pass = 0; pass < 4; pass++) {
            boolean added = false;
            java.util.Arrays.fill(seen, false);
            for (int s = 0; s < n; s++) {
                if (seen[s] || !net.inDepression[s] || elev[s] <= 0f) continue;
                int count = collectDepression(net, elev, s, H, W, seen, stack, members);
                if (count <= p.lakeMaxArea) continue;
                float maxDepth = 0f, meanElev = 0f;
                int pit = members[0];
                for (int k = 0; k < count; k++) {
                    int i = members[k];
                    maxDepth = Math.max(maxDepth, net.filled[i] - routing[i]);
                    meanElev += elev[i];
                    if (routing[i] < routing[pit]) pit = i;
                }
                meanElev /= count;
                if (maxDepth < p.basinMinDepth || meanElev < p.basinMinElev) continue;
                if (sinks == null) { sinks = new boolean[n]; spill = new float[n]; }
                if (!sinks[pit]) { sinks[pit] = true; spill[pit] = net.filled[pit]; added = true; }
            }
            if (!added) break;
            net = DrainageNetwork.compute(routing, runoff, H, W, 0f, true, sinks, i0, j0);
        }
        float[] q = net.limitedDischarge(runoff, p.flowSteps);
        byte[] out = new byte[n];

        // --- a lake at the bottom of each drained basin, where its streams end: the lowest pixels around the sink, up to
        // lakeMaxArea of them (Nov 1: was 40, which kept every mountain basin's lake a small pond; alpine lakes now get
        // the same size limit as any other), under one flat level that the ground rises above all the way round and
        // BASIN_FREEBOARD under the basin's rim
        if (sinks != null) {
            for (int s = 0; s < n; s++) {
                if (!sinks[s]) continue;
                int count = basinLake(routing, elev, s, p.lakeMaxArea, spill[s] - BASIN_FREEBOARD, H, W, lake, members);
                float level = 0f;
                for (int k = 0; k < count; k++) level = Math.max(level, routing[members[k]]);
                for (int k = 0; k < count; k++) { lake[members[k]] = true; net.filled[members[k]] = level; }
            }
        }

        // --- lakes: connected groups of depression cells that are big and deep enough
        java.util.Arrays.fill(seen, false);
        for (int s = 0; s < n; s++) {
            if (seen[s] || !net.inDepression[s] || elev[s] <= 0f || lake[s]) continue;   // (not over a basin lake)
            int sp = 0, count = 0;
            float maxDepth = 0f;
            stack[sp++] = s; seen[s] = true;
            while (sp > 0) {
                int i = stack[--sp];
                members[count++] = i;
                maxDepth = Math.max(maxDepth, net.filled[i] - routing[i]);
                int r = i / W, c = i % W;
                if (r > 0)     { int j = i - W; if (!seen[j] && net.inDepression[j] && elev[j] > 0f && !lake[j]) { seen[j] = true; stack[sp++] = j; } }
                if (r < H - 1) { int j = i + W; if (!seen[j] && net.inDepression[j] && elev[j] > 0f && !lake[j]) { seen[j] = true; stack[sp++] = j; } }
                if (c > 0)     { int j = i - 1; if (!seen[j] && net.inDepression[j] && elev[j] > 0f && !lake[j]) { seen[j] = true; stack[sp++] = j; } }
                if (c < W - 1) { int j = i + 1; if (!seen[j] && net.inDepression[j] && elev[j] > 0f && !lake[j]) { seen[j] = true; stack[sp++] = j; } }
            }
            float meanElev = 0f;
            for (int k = 0; k < count; k++) meanElev += elev[members[k]];
            meanElev /= count;
            int minArea = meanElev > 1200f ? Math.max(30, p.lakeMinArea / 4) : p.lakeMinArea;   // small alpine tarns
            if (count >= minArea && count <= p.lakeMaxArea && maxDepth >= p.lakeMinDepth) {
                for (int k = 0; k < count; k++) lake[members[k]] = true;
                // One flat water level per lake (the fill adds tiny random steps that would otherwise
                // show up as one-block ledges in the water).
                float level = 0f;
                for (int k = 0; k < count; k++) level = Math.max(level, net.filled[members[k]]);
                for (int k = 0; k < count; k++) net.filled[members[k]] = level;
            }
        }

        if (hot != null) {
            dropLakesNearHot(lake, hot, H, W, stack, members);
            dropLakesOnCones(lake, hot, H, W, stack, members);
        }
        if (p.blockScale > 1) fitLakesToBlocks(elev, lake, net.filled, H, W, p.blockScale, stack, members);

        for (int i = 0; i < n; i++) {
            float e = elev[i];
            if (e <= 0f) continue;
            // No water on snowy / stony high ground.
            float temp = climate != null ? climate[i] : 10f;
            if (temp < p.minTempC || e > p.maxElevM) continue;

            if (lake[i]) { out[i] = LAKE; continue; }

            float d = q[i];
            float streamQ = e > 1500f ? p.streamQ * 0.6f : p.streamQ;   // mountain streams are small but common
            byte cls = d >= p.mainQ ? MAIN : d >= p.riverQ ? RIVER : d >= streamQ ? STREAM : NONE;
            if (cls == NONE) continue;
            // Altitude caps: big rivers only in lowlands, small streams in the mountains.
            if (e > 1500f && cls > STREAM) cls = STREAM;
            else if (e > 700f && cls > RIVER) cls = RIVER;
            out[i] = cls;
        }
        // A channel never just stops: once a pixel is a stream or river it stays one (at least) all the
        // way down to the sea or a lake, even where the windowed flow count dips below the threshold.
        for (int s = 0; s < n; s++) {
            byte c = out[s];
            if (c < STREAM || c > MAIN) continue;
            int i = s;
            for (int steps = 0; steps < 400; steps++) {
                int j = net.downstream[i];
                if (j < 0 || elev[j] <= 0f || lake[j]) break;
                float tj = climate != null ? climate[j] : 10f;
                if (tj < p.minTempC || elev[j] > p.maxElevM) break;
                byte cj = c;
                if (elev[j] > 1500f) cj = (byte) Math.min(cj, STREAM);
                else if (elev[j] > 700f) cj = (byte) Math.min(cj, RIVER);
                if (out[j] >= cj) break;
                out[j] = cj;
                i = j;
            }
        }
        // A lake's overflow always reaches the stream it feeds: the path from the lake's outlet down to that stream is a
        // stream too, even where the windowed flow count is still below the threshold (it left a dry gap between lake and
        // stream). Only paths that reach an existing stream within 40 steps are filled in, so no new streams appear.
        int[] path = new int[40];
        for (int s = 0; s < n; s++) {
            if (!lake[s]) continue;
            int j = net.downstream[s];
            if (j < 0 || lake[j] || out[j] >= STREAM) continue;
            int len = 0;
            boolean reached = false;
            int i = j;
            while (i >= 0 && len < path.length) {
                if (elev[i] <= 0f || lake[i]) break;
                if (out[i] >= STREAM && out[i] <= MAIN) { reached = true; break; }
                float ti = climate != null ? climate[i] : 10f;
                if (ti < p.minTempC || elev[i] > p.maxElevM) break;
                path[len++] = i;
                i = net.downstream[i];
            }
            if (!reached) continue;
            for (int k = 0; k < len; k++) if (out[path[k]] == NONE) out[path[k]] = STREAM;
        }
        joinSourcesToLakes(out, lake, net, elev, climate, p, H, W);
        Result result = new Result(out, net.downstream, net.filled, elev);
        result.size = channelSize(out, q, elev, net.downstream, p);
        return result;
    }

    /** Metres a lake stays under the lowest point of its rim at block resolution. */
    private static final float BLOCK_RIM_FREEBOARD = 1f;

    /**
     * Lowers each lake to what its rim holds at block resolution. A lake is filled to its basin's rim at native
     * resolution, but the world's ground is the native grid upsampled (Catmull-Rom, as the tiles do it), and a rim one
     * pixel wide between a deep lake and lower ground outside sags tens of metres between the pixel centres. The water
     * then stood above the ground beside it, and the carver's natural dams built it up into stairs to the water level
     * (Calvin, Nov 1, X 65035 Z 169251, scale 6: water 9 blocks over the ground at the shore). Per lake: the upsampled
     * ground over the lake and the pixels next to it, flooded from the outside of that band (priority flood); the
     * lowest level the band holds over any lake block below the old level is the new level (less
     * {@link #BLOCK_RIM_FREEBOARD}). A lake left with nothing under it is dropped. The block ground only gets higher
     * than this near lakes (the tile's noise there only raises it), so the water never stands over its shore.
     */
    private static void fitLakesToBlocks(float[] elev, boolean[] lake, float[] filled, int H, int W, int s,
                                         int[] stack, int[] members) {
        int n = H * W;
        boolean[] seen = new boolean[n];
        for (int s0 = 0; s0 < n; s0++) {
            if (!lake[s0] || seen[s0]) continue;
            int sp = 0, count = 0;
            stack[sp++] = s0;
            seen[s0] = true;
            int r0 = H, r1 = -1, c0 = W, c1 = -1;
            while (sp > 0) {
                int i = stack[--sp];
                members[count++] = i;
                int r = i / W, c = i % W;
                r0 = Math.min(r0, r); r1 = Math.max(r1, r); c0 = Math.min(c0, c); c1 = Math.max(c1, c);
                if (r > 0 && lake[i - W] && !seen[i - W]) { seen[i - W] = true; stack[sp++] = i - W; }
                if (r < H - 1 && lake[i + W] && !seen[i + W]) { seen[i + W] = true; stack[sp++] = i + W; }
                if (c > 0 && lake[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1; }
                if (c < W - 1 && lake[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1; }
            }
            float level = Float.NEGATIVE_INFINITY;
            for (int k = 0; k < count; k++) level = Math.max(level, filled[members[k]]);
            // The band: the lake's pixels and those next to it (8-neighbours), inside a box with room for the
            // interpolation (2 pixels) round it.
            int br0 = r0 - 1, br1 = r1 + 1, bc0 = c0 - 1, bc1 = c1 + 1;
            int pr0 = Math.max(0, br0 - 2), pr1 = Math.min(H - 1, br1 + 2), pc0 = Math.max(0, bc0 - 2), pc1 = Math.min(W - 1, bc1 + 2);
            int ph = pr1 - pr0 + 1, pw = pc1 - pc0 + 1;
            byte[] band = new byte[ph * pw];   // 2 lake, 1 next to it, 0 outside
            for (int k = 0; k < count; k++) {
                int i = members[k], r = i / W - pr0, c = i % W - pc0;
                band[r * pw + c] = 2;
            }
            for (int k = 0; k < count; k++) {
                int i = members[k], r = i / W - pr0, c = i % W - pc0;
                for (int dr = -1; dr <= 1; dr++)
                    for (int dc = -1; dc <= 1; dc++) {
                        int rr = r + dr, cc = c + dc;
                        if (rr < 0 || cc < 0 || rr >= ph || cc >= pw) continue;
                        if (band[rr * pw + cc] == 0) band[rr * pw + cc] = 1;
                    }
            }
            // The upsampled ground over the box: the same Catmull-Rom resize as the tiles (translation-consistent, so a
            // crop gives the same values away from its edge).
            float[][] crop = new float[ph][pw];
            for (int r = 0; r < ph; r++)
                for (int c = 0; c < pw; c++) crop[r][c] = elev[(pr0 + r) * W + pc0 + c];
            float[][] up = com.github.xandergos.terraindiffusionmc.pipeline.LaplacianUtils.bicubicResize(crop, ph * s, pw * s);
            int bh = ph * s, bw = pw * s, bn = bh * bw;
            // Priority flood over the band's blocks from its outside.
            float[] fill = new float[bn];
            java.util.Arrays.fill(fill, Float.NaN);
            java.util.PriorityQueue<long[]> heap = new java.util.PriorityQueue<>((x, y) -> Float.compare(Float.intBitsToFloat((int) x[0]), Float.intBitsToFloat((int) y[0])));
            for (int br = 0; br < bh; br++)
                for (int bc = 0; bc < bw; bc++) {
                    if (band[(br / s) * pw + bc / s] == 0) continue;
                    boolean edge = false;
                    for (int d = 0; d < 4 && !edge; d++) {
                        int rr = br + (d == 0 ? -1 : d == 1 ? 1 : 0), cc = bc + (d == 2 ? -1 : d == 3 ? 1 : 0);
                        edge = rr < 0 || cc < 0 || rr >= bh || cc >= bw || band[(rr / s) * pw + cc / s] == 0;
                    }
                    if (!edge) continue;
                    int b = br * bw + bc;
                    fill[b] = up[br][bc];
                    heap.add(new long[]{Float.floatToIntBits(fill[b]), b});
                }
            float hold = Float.POSITIVE_INFINITY;
            while (!heap.isEmpty()) {
                long[] top = heap.poll();
                int b = (int) top[1];
                float f = Float.intBitsToFloat((int) top[0]);
                if (f > fill[b]) continue;
                int br = b / bw, bc = b % bw;
                if (band[(br / s) * pw + bc / s] == 2 && up[br][bc] < level) hold = Math.min(hold, f);
                for (int d = 0; d < 4; d++) {
                    int rr = br + (d == 0 ? -1 : d == 1 ? 1 : 0), cc = bc + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    if (rr < 0 || cc < 0 || rr >= bh || cc >= bw || band[(rr / s) * pw + cc / s] == 0) continue;
                    int q = rr * bw + cc;
                    float fq = Math.max(up[rr][cc], f);
                    if (!Float.isNaN(fill[q]) && fill[q] <= fq) continue;
                    fill[q] = fq;
                    heap.add(new long[]{Float.floatToIntBits(fq), q});
                }
            }
            if (hold == Float.POSITIVE_INFINITY || hold - BLOCK_RIM_FREEBOARD >= level) continue;
            float lvl = hold - BLOCK_RIM_FREEBOARD;
            float minElev = Float.POSITIVE_INFINITY;
            for (int k = 0; k < count; k++) minElev = Math.min(minElev, elev[members[k]]);
            for (int k = 0; k < count; k++) {
                if (lvl <= minElev) lake[members[k]] = false;   // nothing left to hold
                else filled[members[k]] = lvl;
            }
        }
    }

    /** How far (native pixels) a lake reaches for a stream beside it, and how far above the lake the ground between may rise (metres). */
    private static final int JOIN_REACH = 6;
    private static final float JOIN_RISE_M = 3f;
    /** How far along a channel touching the lake (8-neighbour steps) counts as already joined. */
    private static final int CHANNEL_REACH = 3 * (JOIN_REACH + 1);
    private static final int[] JOIN_DR = {0, 0, -1, 1, -1, -1, 1, 1}, JOIN_DC = {-1, 1, 0, 0, -1, 1, -1, 1};

    /**
     * A lake with a stream running past just below it overflows into that stream (Oct 28, Calvin's X 1530 Z -4425: a
     * stream a block below a lake's water bent to within 14 blocks of its shore, a strip of land at lake level between
     * them). The lake's own outlet is elsewhere on its rim, so neither the outlet path nor "a channel never stops"
     * joined them. For each lake (connected lake pixels, one level), the nearest channel pixel within
     * {@link #JOIN_REACH} pixels of its shore, at or below the lake's level and reached over ground no more than
     * {@link #JOIN_RISE_M} above it, is found by a breadth-first search out from the shore (straight steps before
     * diagonal ones); the pixels between become stream. One join per lake.
     */
    private static void joinSourcesToLakes(byte[] out, boolean[] lake, DrainageNetwork net, float[] elev, float[] climate,
                                           Params p, int H, int W) {
        int n = out.length;
        boolean[] done = new boolean[n];
        int[] comp = new int[n];
        int[] dist = new int[n];
        int[] parent = new int[n];
        java.util.Arrays.fill(dist, -1);
        int[] queue = new int[n];
        int[] touched = new int[n];
        int[] chan = new int[n], ctouched = new int[n];
        java.util.Arrays.fill(chan, -1);
        int nc = 0;
        for (int s = 0; s < n; s++) {
            if (!lake[s] || done[s]) continue;
            // The lake's pixels.
            int cn = 0;
            comp[cn++] = s;
            done[s] = true;
            for (int k = 0; k < cn; k++) {
                int i = comp[k], r = i / W, c = i % W;
                for (int d = 0; d < 4; d++) {
                    int rr = r + JOIN_DR[d], cc = c + JOIN_DC[d];
                    if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                    int j = rr * W + cc;
                    if (lake[j] && !done[j]) { done[j] = true; comp[cn++] = j; }
                }
            }
            float level = net.filled[s];
            for (int k = 0; k < cn; k++) level = Math.min(level, net.filled[comp[k]]);
            // Channels already touching the lake, and how far along them from the shore (8-neighbour steps): a
            // channel pixel that is already a short way from the lake by its own channel (the lake's outlet or an
            // inflow running away from the shore) is not joined again, only one much further round by channel.
            int head = 0, tail = 0, nt = 0, found = -1;
            for (int k = 0; k < cn; k++) {
                int i = comp[k], r = i / W, c = i % W;
                for (int d = 0; d < 8; d++) {
                    int rr = r + JOIN_DR[d], cc = c + JOIN_DC[d];
                    if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                    int j = rr * W + cc;
                    if (lake[j] || out[j] < STREAM || out[j] > MAIN || chan[j] >= 0) continue;
                    chan[j] = 1;
                    ctouched[nc++] = j;
                    queue[tail++] = j;
                }
            }
            while (head < tail) {
                int i = queue[head++];
                if (chan[i] >= CHANNEL_REACH) continue;
                int r = i / W, c = i % W;
                for (int d = 0; d < 8; d++) {
                    int rr = r + JOIN_DR[d], cc = c + JOIN_DC[d];
                    if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                    int j = rr * W + cc;
                    if (lake[j] || out[j] < STREAM || out[j] > MAIN || chan[j] >= 0) continue;
                    chan[j] = chan[i] + 1;
                    ctouched[nc++] = j;
                    queue[tail++] = j;
                }
            }
            // Breadth-first out from the shore.
            head = 0;
            tail = 0;
            for (int k = 0; k < cn; k++) {
                int i = comp[k];
                dist[i] = 0;
                parent[i] = -1;
                touched[nt++] = i;
                queue[tail++] = i;
            }
            while (head < tail && found < 0) {
                int i = queue[head++];
                if (dist[i] >= JOIN_REACH) continue;
                int r = i / W, c = i % W;
                for (int d = 0; d < 8 && found < 0; d++) {
                    int rr = r + JOIN_DR[d], cc = c + JOIN_DC[d];
                    if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                    int j = rr * W + cc;
                    if (dist[j] >= 0 || lake[j]) continue;
                    if (elev[j] <= 0f) continue;
                    byte cj = out[j];
                    if (cj >= STREAM && cj <= MAIN) {
                        int byChannel = chan[j] < 0 ? Integer.MAX_VALUE : chan[j];
                        if (elev[j] <= level + 1f && dist[i] > 0 && byChannel > 3 * (dist[i] + 1)) {
                            parent[j] = i;
                            dist[j] = dist[i] + 1;
                            touched[nt++] = j;
                            found = j;
                        }
                        continue;   // never walk along or through a channel: the join is to its nearest point
                    }
                    if (elev[j] > level + JOIN_RISE_M) continue;
                    float tj = climate != null ? climate[j] : 10f;
                    if (tj < p.minTempC || elev[j] > p.maxElevM) continue;
                    dist[j] = dist[i] + 1;
                    parent[j] = i;
                    touched[nt++] = j;
                    queue[tail++] = j;
                }
            }
            if (found >= 0) {
                for (int k = parent[found]; k >= 0 && !lake[k]; k = parent[k]) if (out[k] == NONE) out[k] = STREAM;
            }
            for (int k = 0; k < nt; k++) dist[touched[k]] = -1;
            for (int k = 0; k < nc; k++) chan[ctouched[k]] = -1;
            nc = 0;
        }
    }

    /** Elevations (metres) over which the size cap eases from a main river's down to a river's (rivers stop at 1500 m). */
    private static final float SIZE_CAP_FROM_M = 1350f, SIZE_CAP_TO_M = 1500f;

    /** See {@link Result#size}. */
    static float[] channelSize(byte[] cls, float[] q, float[] elev, int[] downstream, Params p) {
        int n = cls.length;
        float[] size = new float[n];
        float lnSR = (float) Math.log(p.riverQ / p.streamQ), lnRM = (float) Math.log(p.mainQ / p.riverQ);
        for (int i = 0; i < n; i++) {
            byte c = cls[i];
            if (c < STREAM || c > MAIN) continue;
            float d = q[i], s;
            if (d <= p.streamQ) s = 1f;
            else if (d < p.riverQ) s = 1f + (float) Math.log(d / p.streamQ) / lnSR;
            else if (d < p.mainQ) s = 2f + (float) Math.log(d / p.riverQ) / lnRM;
            else s = 3f;
            size[i] = fitSize(s, c, elev[i]);
        }
        // Never smaller downstream (the windowed discharge can dip), except where the altitude caps it.
        for (int s0 = 0; s0 < n; s0++) {
            if (size[s0] <= 0f) continue;
            int i = s0;
            for (int steps = 0; steps < 400; steps++) {
                int j = downstream[i];
                if (j < 0 || cls[j] < STREAM || cls[j] > MAIN) break;
                float v = fitSize(size[i], cls[j], elev[j]);
                if (size[j] >= v) break;
                size[j] = v;
                i = j;
            }
        }
        return size;
    }

    /** A size kept inside its class's band and under the altitude cap (big rivers only in the lowlands). */
    private static float fitSize(float s, byte cls, float e) {
        float cap = e <= SIZE_CAP_FROM_M ? 3f : e >= SIZE_CAP_TO_M ? 1.999f
                : 3f - (e - SIZE_CAP_FROM_M) / (SIZE_CAP_TO_M - SIZE_CAP_FROM_M);
        s = Math.min(s, cap);
        return cls >= MAIN ? Math.max(3f, s) : Math.max(cls, Math.min(cls + 0.999f, s));
    }

    /** Collects the connected depression cells (filled ones, on land) around s into members; returns how many. */
    private static int collectDepression(DrainageNetwork net, float[] elev, int s, int H, int W,
                                         boolean[] seen, int[] stack, int[] members) {
        int sp = 0, count = 0;
        stack[sp++] = s; seen[s] = true;
        while (sp > 0) {
            int i = stack[--sp];
            members[count++] = i;
            int r = i / W, c = i % W;
            if (r > 0)     { int j = i - W; if (!seen[j] && net.inDepression[j] && elev[j] > 0f) { seen[j] = true; stack[sp++] = j; } }
            if (r < H - 1) { int j = i + W; if (!seen[j] && net.inDepression[j] && elev[j] > 0f) { seen[j] = true; stack[sp++] = j; } }
            if (c > 0)     { int j = i - 1; if (!seen[j] && net.inDepression[j] && elev[j] > 0f) { seen[j] = true; stack[sp++] = j; } }
            if (c < W - 1) { int j = i + 1; if (!seen[j] && net.inDepression[j] && elev[j] > 0f) { seen[j] = true; stack[sp++] = j; } }
        }
        return count;
    }

    /**
     * The lowest {@code area} or so pixels connected to the sink (taken lowest first), plus any neighbour still below the
     * highest of them, so the ground around the lake is at or above its level everywhere. Pixels already in a lake are
     * left out. Returns how many were put into members.
     */
    /** Metres a drained basin's lake stays under the basin's rim. */
    private static final float BASIN_FREEBOARD = 3f;

    private static int basinLake(float[] routing, float[] elev, int sink, int area, float maxLevel, int H, int W,
                                 boolean[] lake, int[] members) {
        java.util.PriorityQueue<long[]> heap = new java.util.PriorityQueue<>((x, y) -> Float.compare(Float.intBitsToFloat((int) x[0]), Float.intBitsToFloat((int) y[0])));
        java.util.HashSet<Integer> queued = new java.util.HashSet<>();
        int count = 0;
        float level = Float.NEGATIVE_INFINITY;
        heap.add(new long[]{Float.floatToIntBits(routing[sink]), sink});
        queued.add(sink);
        while (!heap.isEmpty()) {
            long[] top = heap.peek();
            float e = Float.intBitsToFloat((int) top[0]);
            if (count >= area && e >= level) break;   // enough pixels, and nothing left below the level
            if (count > 0 && e > maxLevel) break;     // the rim: the lake never spills over it
            heap.poll();
            int i = (int) top[1];
            members[count++] = i;
            level = Math.max(level, e);
            int r = i / W, c = i % W;
            int[] nb = { r > 0 ? i - W : -1, r < H - 1 ? i + W : -1, c > 0 ? i - 1 : -1, c < W - 1 ? i + 1 : -1 };
            for (int j : nb) {
                if (j < 0 || queued.contains(j) || lake[j] || elev[j] <= 0f) continue;
                queued.add(j);
                heap.add(new long[]{Float.floatToIntBits(routing[j]), j});
            }
            if (count >= area * 4) break;   // a flat floor: stop growing
        }
        return count;
    }
}
