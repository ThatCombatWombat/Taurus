package com.riptide;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.Comparator;

/** Cast -> (pet swap) -> wait for bite -> reel -> kill the catch with Hyperion -> recast. */
public final class AutoFisher {

    // the PET_* states must stay last: they run even while the pets menu (a screen) is open
    private enum State { OFF, CAST, WAIT_BITE, REEL, WAIT_SPAWN, KILL, PET_OPEN, PET_WAIT_MENU, PET_PICK, PET_CLOSING }

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("riptide", "main"));

    private static KeyMapping toggleKey;
    private static State state = State.OFF;
    private static final Random rng = new Random();

    private static long now;          // real game ticks since the mod loaded (never paused by delays)
    private static int delay;
    private static int ticks;
    private static long killStart;
    private static int killUses;
    private static int castCount;
    private static int petWait;
    private static final Set<Integer> baseline = new HashSet<>();
    private static float savedYaw, savedPitch;
    private static boolean viewMoved;
    private static Vec3 catchOrigin;

    private AutoFisher() {}

    public static void init() {
        toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.riptide.toggle", InputConstants.KEY_J, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(AutoFisher::tick);
    }

    public static boolean isRunning() { return state != State.OFF; }

    public static void toggle(Minecraft mc) {
        if (state != State.OFF) { stop(mc, "Riptide: OFF"); return; }
        LocalPlayer p = mc.player;
        if (p == null) return;
        savedYaw = p.getYRot();
        savedPitch = p.getXRot();
        viewMoved = false;
        castCount = 0;
        clearCatchAttempt();
        state = State.CAST;
        delay = 0;
        p.sendSystemMessage(Component.literal("Riptide: ON"));
    }

    private static void tick(Minecraft mc) {
        now++;
        while (toggleKey.consumeClick()) toggle(mc);
        if (state == State.OFF) return;

        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) { state = State.OFF; return; }
        if (!p.isAlive()) { stop(mc, "Riptide stopped (dead)"); return; }
        // Jawbus is a shared, nearby event. Stop immediately even when another player caught it.
        if (hasJawbus(mc, p, RiptideConfig.get())) {
            stop(mc, "Riptide stopped (Jawbus detected)");
            return;
        }
        boolean petState = state.ordinal() >= State.PET_OPEN.ordinal();
        if (!petState && currentScreen(mc) != null) return; // pause while any other menu is open
        if (delay > 0) { delay--; return; }

        RiptideConfig c = RiptideConfig.get();

        switch (state) {
            case CAST -> {
                int rod = findRod(p);
                if (rod < 0) { stop(mc, "Riptide: no fishing rod in hotbar"); return; }
                restoreView(p);
                p.getInventory().setSelectedSlot(rod);
                if (p.fishing == null) use(mc, p);
                castCount++;
                ticks = 0;
                if (shouldSwapPet(c)) {
                    state = State.PET_OPEN;
                    delay = rand(3, 6);
                } else {
                    state = State.WAIT_BITE;
                    delay = rand(15, 25); // let the bobber land
                }
            }

            case PET_OPEN -> {
                if (!sendCommand(mc, p, c.petCommand)) {
                    p.sendSystemMessage(Component.literal("Riptide: couldn't send /" + c.petCommand));
                    backToFishing();
                    return;
                }
                petWait = 0;
                state = State.PET_WAIT_MENU;
                delay = 2;
            }

            case PET_WAIT_MENU -> {
                petWait++;
                if (menuOpen(p)) {
                    state = State.PET_PICK;
                    delay = rand(4, 7); // let the menu contents arrive
                } else if (petWait > c.petMenuWaitTicks) {
                    p.sendSystemMessage(Component.literal("Riptide: /" + c.petCommand + " menu didn't open"));
                    backToFishing();
                }
            }

            case PET_PICK -> {
                int slot = menuOpen(p) ? findSlotByName(p, c.petName) : -1;
                if (slot < 0) {
                    p.sendSystemMessage(Component.literal("Riptide: pet '" + c.petName + "' not found in the menu"));
                } else if (!clickSlot(mc, p, slot)) {
                    p.sendSystemMessage(Component.literal("Riptide: couldn't click the pet slot"));
                }
                state = State.PET_CLOSING;
                delay = rand(4, 8);
            }

            case PET_CLOSING -> {
                if (menuOpen(p)) closeMenu(mc, p);
                backToFishing();
            }

            case WAIT_BITE -> {
                ticks++;
                FishingHook hook = p.fishing;
                if (hook == null) {
                    if (ticks > 30) { state = State.CAST; delay = rand(c.recastMin, c.recastMax); }
                    return;
                }
                if (biteDetected(mc, hook, c)) {
                    state = State.REEL;
                    delay = rand(c.reactionMin, c.reactionMax);
                    return;
                }
                if (ticks > c.biteTimeoutSec * 20) {
                    use(mc, p);
                    state = State.CAST;
                    delay = rand(c.recastMin, c.recastMax);
                }
            }

            case REEL -> {
                FishingHook hook = p.fishing;
                catchOrigin = hook != null ? hook.position() : p.position();
                snapshotBaseline(mc, p, c);
                if (p.fishing != null) use(mc, p);
                state = State.WAIT_SPAWN;
                ticks = 0;
                delay = rand(2, 4);
            }

            case WAIT_SPAWN -> {
                ticks++;
                if (!fresh(mc, p, c).isEmpty()) { startKill(mc, p, c); return; }
                if (ticks > c.creatureWaitTicks) {
                    state = State.CAST;
                    clearCatchAttempt();
                    delay = rand(c.recastMin, c.recastMax);
                }
            }

            case KILL -> {
                List<LivingEntity> targets = fresh(mc, p, c);
                // timeout is measured in real ticks (not attack ticks), plus a hard cap on attacks
                if (targets.isEmpty() || now - killStart > c.killTimeoutSec * 20L || killUses >= c.maxAttacks) {
                    state = State.CAST;
                    clearCatchAttempt();
                    delay = rand(c.recastMin, c.recastMax);
                    return;
                }
                boolean thunder = targets.stream().anyMatch(AutoFisher::isThunder);
                if (c.attackMode == RiptideConfig.AttackMode.ABILITY) {
                    if (c.aimDown) { p.setXRot(90f); viewMoved = true; }
                    use(mc, p);
                    killUses++;
                    delay = thunder ? 1 : c.abilityCooldownTicks + rng.nextInt(4);
                } else {
                    LivingEntity t = nearest(p, targets);
                    if (p.distanceTo(t) <= 4.0) {
                        face(p, t);
                        mc.gameMode.attack(p, t);
                        p.swing(InteractionHand.MAIN_HAND);
                        killUses++;
                        delay = thunder ? 1 : c.abilityCooldownTicks + rng.nextInt(4);
                    }
                }
            }

            default -> {}
        }
    }

    // ---------------- pets ----------------

    private static boolean shouldSwapPet(RiptideConfig c) {
        if (!c.petEnabled || c.petName.isBlank()) return false;
        if (castCount == 1) return true;
        return c.petEveryCasts > 0 && (castCount - 1) % c.petEveryCasts == 0;
    }

    private static void backToFishing() {
        state = State.WAIT_BITE;
        ticks = 0;
        delay = rand(3, 6);
    }

    /** The player's own inventory menu has container id 0; anything else is an opened menu. */
    private static boolean menuOpen(LocalPlayer p) {
        return p.containerMenu != null && p.containerMenu.containerId != 0;
    }

    private static int findSlotByName(LocalPlayer p, String name) {
        try {
            List<?> slots = (List<?>) readField(p.containerMenu, "slots");
            int limit = slots.size() > 36 ? slots.size() - 36 : slots.size(); // skip your own inventory rows
            String n = name.trim().toLowerCase();
            for (int i = 0; i < limit; i++) {
                ItemStack s = (ItemStack) callNoArg(slots.get(i), "getItem");
                if (s != null && !s.isEmpty() && s.getHoverName().getString().toLowerCase().contains(n)) return i;
            }
        } catch (Exception ignored) { }
        return -1;
    }

    /** Left-click a menu slot. The click method was renamed in 26.x, so it's found by its signature. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean clickSlot(Minecraft mc, LocalPlayer p, int slot) {
        try {
            Object gm = mc.gameMode;
            for (java.lang.reflect.Method m : gm.getClass().getMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (t.length == 5 && t[0] == int.class && t[1] == int.class && t[2] == int.class
                        && t[3].isEnum() && t[4].isAssignableFrom(p.getClass())) {
                    Object pickup;
                    try { pickup = Enum.valueOf((Class) t[3], "PICKUP"); }
                    catch (IllegalArgumentException e) { pickup = t[3].getEnumConstants()[0]; }
                    m.invoke(gm, p.containerMenu.containerId, slot, 0, pickup, p);
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static boolean sendCommand(Minecraft mc, LocalPlayer p, String cmd) {
        try {
            String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
            Object conn = null;
            try { conn = mc.getClass().getMethod("getConnection").invoke(mc); } catch (Exception ignored) { }
            if (conn == null) conn = readField(p, "connection");
            conn.getClass().getMethod("sendCommand", String.class).invoke(conn, c);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void closeMenu(Minecraft mc, LocalPlayer p) {
        try { callNoArg(p, "closeContainer"); return; } catch (Exception ignored) { }
        try {
            Object s = currentScreen(mc);
            if (s != null) callNoArg(s, "onClose");
        } catch (Exception ignored) { }
    }

    // ---------------- helpers ----------------

    private static int rand(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return lo + rng.nextInt(hi - lo + 1);
    }

    private static void stop(Minecraft mc, String msg) {
        state = State.OFF;
        clearCatchAttempt();
        if (mc.player != null) {
            restoreView(mc.player);
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }

    private static void use(Minecraft mc, LocalPlayer p) {
        InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        if (r.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
    }

    private static void startKill(Minecraft mc, LocalPlayer p, RiptideConfig c) {
        int h = findHyperion(p, c);
        if (h < 0) { stop(mc, "Riptide: Hyperion not found in hotbar"); return; }
        p.getInventory().setSelectedSlot(h);
        state = State.KILL;
        killStart = now;
        killUses = 0;
        // Thunders are urgent; begin their kill sequence as soon as they spawn.
        delay = fresh(mc, p, c).stream().anyMatch(AutoFisher::isThunder) ? 0 : rand(4, 8);
    }

    private static void restoreView(LocalPlayer p) {
        if (!viewMoved) return;
        p.setYRot(savedYaw);
        p.setXRot(savedPitch);
        viewMoved = false;
    }

    private static int findRod(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.getItem() == Items.FISHING_ROD || s.getItem() instanceof FishingRodItem) return i;
        }
        return -1;
    }

    private static int findHyperion(LocalPlayer p, RiptideConfig c) {
        if (c.hyperionSlot > 0) return c.hyperionSlot - 1;
        String n = c.hyperionName.trim().toLowerCase();
        if (n.isEmpty()) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && s.getHoverName().getString().toLowerCase().contains(n)) return i;
        }
        return -1;
    }

    private static boolean biteDetected(Minecraft mc, FishingHook hook, RiptideConfig c) {
        if (c.biteMode != RiptideConfig.BiteMode.DIP_ONLY) {
            AABB box = hook.getBoundingBox().inflate(2.5, 3.0, 2.5);
            for (Entity e : mc.level.getEntitiesOfClass(Entity.class, box, x -> x.hasCustomName())) {
                Component name = e.getCustomName();
                if (name != null && name.getString().contains("!!!")) return true;
            }
            if (c.biteMode == RiptideConfig.BiteMode.TAG_ONLY) return false;
        }
        return hook.isInWater() && hook.getDeltaMovement().y() < -0.08;
    }

    private static void snapshotBaseline(Minecraft mc, LocalPlayer p, RiptideConfig c) {
        baseline.clear();
        AABB box = p.getBoundingBox().inflate(c.scanRange + 8);
        for (Entity e : mc.level.getEntitiesOfClass(Entity.class, box, e -> true)) {
            baseline.add(e.getId());
        }
        if (catchOrigin != null) {
            AABB bobberBox = new AABB(catchOrigin, catchOrigin).inflate(12.0);
            for (Entity e : mc.level.getEntitiesOfClass(Entity.class, bobberBox, entity -> true)) {
                baseline.add(e.getId());
            }
        }
    }

    /** New entities at this player's bobber are the catch; never use lobby-wide names. */
    private static List<LivingEntity> fresh(Minecraft mc, LocalPlayer p, RiptideConfig c) {
        if (catchOrigin == null) return List.of();
        AABB box = new AABB(catchOrigin, catchOrigin).inflate(8.0);
        List<LivingEntity> candidates = mc.level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != p && e.isAlive() && !isArmorStand(e) && !baseline.contains(e.getId())
                        && e.position().distanceToSqr(catchOrigin) <= 64.0 && !isListedPlayer(mc, e));
        candidates.sort(Comparator.comparing(AutoFisher::isThunder).reversed()
                .thenComparingDouble(e -> e.position().distanceToSqr(catchOrigin)));
        return candidates;
    }

    /** Real players are tab-listed; Hypixel's player-shaped sea creatures are not. */
    private static boolean isListedPlayer(Minecraft mc, LivingEntity entity) {
        if (!(entity instanceof Player player) || mc.getConnection() == null) return false;
        return mc.getConnection().getListedOnlinePlayers().stream()
                .anyMatch(info -> info.getProfile().id().equals(player.getUUID()));
    }

    private static void clearCatchAttempt() {
        catchOrigin = null;
    }

    private static boolean hasJawbus(Minecraft mc, LocalPlayer p, RiptideConfig c) {
        AABB box = p.getBoundingBox().inflate(c.scanRange);
        return mc.level.getEntitiesOfClass(Entity.class, box, e -> {
            Component name = e.getCustomName();
            return name != null && name.getString().toLowerCase().contains("jawbus");
        }).size() > 0;
    }

    private static boolean isThunder(LivingEntity e) {
        Component name = e.getCustomName();
        return name != null && name.getString().toLowerCase().contains("thunder");
    }

    private static LivingEntity nearest(LocalPlayer p, List<LivingEntity> list) {
        for (LivingEntity e : list) if (isThunder(e)) return e;
        LivingEntity best = list.get(0);
        for (LivingEntity e : list) if (p.distanceTo(e) < p.distanceTo(best)) best = e;
        return best;
    }

    private static void face(LocalPlayer p, LivingEntity t) {
        Vec3 d = t.getEyePosition().subtract(p.getEyePosition());
        double h = Math.sqrt(d.x() * d.x() + d.z() * d.z());
        p.setYRot((float) (Math.toDegrees(Math.atan2(d.z(), d.x())) - 90.0));
        p.setXRot((float) -Math.toDegrees(Math.atan2(d.y(), h)));
        viewMoved = true;
    }

    // ---- version-proof helpers (26.2 moved/renamed these, so they're resolved by name at runtime) ----

    private static boolean isArmorStand(Entity e) {
        return e.getClass().getSimpleName().contains("ArmorStand");
    }

    private static int screenMode = -1; // -1 = not resolved yet, 0-3 = working lookup, 99 = none found

    private static Object currentScreen(Minecraft mc) {
        if (screenMode == 99) return null;
        for (int mode = Math.max(screenMode, 0); mode < 4; mode++) {
            try {
                Object v = switch (mode) {
                    case 0 -> mc.getClass().getMethod("getScreen").invoke(mc);
                    case 1 -> callNoArg(readField(mc, "gui"), "screen");
                    case 2 -> readField(mc, "screen");
                    default -> readField(readField(mc, "gui"), "screen");
                };
                screenMode = mode;
                return v;
            } catch (Exception ignored) {
                if (screenMode >= 0) return null;
            }
        }
        screenMode = 99;
        return null;
    }

    private static Object callNoArg(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
