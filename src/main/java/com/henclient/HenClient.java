package com.henclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class HenClient implements ClientModInitializer {
    public static final int RED = 0xFFE53935;
    public static final int DARK = 0xFF151515;
    public static final int PANEL = 0xFF222222;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int MUTED = 0xFFAAAAAA;

    private static final Minecraft MC = Minecraft.getInstance();
    private static final Path CONFIG = FabricLoader.getInstance()
            .getConfigDir().resolve("henclient.properties");

    private static final List<Module> MODULES = new ArrayList<>();
    private static final Set<Integer> HELD = new HashSet<>();
    private static final List<String> STORAGE_RESULTS = new ArrayList<>();
    private static final List<String> SPAWNER_RESULTS = new ArrayList<>();
    private static final List<String> SUS_CHUNK_RESULTS = new ArrayList<>();
    private static int scanTicks = 0;

    // Storage ESP
    private static final List<EspBox> STORAGE_ESP = new ArrayList<>();
    private static final int ESP_RANGE = 128;   // blocks
    private static final int ESP_MAX_BOXES = 500;
    private static int espTicks = 0;

    // Freecam
    private static final double FREECAM_SPEED = 0.6; // blocks per tick
    private static RemotePlayer freecam = null;

    private static final class EspBox {
        final BlockPos pos;
        final int color;

        EspBox(BlockPos pos, int color) {
            this.pos = pos;
            this.color = color;
        }
    }

    private static int guiKey = GLFW.GLFW_KEY_RIGHT_SHIFT;

    private static final class Module {
        final String id;
        final String name;
        int key;
        boolean enabled;

        Module(String id, String name, int key, boolean enabled) {
            this.id = id;
            this.name = name;
            this.key = key;
            this.enabled = enabled;
        }
    }

    private static void addModule(
            String id, String name, int key, boolean enabled) {
        MODULES.add(new Module(id, name, key, enabled));
    }

    @Override
    public void onInitializeClient() {
        addModule("coords", "Coordinates", GLFW.GLFW_KEY_C, true);
        addModule("fps", "FPS Counter", GLFW.GLFW_KEY_F, true);
        addModule("health", "Health HUD", GLFW.GLFW_KEY_H, false);
        addModule("sprint", "Sprint Status", GLFW.GLFW_KEY_G, false);
        addModule("armor", "Armor Status", GLFW.GLFW_KEY_J, true);
        addModule("direction", "Direction", GLFW.GLFW_KEY_N, true);
        addModule("biome", "Biome", GLFW.GLFW_KEY_B, false);
        addModule("storage", "Storage Finder", GLFW.GLFW_KEY_V, false);
        addModule("spawners", "Spawner Finder", GLFW.GLFW_KEY_P, false);
        addModule("suschunk", "Sus Chunk Finder", GLFW.GLFW_KEY_U, false);
        addModule("storageesp", "Storage ESP", GLFW.GLFW_KEY_X, false);
        addModule("freecam", "Freecam", GLFW.GLFW_KEY_Z, false);

        loadConfig();
        setEnabled("freecam", false); // never auto-start freecam on launch

        // Freecam: stop the real player from reacting to movement/clicks.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (freecam != null && client.screen == null) {
                KeyMapping.releaseAll();
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) {
                if (freecam != null) {
                    freecam = null;
                    setEnabled("freecam", false);
                }
                return;
            }

            updateFreecam(client);

            if (++espTicks >= 10) {
                espTicks = 0;
                if (enabled("storageesp")) {
                    scanStorageEsp();
                } else {
                    STORAGE_ESP.clear();
                }
            }

            if (++scanTicks >= 20) {
                scanTicks = 0;
                scanNearbyBlocks();
            }

            // Avoid toggling modules while a screen is open.
            if (client.screen != null) {
                HELD.clear();
                return;
            }

            if (pressed(guiKey)) {
                client.setScreen(new HenScreen());
                return;
            }

            for (Module module : MODULES) {
                if (pressed(module.key)) {
                    module.enabled = !module.enabled;
                    saveConfig();
                }
            }
        });

        HudRenderCallback.EVENT.register((graphics, tickCounter) -> {
            if (MC.player == null || MC.level == null) {
                return;
            }

            float partial = tickCounter.getGameTimeDeltaPartialTick(true);
            syncFreecamRotation();

            if (enabled("storageesp")) {
                drawStorageEsp(graphics, partial);
            }

            int x = 8;
            int y = 8;

            graphics.fill(x - 4, y - 4, x + 142, y + 16, DARK);
            graphics.fill(x - 4, y - 4, x - 1, y + 16, RED);
            graphics.drawString(MC.font, "HEN CLIENT", x + 3, y, RED);

            y += 23;

            if (enabled("fps")) {
                drawHud(graphics, "FPS: " + MC.getFps(), x, y);
                y += 13;
            }

            if (enabled("coords")) {
                var p = MC.player;
                String coords = String.format(
                        "XYZ: %d, %d, %d",
                        (int) Math.floor(p.getX()),
                        (int) Math.floor(p.getY()),
                        (int) Math.floor(p.getZ())
                );
                drawHud(graphics, coords, x, y);
                y += 13;
            }

            if (enabled("health")) {
                drawHud(graphics,
                        String.format("Health: %.1f", MC.player.getHealth()),
                        x, y);
                y += 13;
            }

            if (enabled("sprint")) {
                drawHud(graphics,
                        "Sprinting: " + (MC.player.isSprinting() ? "ON" : "OFF"),
                        x, y);
                y += 13;
            }

            if (enabled("armor")) {
                drawHud(graphics, armorText(), x, y);
                y += 13;
            }

            if (enabled("direction")) {
                drawHud(graphics, directionText(), x, y);
                y += 13;
            }

            if (enabled("biome")) {
                drawHud(graphics, biomeText(), x, y);
                y += 13;
            }

            if (enabled("freecam")) {
                drawHud(graphics, "Freecam: ON (Ctrl = fast)", x, y);
                y += 13;
            }

            if (enabled("storageesp")) {
                drawHud(graphics, "Storage ESP: " + STORAGE_ESP.size(), x, y);
                y += 13;
            }

            if (enabled("storage")) {
                drawHud(graphics, "Storage nearby: " + STORAGE_RESULTS.size(), x, y);
                y += 13;
                for (int i = 0; i < Math.min(3, STORAGE_RESULTS.size()); i++) {
                    drawHud(graphics, "  " + STORAGE_RESULTS.get(i), x, y);
                    y += 13;
                }
            }

            if (enabled("spawners")) {
                drawHud(graphics, "Spawners nearby: " + SPAWNER_RESULTS.size(), x, y);
                y += 13;
                for (int i = 0; i < Math.min(3, SPAWNER_RESULTS.size()); i++) {
                    drawHud(graphics, "  " + SPAWNER_RESULTS.get(i), x, y);
                    y += 13;
                }
            }

            if (enabled("suschunk")) {
                drawHud(graphics, "Sus chunks: " + SUS_CHUNK_RESULTS.size(), x, y);
                y += 13;
                for (int i = 0; i < Math.min(2, SUS_CHUNK_RESULTS.size()); i++) {
                    drawHud(graphics, "  " + SUS_CHUNK_RESULTS.get(i), x, y);
                    y += 13;
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Freecam
    // ------------------------------------------------------------------

    private static void updateFreecam(Minecraft client) {
        boolean want = enabled("freecam");

        if (want && freecam == null) {
            startFreecam();
        } else if (!want && freecam != null) {
            stopFreecam();
        }

        if (freecam == null) return;

        // World/dimension changed under us: bail out safely.
        if (freecam.level() != client.level) {
            freecam = null;
            setEnabled("freecam", false);
            return;
        }

        // Vanilla resets the camera entity on respawn; re-assert ours.
        if (client.getCameraEntity() != freecam) {
            client.setCameraEntity(freecam);
        }

        if (client.screen != null) return;

        long window = client.getWindow().getWindow();
        double fwd = (key(window, GLFW.GLFW_KEY_W) ? 1 : 0)
                - (key(window, GLFW.GLFW_KEY_S) ? 1 : 0);
        double strafe = (key(window, GLFW.GLFW_KEY_D) ? 1 : 0)
                - (key(window, GLFW.GLFW_KEY_A) ? 1 : 0);
        double vert = (key(window, GLFW.GLFW_KEY_SPACE) ? 1 : 0)
                - (key(window, GLFW.GLFW_KEY_LEFT_SHIFT) ? 1 : 0);
        double speed = FREECAM_SPEED
                * (key(window, GLFW.GLFW_KEY_LEFT_CONTROL) ? 3.0 : 1.0);

        double yaw = Math.toRadians(freecam.getYRot());
        double mx = -Math.sin(yaw) * fwd - Math.cos(yaw) * strafe;
        double mz = Math.cos(yaw) * fwd - Math.sin(yaw) * strafe;
        double len = Math.sqrt(mx * mx + mz * mz);
        if (len > 1.0) {
            mx /= len;
            mz /= len;
        }

        // Keep previous position for smooth partial-tick interpolation.
        freecam.xo = freecam.xOld = freecam.getX();
        freecam.yo = freecam.yOld = freecam.getY();
        freecam.zo = freecam.zOld = freecam.getZ();

        freecam.setPos(
                freecam.getX() + mx * speed,
                freecam.getY() + vert * speed,
                freecam.getZ() + mz * speed);
    }

    private static void startFreecam() {
        var p = MC.player;
        freecam = new RemotePlayer(MC.level, p.getGameProfile());
        freecam.setPos(p.getX(), p.getY(), p.getZ());
        freecam.xo = freecam.xOld = p.getX();
        freecam.yo = freecam.yOld = p.getY();
        freecam.zo = freecam.zOld = p.getZ();
        syncFreecamRotation();
        MC.setCameraEntity(freecam);
    }

    private static void stopFreecam() {
        freecam = null;
        if (MC.player != null) {
            MC.setCameraEntity(MC.player);
        }
    }

    /** Mouse still turns the real player; mirror that onto the camera. */
    private static void syncFreecamRotation() {
        if (freecam == null || MC.player == null) return;
        float yaw = MC.player.getYRot();
        float pitch = MC.player.getXRot();
        freecam.setYRot(yaw);
        freecam.setXRot(pitch);
        freecam.yRotO = yaw;
        freecam.xRotO = pitch;
    }

    private static boolean key(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    // ------------------------------------------------------------------
    // Storage ESP (projected 2D boxes, visible through walls)
    // ------------------------------------------------------------------

    private static Entity cameraEntity() {
        Entity cam = MC.getCameraEntity();
        return cam != null ? cam : MC.player;
    }

    private static int espColor(BlockEntity be) {
        if (be instanceof TrappedChestBlockEntity) return 0xFFFF4444;
        if (be instanceof ChestBlockEntity)        return 0xFFFFA726;
        if (be instanceof BarrelBlockEntity)       return 0xFFB5651D;
        if (be instanceof ShulkerBoxBlockEntity)   return 0xFFCE93D8;
        if (be instanceof EnderChestBlockEntity)   return 0xFF26C6DA;
        return 0;
    }

    private static void scanStorageEsp() {
        STORAGE_ESP.clear();
        if (MC.level == null || MC.player == null) return;

        Entity cam = cameraEntity();
        double cx = cam.getX();
        double cy = cam.getY();
        double cz = cam.getZ();
        int chunkX = ((int) Math.floor(cx)) >> 4;
        int chunkZ = ((int) Math.floor(cz)) >> 4;
        int r = (ESP_RANGE >> 4) + 1;
        double maxSq = (double) ESP_RANGE * ESP_RANGE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                var chunk = MC.level.getChunk(
                        chunkX + dx, chunkZ + dz, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk levelChunk)) continue;

                for (BlockEntity be : levelChunk.getBlockEntities().values()) {
                    int color = espColor(be);
                    if (color == 0) continue;

                    BlockPos pos = be.getBlockPos();
                    double ddx = pos.getX() + 0.5 - cx;
                    double ddy = pos.getY() + 0.5 - cy;
                    double ddz = pos.getZ() + 0.5 - cz;
                    if (ddx * ddx + ddy * ddy + ddz * ddz > maxSq) continue;

                    STORAGE_ESP.add(new EspBox(pos, color));
                    if (STORAGE_ESP.size() >= ESP_MAX_BOXES) return;
                }
            }
        }
    }

    private static void drawStorageEsp(GuiGraphics g, float partial) {
        if (STORAGE_ESP.isEmpty()) return;

        Entity cam = cameraEntity();
        if (cam == null) return;

        Vec3 eye = cam.getEyePosition(partial);
        double yaw = Math.toRadians(cam.getViewYRot(partial));
        double pitch = Math.toRadians(cam.getViewXRot(partial));
        double cp = Math.cos(pitch);

        // Camera basis (Minecraft: +X east, +Y up, +Z south).
        double fx = -Math.sin(yaw) * cp;
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * cp;
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;

        double w = MC.getWindow().getGuiScaledWidth();
        double h = MC.getWindow().getGuiScaledHeight();
        double tanHalf = Math.tan(Math.toRadians(MC.options.fov().get()) / 2.0);
        double aspect = w / h;

        for (EspBox box : STORAGE_ESP) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            boolean visible = true;

            for (int i = 0; i < 8 && visible; i++) {
                double x = box.pos.getX() + (i & 1);
                double y = box.pos.getY() + ((i >> 1) & 1);
                double z = box.pos.getZ() + ((i >> 2) & 1);

                double dx = x - eye.x;
                double dy = y - eye.y;
                double dz = z - eye.z;

                double depth = dx * fx + dy * fy + dz * fz;
                if (depth < 0.05) {
                    visible = false;
                    break;
                }

                double px = dx * rx + dz * rz;
                double py = dx * ux + dy * uy + dz * uz;
                double sx = w / 2.0 * (1.0 + px / (depth * tanHalf * aspect));
                double sy = h / 2.0 * (1.0 - py / (depth * tanHalf));

                minX = Math.min(minX, sx);
                minY = Math.min(minY, sy);
                maxX = Math.max(maxX, sx);
                maxY = Math.max(maxY, sy);
            }

            if (!visible) continue;
            if (maxX < 0 || maxY < 0 || minX > w || minY > h) continue;

            int x1 = (int) Math.max(-2000, Math.min(2000, minX));
            int y1 = (int) Math.max(-2000, Math.min(2000, minY));
            int x2 = (int) Math.max(-2000, Math.min(2000, maxX));
            int y2 = (int) Math.max(-2000, Math.min(2000, maxY));
            if (x2 - x1 < 4) x2 = x1 + 4;
            if (y2 - y1 < 4) y2 = y1 + 4;

            drawOutline(g, x1, y1, x2, y2, box.color);

            double ddx = box.pos.getX() + 0.5 - eye.x;
            double ddy = box.pos.getY() + 0.5 - eye.y;
            double ddz = box.pos.getZ() + 0.5 - eye.z;
            int dist = (int) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            g.drawString(MC.font, dist + "m", x1, y1 - 10, box.color);
        }
    }

    private static void drawOutline(
            GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    private static void setEnabled(String id, boolean value) {
        for (Module m : MODULES) {
            if (m.id.equals(id)) m.enabled = value;
        }
    }

    private static void drawHud(
            GuiGraphics graphics, String text, int x, int y) {
        int width = MC.font.width(text);
        graphics.fill(x - 2, y - 2, x + width + 5, y + 11, DARK);
        graphics.drawString(MC.font, text, x, y, WHITE);
    }

    /**
     * Scans only already-loaded nearby block positions. Results are informational
     * HUD markers, not wall-penetrating 3D boxes. Runs once per second.
     */
    private static void scanNearbyBlocks() {
        if (MC.player == null || MC.level == null) return;

        STORAGE_RESULTS.clear();
        SPAWNER_RESULTS.clear();
        SUS_CHUNK_RESULTS.clear();

        var playerPos = MC.player.blockPosition();
        int radius = 16;
        int minY = Math.max(MC.level.getMinBuildHeight(), playerPos.getY() - 8);
        int maxY = Math.min(MC.level.getMaxBuildHeight() - 1, playerPos.getY() + 8);

        java.util.Map<Long, Integer> interestingByChunk = new java.util.HashMap<>();

        for (int x = playerPos.getX() - radius; x <= playerPos.getX() + radius; x++) {
            for (int z = playerPos.getZ() - radius; z <= playerPos.getZ() + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    var pos = new net.minecraft.core.BlockPos(x, y, z);
                    if (!MC.level.hasChunkAt(pos)) continue;

                    var state = MC.level.getBlockState(pos);
                    var block = state.getBlock();
                    String found = null;

                    if (block instanceof net.minecraft.world.level.block.ChestBlock
                            || block instanceof net.minecraft.world.level.block.BarrelBlock
                            || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                        found = "Storage " + x + " " + y + " " + z;
                        if (STORAGE_RESULTS.size() < 50) STORAGE_RESULTS.add(found);
                    }

                    if (block instanceof net.minecraft.world.level.block.SpawnerBlock) {
                        found = "Spawner " + x + " " + y + " " + z;
                        if (SPAWNER_RESULTS.size() < 50) SPAWNER_RESULTS.add(found);
                    }

                    if (found != null) {
                        int chunkX = x >> 4;
                        int chunkZ = z >> 4;
                        long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
                        interestingByChunk.merge(chunkKey, 1, Integer::sum);
                    }
                }
            }
        }

        // Heuristic only: multiple discovered storage/spawner blocks in a chunk
        // are a candidate for inspection, not proof of cheating or unnatural terrain.
        for (var entry : interestingByChunk.entrySet()) {
            if (entry.getValue() >= 4 && SUS_CHUNK_RESULTS.size() < 20) {
                int chunkX = (int) (entry.getKey() >> 32);
                int chunkZ = (int) (long) entry.getKey();
                SUS_CHUNK_RESULTS.add("Chunk " + chunkX + ", " + chunkZ
                        + " (" + entry.getValue() + " finds)");
            }
        }
    }

    private static String armorText() {
        var player = MC.player;
        int equipped = 0;
        int damaged = 0;

        for (var stack : player.getInventory().armor) {
            if (!stack.isEmpty()) {
                equipped++;
                if (stack.isDamageableItem()) {
                    int remaining = stack.getMaxDamage() - stack.getDamageValue();
                    if (remaining <= Math.max(1, stack.getMaxDamage() / 10)) {
                        damaged++;
                    }
                }
            }
        }

        return "Armor: " + equipped + "/4" + (damaged > 0 ? " (" + damaged + " low)" : "");
    }

    private static String directionText() {
        float yaw = MC.player.getYRot();
        String[] directions = {"South", "South-West", "West", "North-West",
                "North", "North-East", "East", "South-East"};
        int index = Math.floorMod(Math.round(yaw / 45.0f), 8);
        return "Facing: " + directions[index];
    }

    private static String biomeText() {
        var biome = MC.level.getBiome(MC.player.blockPosition());
        return "Biome: " + biome.unwrapKey()
                .map(key -> key.location().getPath().replace('_', ' '))
                .orElse("Unknown");
    }

    private static boolean enabled(String id) {
        return MODULES.stream()
                .anyMatch(m -> m.id.equals(id) && m.enabled);
    }

    private static boolean pressed(int key) {
        long window = MC.getWindow().getWindow();
        boolean down = GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;

        if (!down) {
            HELD.remove(key);
            return false;
        }

        return HELD.add(key);
    }

    private static String keyName(int key) {
        String name = GLFW.glfwGetKeyName(key, 0);
        if (name != null) {
            return name.toUpperCase();
        }

        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) return "RSHIFT";
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT) return "LSHIFT";
        if (key == GLFW.GLFW_KEY_SPACE) return "SPACE";
        if (key == GLFW.GLFW_KEY_ESCAPE) return "ESC";

        return "KEY " + key;
    }

    private static void loadConfig() {
        Properties properties = new Properties();

        if (Files.exists(CONFIG)) {
            try (InputStream in = Files.newInputStream(CONFIG)) {
                properties.load(in);
            } catch (IOException e) {
                System.err.println("Hen Client: config load failed: " + e);
            }
        }

        guiKey = parseKey(properties, "key.gui", guiKey);

        for (Module module : MODULES) {
            module.key = parseKey(
                    properties, "key." + module.id, module.key);
            module.enabled = Boolean.parseBoolean(
                    properties.getProperty(
                            "enabled." + module.id,
                            Boolean.toString(module.enabled)));
        }
    }

    private static int parseKey(
            Properties properties, String name, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(
                    name, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void saveConfig() {
        Properties properties = new Properties();
        properties.setProperty("key.gui", Integer.toString(guiKey));

        for (Module module : MODULES) {
            properties.setProperty(
                    "key." + module.id, Integer.toString(module.key));
            properties.setProperty(
                    "enabled." + module.id,
                    Boolean.toString(module.enabled));
        }

        try {
            Files.createDirectories(CONFIG.getParent());
            try (OutputStream out = Files.newOutputStream(CONFIG)) {
                properties.store(out, "Hen Client settings");
            }
        } catch (IOException e) {
            System.err.println("Hen Client: config save failed: " + e);
        }
    }

    private static final class HenScreen extends Screen {
        private static final int WIDTH = 360;

        private static int panelHeight() {
            return 72 + MODULES.size() * 34 + 30;
        }

        private String bindTarget = null;

        HenScreen() {
            super(Component.literal("Hen Client"));
        }

        @Override
        public void render(
                GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fill(0, 0, width, height, 0x99000000);

            int left = (width - WIDTH) / 2;
            int top = (height - panelHeight()) / 2;

            g.fill(left, top, left + WIDTH, top + panelHeight(), DARK);
            g.fill(left, top, left + WIDTH, top + 3, RED);
            g.fill(left, top + 3, left + WIDTH, top + 37, PANEL);

            g.drawString(font, "HEN CLIENT", left + 14, top + 13, RED);
            g.drawString(font, "FABRIC 1.21.11",
                    left + 235, top + 13, MUTED);

            g.drawString(font, "MODULES", left + 14, top + 48, WHITE);

            String guiBind = bindTarget != null
                    && bindTarget.equals("gui")
                    ? "Press a key..." : keyName(guiKey);

            g.fill(left + 206, top + 43, left + 345, top + 62,
                    bindTarget != null && bindTarget.equals("gui")
                            ? RED : PANEL);
            g.drawString(font, "GUI KEY: " + guiBind,
                    left + 211, top + 49, WHITE);

            int rowY = top + 72;

            for (Module module : MODULES) {
                g.fill(left + 10, rowY, left + WIDTH - 10,
                        rowY + 29, PANEL);

                g.fill(left + 16, rowY + 7, left + 25,
                        rowY + 16, module.enabled ? RED : 0xFF555555);

                g.drawString(font, module.name,
                        left + 33, rowY + 10, WHITE);

                boolean binding = module.id.equals(bindTarget);
                String keyText = binding
                        ? "PRESS KEY" : keyName(module.key);

                g.fill(left + 245, rowY + 4, left + 340,
                        rowY + 25, binding ? RED : DARK);

                int textWidth = font.width(keyText);
                g.drawString(font, keyText,
                        left + 292 - textWidth / 2,
                        rowY + 10, WHITE);

                rowY += 34;
            }

            String footer = bindTarget == null
                    ? "Click a module to toggle it | Click its key to rebind"
                    : "Press a key to bind | ESC cancels";

            g.drawString(font, footer,
                    left + 12, top + panelHeight() - 18, MUTED);

            super.render(g, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(
                double mouseX, double mouseY, int button) {
            int left = (width - WIDTH) / 2;
            int top = (height - panelHeight()) / 2;

            if (button == 0) {
                if (mouseX >= left + 206 && mouseX <= left + 345
                        && mouseY >= top + 43 && mouseY <= top + 62) {
                    bindTarget = "gui";
                    return true;
                }

                int rowY = top + 72;

                for (Module module : MODULES) {
                    if (mouseY >= rowY && mouseY < rowY + 29
                            && mouseX >= left + 10
                            && mouseX <= left + WIDTH - 10) {
                        if (mouseX >= left + 240) {
                            bindTarget = module.id;
                        } else {
                            module.enabled = !module.enabled;
                            saveConfig();
                        }
                        return true;
                    }
                    rowY += 34;
                }
            }

            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(
                int keyCode, int scanCode, int modifiers) {
            if (bindTarget != null) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    bindTarget = null;
                    return true;
                }

                if (keyCode != GLFW.GLFW_KEY_UNKNOWN) {
                    if (bindTarget.equals("gui")) {
                        guiKey = keyCode;
                    } else {
                        for (Module module : MODULES) {
                            if (module.id.equals(bindTarget)) {
                                module.key = keyCode;
                                break;
                            }
                        }
                    }

                    bindTarget = null;
                    saveConfig();
                }
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}