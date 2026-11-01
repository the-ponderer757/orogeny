package com.github.xandergos.terraindiffusionmc.hydrology;

/**
 * Drainage network on a regular grid: depression filling (priority-flood), flow directions (Rho8 on sloping ground,
 * see {@link #route}) and runoff-weighted flow accumulation.
 *
 * <p>Pure Java with no Minecraft dependencies so it can be unit-tested on synthetic terrain.
 *
 * <p>Outlets: every cell at or below {@code seaLevel} (the ocean), plus, if requested, every cell
 * on the grid border. Callers compute on a window with a margin and only use the central part, so
 * that border effects do not matter.
 */
public final class DrainageNetwork {
    /** Cell offsets of the 8 neighbours. */
    private static final int[] DR = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1, -1, 1, -1, 0, 1};

    public final int height;
    public final int width;
    /** Downstream cell index for every cell, or -1 for outlets (ocean cells and window-edge exits). */
    public final int[] downstream;
    /** Runoff accumulated upstream of (and including) every cell. */
    public final float[] discharge;
    /** Elevation after depression filling (lakes become flat plus a tiny random slope). */
    public final float[] filled;
    /** True for cells that were raised by depression filling (lake / flat areas). */
    public final boolean[] inDepression;

    private DrainageNetwork(int h, int w, int[] downstream, float[] discharge, float[] filled, boolean[] dep) {
        this.height = h;
        this.width = w;
        this.downstream = downstream;
        this.discharge = discharge;
        this.filled = filled;
        this.inDepression = dep;
    }

    /** Heap key: float ordering in the high 32 bits, cell index in the low 32 bits. */
    private static long key(float v, int idx) {
        int b = Float.floatToIntBits(v);
        if (b < 0) b ^= 0x7FFFFFFF;
        return ((long) b << 32) | (idx & 0xFFFFFFFFL);
    }

    /**
     * @param elev        elevation in metres, row-major {@code height*width}
     * @param runoff      runoff produced by each cell (for example precipitation times cell area)
     * @param seaLevel    cells with elevation at or below this value are ocean outlets
     * @param edgesAreOutlets if true, water reaching the window border leaves the grid
     */
    public static DrainageNetwork compute(float[] elev, float[] runoff, int height, int width,
                                          float seaLevel, boolean edgesAreOutlets) {
        return compute(elev, runoff, height, width, seaLevel, edgesAreOutlets, null);
    }

    /**
     * As {@link #compute(float[], float[], int, int, float, boolean)}, with extra outlets: cells where {@code sinks} is
     * true drain nowhere (the bottom of a closed basin), so the land around them drains into them instead of being
     * filled up to the basin's rim.
     */
    public static DrainageNetwork compute(float[] elev, float[] runoff, int height, int width,
                                          float seaLevel, boolean edgesAreOutlets, boolean[] sinks) {
        return compute(elev, runoff, height, width, seaLevel, edgesAreOutlets, sinks, Integer.MIN_VALUE, 0);
    }

    /**
     * As {@link #compute(float[], float[], int, int, float, boolean, boolean[])}, with the window's first row and column
     * in world pixels (i0, j0): flow directions on sloping ground are then chosen by {@link #route} from the world
     * position, so overlapping windows agree. With i0 = Integer.MIN_VALUE every cell drains to its lowest neighbour.
     */
    public static DrainageNetwork compute(float[] elev, float[] runoff, int height, int width,
                                          float seaLevel, boolean edgesAreOutlets, boolean[] sinks, int i0, int j0) {
        final int n = height * width;
        float[] filled = new float[n];
        int[] downstream = new int[n];
        boolean[] closed = new boolean[n];
        boolean[] dep = new boolean[n];
        java.util.Arrays.fill(downstream, -1);

        long[] heap = new long[n];
        int size = 0;
        int[] order = new int[n];
        int popped = 0;

        for (int i = 0; i < n; i++) {
            int r = i / width, c = i % width;
            boolean ocean = elev[i] <= seaLevel;
            boolean edge = edgesAreOutlets && (r == 0 || c == 0 || r == height - 1 || c == width - 1);
            boolean sink = sinks != null && sinks[i];
            if (ocean || edge || sink) {
                closed[i] = true;
                filled[i] = elev[i];
                size = push(heap, size, key(elev[i], i));
            }
        }
        // If nothing is an outlet, seed from the lowest cell so the algorithm still terminates.
        if (size == 0) {
            int best = 0;
            for (int i = 1; i < n; i++) if (elev[i] < elev[best]) best = i;
            closed[best] = true;
            filled[best] = elev[best];
            size = push(heap, size, key(elev[best], best));
        }

        final float eps = 1e-3f;
        while (size > 0) {
            long top = heap[0];
            size = pop(heap, size);
            int i = (int) top;
            order[popped++] = i;
            int r = i / width, c = i % width;
            float fi = filled[i];
            for (int k = 0; k < 8; k++) {
                int rr = r + DR[k], cc = c + DC[k];
                if (rr < 0 || cc < 0 || rr >= height || cc >= width) continue;
                int j = rr * width + cc;
                if (closed[j]) continue;
                closed[j] = true;
                float fj = elev[j];
                if (fj <= fi) {
                    // Randomised increment: flats drain along meandering paths instead of the
                    // straight diagonal lines a constant increment produces.
                    int h = j * 0x9E3779B1; h ^= h >>> 15; h *= 0x85EBCA6B; h ^= h >>> 13;
                    fj = fi + eps * (0.25f + 1.5f * ((h & 0xFFFF) / 65535f));
                    dep[j] = true;
                }
                filled[j] = fj;
                downstream[j] = i;
                size = push(heap, size, key(fj, j));
            }
        }

        if (i0 != Integer.MIN_VALUE) route(filled, dep, downstream, order, popped, height, width, i0, j0);

        // Cells were popped in non-decreasing filled order and every cell is popped after its
        // downstream cell, so walking the pop order backwards visits all upstream cells first.
        float[] discharge = new float[n];
        for (int i = 0; i < n; i++) discharge[i] = Math.max(0f, runoff[i]);
        for (int t = popped - 1; t >= 0; t--) {
            int i = order[t];
            int d = downstream[i];
            if (d >= 0) discharge[d] += discharge[i];
        }
        return new DrainageNetwork(height, width, downstream, discharge, filled, dep);
    }

    private static final float DIAG = (float) Math.sqrt(2);
    /** The 8 facets: cardinal neighbour (dr, dc), then the diagonal neighbour beside it. */
    private static final int[][] FACETS = {
            {0, 1, -1, 1}, {0, 1, 1, 1}, {1, 0, 1, 1}, {1, 0, 1, -1},
            {0, -1, 1, -1}, {0, -1, -1, -1}, {-1, 0, -1, -1}, {-1, 0, -1, 1}};

    /**
     * Flow directions on sloping ground by Rho8 (Fairfield and Leymarie 1991) over Tarboton's facets. The flood's own
     * parent is the lowest neighbour, which on a plane is a diagonal for any slope that isn't exactly along a grid axis
     * (a diagonal drops by up to sqrt 2 times more): every stream on a long even slope ran dead straight at 45 degrees,
     * side by side without meeting (Calvin, Nov 1, a dry coastal range). Here the true downhill direction is taken
     * from the steepest of the eight triangular facets round the cell, between its cardinal and diagonal neighbour,
     * and the cell drains to the diagonal with probability (angle from the cardinal) / 45 degrees, from a hash of the
     * world position: on average streams follow the real slope, wandering and meeting as on real hillsides. Only
     * neighbours popped before the cell are taken (so the order stays valid for accumulation and no loops form);
     * flats and filled depressions keep the flood's paths.
     */
    private static void route(float[] filled, boolean[] dep, int[] downstream, int[] order, int popped, int H, int W,
                              int i0, int j0) {
        int n = H * W;
        int[] pos = new int[n];
        java.util.Arrays.fill(pos, Integer.MAX_VALUE);
        for (int t = 0; t < popped; t++) pos[order[t]] = t;
        for (int i = 0; i < n; i++) {
            if (downstream[i] < 0 || dep[i]) continue;
            int r = i / W, c = i % W;
            float e0 = filled[i];
            float best = 0f, angle = 0f;
            int bestF = -1;
            for (int f = 0; f < 8; f++) {
                int r1 = r + FACETS[f][0], c1 = c + FACETS[f][1], r2 = r + FACETS[f][2], c2 = c + FACETS[f][3];
                if (r1 < 0 || r1 >= H || c1 < 0 || c1 >= W || r2 < 0 || r2 >= H || c2 < 0 || c2 >= W) continue;
                float e1 = filled[r1 * W + c1], e2 = filled[r2 * W + c2];
                float s1 = e0 - e1, s2 = e1 - e2;
                float a = (float) Math.atan2(s2, s1), sl;
                if (a <= 0f) { a = 0f; sl = s1; }
                else if (a >= (float) (Math.PI / 4)) { a = (float) (Math.PI / 4); sl = (e0 - e2) / DIAG; }
                else sl = (float) Math.sqrt(s1 * s1 + s2 * s2);
                if (sl > best) { best = sl; angle = a; bestF = f; }
            }
            if (bestF < 0) continue;
            int x = j0 + c, z = i0 + r;
            int h = x * 0x9E3779B1 ^ z * 0x85EBCA6B;
            h ^= h >>> 15; h *= 0x2C1B3C6D; h ^= h >>> 12; h *= 0x297A2D39; h ^= h >>> 15;
            boolean diag = (h & 0xFFFF) / 65536f < angle / (float) (Math.PI / 4);
            int[] fc = FACETS[bestF];
            int a1 = (r + fc[0]) * W + c + fc[1], a2 = (r + fc[2]) * W + c + fc[3];
            int pick = diag ? a2 : a1, other = diag ? a1 : a2;
            if (pos[pick] < pos[i] && filled[pick] < e0) downstream[i] = pick;
            else if (pos[other] < pos[i] && filled[other] < e0) downstream[i] = other;
            // else: keep the flood's parent
        }
    }

    /**
     * Accumulation restricted to upstream cells that reach a cell within {@code steps} flow steps.
     *
     * <p>Unlike {@link #discharge} this does not depend on how big the computed window is, as long
     * as the window extends at least {@code steps} cells beyond the area of interest. That is what
     * keeps rivers continuous across independently computed regions.
     */
    public float[] limitedDischarge(float[] runoff, int steps) {
        final int n = height * width;
        float[] base = new float[n];
        for (int i = 0; i < n; i++) base[i] = Math.max(0f, runoff[i]);
        float[] cur = base.clone();
        float[] next = new float[n];
        for (int k = 0; k < steps; k++) {
            System.arraycopy(base, 0, next, 0, n);
            for (int i = 0; i < n; i++) {
                int d = downstream[i];
                if (d >= 0) next[d] += cur[i];
            }
            float[] t = cur; cur = next; next = t;
        }
        return cur;
    }

    private static int push(long[] heap, int size, long v) {
        int i = size++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heap[p] <= v) break;
            heap[i] = heap[p];
            i = p;
        }
        heap[i] = v;
        return size;
    }

    private static int pop(long[] heap, int size) {
        size--;
        if (size == 0) return 0;
        long v = heap[size];
        int i = 0;
        while (true) {
            int l = 2 * i + 1;
            if (l >= size) break;
            int rgt = l + 1;
            int m = (rgt < size && heap[rgt] < heap[l]) ? rgt : l;
            if (heap[m] >= v) break;
            heap[i] = heap[m];
            i = m;
        }
        heap[i] = v;
        return size;
    }
}
