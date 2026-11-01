package com.riptide;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RiptideConfig {
    public enum BiteMode { AUTO, TAG_ONLY, DIP_ONLY }
    public enum AttackMode { ABILITY, MELEE }

    // fishing
    public int reactionMin = 3;
    public int reactionMax = 8;
    public int recastMin = 8;
    public int recastMax = 22;
    public int biteTimeoutSec = 45;
    public int creatureWaitTicks = 40;
    public BiteMode biteMode = BiteMode.AUTO;

    // combat
    public String hyperionName = "hyperion"; // matched against the item name; edit in config/riptide.json
    public int hyperionSlot = 0;             // 0 = find by name, 1-9 = always use that hotbar slot
    public AttackMode attackMode = AttackMode.ABILITY;
    public boolean aimDown = true;
    public int abilityCooldownTicks = 10;
    public int killTimeoutSec = 10;
    public int scanRange = 16;
    public int maxAttacks = 30;       // give up on a creature after this many attacks

    // pets
    public boolean petEnabled = false;
    public String petName = "";        // part of the pet's name in the /pets menu
    public String petCommand = "pets"; // command that opens the menu (no slash); edit in config/riptide.json
    public int petEveryCasts = 0;      // 0 = first cast after starting only, N = every N casts
    public int petMenuWaitTicks = 50;  // how long to wait for the menu to open

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static RiptideConfig instance = new RiptideConfig();

    public static RiptideConfig get() { return instance; }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("riptide.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                RiptideConfig c = GSON.fromJson(Files.readString(p), RiptideConfig.class);
                if (c != null) instance = c;
            }
        } catch (Exception ignored) { /* keep defaults */ }
        instance.sanitize();
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(instance));
        } catch (IOException ignored) { }
    }

    public static void reset() {
        instance = new RiptideConfig();
        save();
    }

    private void sanitize() {
        if (biteMode == null) biteMode = BiteMode.AUTO;
        if (attackMode == null) attackMode = AttackMode.ABILITY;
        if (hyperionName == null) hyperionName = "hyperion";
        if (petName == null) petName = "";
        if (petCommand == null || petCommand.isBlank()) petCommand = "pets";
        hyperionSlot = Math.max(0, Math.min(9, hyperionSlot));
        scanRange = Math.max(6, Math.min(32, scanRange));
    }
}
