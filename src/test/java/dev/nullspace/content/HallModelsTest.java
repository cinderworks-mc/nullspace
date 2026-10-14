package dev.nullspace.content;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class HallModelsTest {
    private static final String[] MODELS = {"destination_pad", "destination_pad_idle", "lift_pad_down", "lift_pad_up", "waypoint_pedestal", "waypoint_dais", "lamp_pillar", "archive_exit", "archive_exit_upper"};

    record El(double[] box, boolean rotated, Map<String, double[]> faces) {}
    record Model(int size, List<El> elements) {}

    private static final Pattern ELEMENT = Pattern.compile("\\{\"name\": \"[^\"]*\", \"from\": \\[([^\\]]*)\\], \"to\": \\[([^\\]]*)\\](, \"rotation\")?[^\\n]*\\n(.*?)\\n      \\}\\}", Pattern.DOTALL);
    private static final Pattern FACE = Pattern.compile("\"(north|east|south|west|up|down)\": \\{\"uv\": \\[([^\\]]*)\\]");

    private static double[] nums(String csv) { return Arrays.stream(csv.split(",")).mapToDouble(x -> Double.parseDouble(x.trim())).toArray(); }

    private static Model model(String name) throws Exception {
        String text;
        try (var in = HallModelsTest.class.getResourceAsStream("/assets/nullspace/models/block/" + name + ".json")) {
            assertNotNull(in, name);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher sz = Pattern.compile("\"texture_size\": \\[(\\d+), (\\d+)\\]").matcher(text);
        assertTrue(sz.find(), name);
        assertTrue(text.contains("\"0\": \"nullspace:block/" + name + "_model\""), name + " uses its own atlas");
        List<El> els = new ArrayList<>();
        Matcher m = ELEMENT.matcher(text);
        while (m.find()) {
            double[] f = nums(m.group(1)), t = nums(m.group(2));
            Map<String, double[]> faces = new LinkedHashMap<>();
            Matcher fm = FACE.matcher(m.group(4));
            while (fm.find()) faces.put(fm.group(1), nums(fm.group(2)));
            els.add(new El(new double[] {f[0], f[1], f[2], t[0], t[1], t[2]}, m.group(3) != null, faces));
        }
        assertEquals(text.split("\"name\"", -1).length - 1, els.size(), name + ": every element must parse");
        return new Model(Integer.parseInt(sz.group(1)), els);
    }

    private static BufferedImage atlas(String name) throws Exception {
        try (var in = HallModelsTest.class.getResourceAsStream("/assets/nullspace/textures/block/" + name + "_model.png")) {
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    private static double top(String name) throws Exception {
        double top = 0;
        for (El e : model(name).elements()) top = Math.max(top, e.box()[4]);
        return top;
    }

    @Test void everyModelIsWholeUnitsUnstretchedAndFullyPainted() throws Exception {
        for (String name : MODELS) {
            Model m = model(name);
            int s = m.size();
            BufferedImage img = atlas(name);
            assertEquals(s, img.getWidth(), name);
            assertEquals(s, img.getHeight(), name);
            assertFalse(m.elements().isEmpty(), name);
            Set<Integer> sampled = new HashSet<>();
            for (El el : m.elements()) {
                assertFalse(el.rotated(), name + " has a rotated element: its shape could not match");
                double[] b = el.box();
                for (double v : b) assertEquals(Math.rint(v), v, 1e-9, name + " box not whole units");
                assertTrue(b[0] >= 0 && b[1] >= 0 && b[2] >= 0 && b[3] <= 16 && b[5] <= 16, name + " sticks out sideways");
                for (var face : el.faces().entrySet()) {
                    double[] uv = face.getValue();
                    double u0 = uv[0] * s / 16, v0 = uv[1] * s / 16, u1 = uv[2] * s / 16, v1 = uv[3] * s / 16;
                    double dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
                    double w, h;
                    switch (face.getKey()) {
                        case "north", "south" -> { w = dx; h = dy; }
                        case "east", "west" -> { w = dz; h = dy; }
                        default -> { w = dx; h = dz; }
                    }
                    assertEquals(w, Math.abs(u1 - u0), 1e-3, name + " " + face.getKey() + " stretched");
                    assertEquals(h, Math.abs(v1 - v0), 1e-3, name + " " + face.getKey() + " stretched");
                    for (int y = (int) Math.round(Math.min(v0, v1)); y < Math.round(Math.max(v0, v1)); y++)
                        for (int x = (int) Math.round(Math.min(u0, u1)); x < Math.round(Math.max(u0, u1)); x++) {
                            assertEquals(255, img.getRGB(x, y) >>> 24, name + " transparent texel " + x + "," + y);
                            assertTrue(sampled.add(y * s + x), name + " texel shared by two faces " + x + "," + y);
                        }
                }
            }
            for (int y = 0; y < s; y++) for (int x = 0; x < s; x++)
                if ((img.getRGB(x, y) >>> 24) != 0) assertTrue(sampled.contains(y * s + x), name + " painted texel nobody samples " + x + "," + y);
        }
    }

    private static boolean[][][] voxels(List<double[]> boxes) {
        boolean[][][] v = new boolean[16][32][16];
        for (double[] b : boxes) for (int x = (int) b[0]; x < b[3]; x++) for (int y = (int) b[1]; y < b[4]; y++) for (int z = (int) b[2]; z < b[5]; z++) v[x][y][z] = true;
        return v;
    }

    @Test void destinationPadShapeIsTheModel() throws Exception {
        for (String name : new String[] {"destination_pad", "destination_pad_idle"}) {
            List<double[]> boxes = new ArrayList<>();
            for (El e : model(name).elements()) boxes.add(e.box());
            assertTrue(Arrays.deepEquals(voxels(boxes), voxels(Arrays.asList(HallGeometry.PAD_PARTS))), name + ": model and shape differ");
        }
        // bound and unbound are the same machine
        assertEquals(model("destination_pad").elements().size(), model("destination_pad_idle").elements().size());
        // the walkable deck (where the Tempad door stands) is 3 units high: DestinationPadBlock.HEIGHT = 3 / 16
        assertEquals(3, HallGeometry.PAD_PARTS[1][4]);
    }

    private static List<double[]> boxes(String name) throws Exception {
        List<double[]> boxes = new ArrayList<>();
        for (El e : model(name).elements()) boxes.add(e.box());
        return boxes;
    }

    @Test void exitShapeIsTheModel() throws Exception {
        assertTrue(Arrays.deepEquals(voxels(boxes("archive_exit")), voxels(Arrays.asList(HallGeometry.EXIT_LOWER_PARTS))), "archive_exit: model and shape differ");
        assertTrue(Arrays.deepEquals(voxels(boxes("archive_exit_upper")), voxels(Arrays.asList(HallGeometry.EXIT_UPPER_PARTS))), "archive_exit_upper: model and shape differ");
    }

    @Test void exitHalvesMeetAtTheCellEdge() throws Exception {
        // the lower half runs to the top of its cell and the upper one starts at the floor of its cell, with the same jambs and leaf columns,
        // so the two read as one 2 block door. the lintel keystone is the only part that leaves the frame (2 proud, as the threshold lip does)
        boolean[][][] lower = voxels(boxes("archive_exit")), upper = voxels(boxes("archive_exit_upper"));
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            boolean frameOrLeaf = (x < 2 || x >= 14) ? z >= 10 : z >= 14;
            assertEquals(frameOrLeaf, lower[x][15][z], "lower top layer at " + x + "," + z);
            if (z >= 10 && (x < 2 || x >= 14) || z >= 14 && x >= 2 && x < 14) assertTrue(upper[x][0][z], "upper bottom layer at " + x + "," + z);
        }
        assertEquals(16, top("archive_exit"));
        assertEquals(16, top("archive_exit_upper"));
        // the opening is 28 units tall (leaf 14 + 10, then two arch steps of 2), a player is 28.8
        assertEquals(14, HallGeometry.EXIT_LOWER_PARTS[3][4] - HallGeometry.EXIT_LOWER_PARTS[3][1]);
        assertEquals(10, HallGeometry.EXIT_UPPER_PARTS[7][4] - HallGeometry.EXIT_UPPER_PARTS[7][1]);
        // plus two arch steps of 2, so 14 + 10 + 2 + 2
    }

    @Test void rimmedBlocksStandOnTheirFullCube() throws Exception {
        // the cell top stays a full plane, so walking and the lift's landing at the cell top are as before
        assertTrue(Arrays.deepEquals(voxels(boxes("lift_pad_down")), voxels(Arrays.asList(HallGeometry.LIFT_DOWN_PARTS))), "lift_pad_down: model and shape differ");
        assertTrue(Arrays.deepEquals(voxels(boxes("waypoint_pedestal")), voxels(Arrays.asList(HallGeometry.PEDESTAL_PARTS))), "waypoint_pedestal: model and shape differ");
        assertEquals(19, top("lift_pad_down"));
        assertEquals(20, top("waypoint_pedestal"));
    }

    @Test void flatBlocksStayFlushWithTheirCellAndTheDaisTierMatchesItsShape() throws Exception {
        // full-cube collision: walking and the lift's exact landing (y = the cell's top) stay as they were
        for (String name : new String[] {"lift_pad_up", "lamp_pillar"}) assertEquals(16, top(name), name);
        assertEquals(HallGeometry.DAIS_TOP, top("waypoint_dais"), "dais tier height equals its collision shape");
        for (El e : model("lamp_pillar").elements()) {
            double[] b = e.box();
            assertTrue(b[0] >= 3 && b[2] >= 3 && b[3] <= 13 && b[5] <= 13, "the pillar stays inside its 3..13 shape");
        }
    }
}
