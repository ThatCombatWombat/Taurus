package com.riptide;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

public class RiptideScreen extends Screen {
    private static final int PAD = 10, COL_W = 160, GAP = 8, ROW = 24, H = 192;
    private static final int W = PAD * 2 + COL_W * 2 + GAP;
    private static final int ACCENT = 0xFF4FC3F7, MUTED = 0xFF8A94A6, WHITE = 0xFFFFFFFF, GREEN = 0xFF66BB6A;
    private static final int BORDER = 0xFF3A3F4B, HEADER = 0xFF1B2330, FIELD = 0xFF10141C;
    private static final String[] TABS = {"Fishing", "Combat", "Pets"};
    private static int tab = 0; // remembered between openings

    private record Row(String label, IntSupplier get, IntFunction<String> fmt, int x, int y, int w) {}
    private record Label(String text, int x, int y) {}

    private final List<Row> rows = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private int left, top;

    public RiptideScreen() {
        super(Component.literal("Riptide"));
    }

    @Override
    protected void init() {
        rows.clear();
        labels.clear();
        RiptideConfig c = RiptideConfig.get();
        left = (this.width - W) / 2;
        top = Math.max(4, (this.height - H) / 2);
        int x1 = left + PAD, x2 = x1 + COL_W + GAP, y0 = top + 58;

        // ---- tabs ----
        int tw = (W - 2 * PAD - 8) / 3;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            addRenderableWidget(Button.builder(Component.literal(i == tab ? "> " + TABS[i] + " <" : TABS[i]), b -> {
                        if (idx != tab) {
                            tab = idx;
                            this.minecraft.setScreenAndShow(new RiptideScreen());
                        }
                    })
                    .bounds(left + PAD + i * (tw + 4), top + 30, tw, 20).build());
        }

        // ---- tab content ----
        switch (tab) {
            case 0 -> {
                stepper(x1, y0, "Reaction min", 1, 40, 1, () -> c.reactionMin, v -> c.reactionMin = v, v -> v + "t");
                stepper(x1, y0 + ROW, "Reaction max", 1, 40, 1, () -> c.reactionMax, v -> c.reactionMax = v, v -> v + "t");
                stepper(x1, y0 + 2 * ROW, "Recast min", 0, 60, 2, () -> c.recastMin, v -> c.recastMin = v, v -> v + "t");
                stepper(x1, y0 + 3 * ROW, "Recast max", 0, 60, 2, () -> c.recastMax, v -> c.recastMax = v, v -> v + "t");
                stepper(x2, y0, "Bite timeout", 10, 120, 5, () -> c.biteTimeoutSec, v -> c.biteTimeoutSec = v, v -> v + "s");
                stepper(x2, y0 + ROW, "Creature wait", 10, 100, 5, () -> c.creatureWaitTicks, v -> c.creatureWaitTicks = v, v -> v + "t");
                Button bite = Button.builder(biteLabel(c), b -> {
                            RiptideConfig.BiteMode[] m = RiptideConfig.BiteMode.values();
                            c.biteMode = m[(c.biteMode.ordinal() + 1) % m.length];
                            b.setMessage(biteLabel(c));
                        })
                        .bounds(x2, y0 + 2 * ROW, COL_W, 20).build();
                bite.setTooltip(Tooltip.create(Component.literal("Auto: '!!!' tag first, bobber dip as fallback")));
                addRenderableWidget(bite);
            }
            case 1 -> {
                stepper(x1, y0, "Hyperion slot", 0, 9, 1, () -> c.hyperionSlot, v -> c.hyperionSlot = v,
                        v -> v == 0 ? "Auto" : String.valueOf(v));
                Button attack = Button.builder(attackLabel(c), b -> {
                            c.attackMode = c.attackMode == RiptideConfig.AttackMode.ABILITY
                                    ? RiptideConfig.AttackMode.MELEE : RiptideConfig.AttackMode.ABILITY;
                            b.setMessage(attackLabel(c));
                        })
                        .bounds(x1, y0 + ROW, COL_W, 20).build();
                attack.setTooltip(Tooltip.create(Component.literal("Ability = right-click Hyperion. Melee = hit the nearest catch.")));
                addRenderableWidget(attack);
                Button aim = Button.builder(aimLabel(c), b -> {
                            c.aimDown = !c.aimDown;
                            b.setMessage(aimLabel(c));
                        })
                        .bounds(x1, y0 + 2 * ROW, COL_W, 20).build();
                aim.setTooltip(Tooltip.create(Component.literal("Look straight down while using the ability so a teleport can't move you")));
                addRenderableWidget(aim);
                stepper(x1, y0 + 3 * ROW, "Attack delay", 2, 40, 1, () -> c.abilityCooldownTicks, v -> c.abilityCooldownTicks = v, v -> v + "t");
                stepper(x2, y0, "Kill timeout", 3, 30, 1, () -> c.killTimeoutSec, v -> c.killTimeoutSec = v, v -> v + "s");
                stepper(x2, y0 + ROW, "Max attacks", 5, 100, 5, () -> c.maxAttacks, v -> c.maxAttacks = v, String::valueOf);
                stepper(x2, y0 + 2 * ROW, "Scan range", 6, 32, 2, () -> c.scanRange, v -> c.scanRange = v, v -> v + "m");
            }
            default -> {
                Button pets = Button.builder(petsLabel(c), b -> {
                            c.petEnabled = !c.petEnabled;
                            b.setMessage(petsLabel(c));
                        })
                        .bounds(x1, y0, COL_W, 20).build();
                pets.setTooltip(Tooltip.create(Component.literal("After casting: runs /pets, clicks the pet whose name contains the text below, then closes the menu")));
                addRenderableWidget(pets);

                labels.add(new Label("Pet name:", x1, y0 + ROW + 6));
                EditBox name = new EditBox(Minecraft.getInstance().font, x1 + 58, y0 + ROW, COL_W - 58, 20,
                        Component.literal("Pet name"));
                name.setValue(c.petName);
                name.setResponder(s -> c.petName = s);
                addRenderableWidget(name);

                stepper(x2, y0, "Swap", 0, 50, 1, () -> c.petEveryCasts, v -> c.petEveryCasts = v,
                        v -> v == 0 ? "first cast" : "every " + v);
                stepper(x2, y0 + ROW, "Menu wait", 20, 100, 10, () -> c.petMenuWaitTicks, v -> c.petMenuWaitTicks = v, v -> v + "t");
            }
        }

        // ---- footer ----
        int by = y0 + 4 * ROW + 6;
        int fw = (W - 2 * PAD - 2 * GAP) / 3;
        addRenderableWidget(Button.builder(runLabel(), b -> {
                    AutoFisher.toggle(this.minecraft);
                    b.setMessage(runLabel());
                })
                .bounds(x1, by, fw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset defaults"), b -> {
                    RiptideConfig.reset();
                    this.minecraft.setScreenAndShow(new RiptideScreen());
                })
                .bounds(x1 + fw + GAP, by, fw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(x1 + 2 * (fw + GAP), by, fw, 20).build());
    }

    /** [-]  label: value  [+] */
    private void stepper(int x, int y, String label, int min, int max, int step,
                         IntSupplier get, IntConsumer set, IntFunction<String> fmt) {
        rows.add(new Row(label, get, fmt, x + 22, y, COL_W - 44));
        addRenderableWidget(Button.builder(Component.literal("-"),
                        b -> set.accept(Math.max(min, get.getAsInt() - step)))
                .bounds(x, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"),
                        b -> set.accept(Math.min(max, get.getAsInt() + step)))
                .bounds(x + COL_W - 20, y, 20, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        Font font = Minecraft.getInstance().font;

        // frame + header (nothing here overlaps the widgets)
        g.fill(left - 1, top - 1, left + W + 1, top, BORDER);
        g.fill(left - 1, top + H, left + W + 1, top + H + 1, BORDER);
        g.fill(left - 1, top, left, top + H, BORDER);
        g.fill(left + W, top, left + W + 1, top + H, BORDER);
        g.fill(left, top, left + W, top + 24, HEADER);
        g.fill(left, top + 24, left + W, top + 25, ACCENT);
        g.text(font, Component.literal("RIPTIDE"), left + PAD, top + 8, ACCENT);

        Component status = Component.literal(AutoFisher.isRunning() ? "RUNNING" : "IDLE");
        g.text(font, status, left + W - PAD - font.width(status), top + 8, AutoFisher.isRunning() ? GREEN : MUTED);

        // stepper value labels, between their - and + buttons
        for (Row r : rows) {
            g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + 20, FIELD);
            Component t = Component.literal(r.label() + ": " + r.fmt().apply(r.get().getAsInt()));
            g.text(font, t, r.x() + (r.w() - font.width(t)) / 2, r.y() + 6, WHITE);
        }
        for (Label l : labels) {
            g.text(font, Component.literal(l.text()), l.x(), l.y(), MUTED);
        }
    }

    @Override
    public void onClose() {
        RiptideConfig.save();
        super.onClose();
    }

    private static Component biteLabel(RiptideConfig c) {
        return Component.literal("Bite: " + switch (c.biteMode) {
            case AUTO -> "Auto";
            case TAG_ONLY -> "!!! tag only";
            case DIP_ONLY -> "Bobber dip only";
        });
    }

    private static Component attackLabel(RiptideConfig c) {
        return Component.literal("Attack: " + (c.attackMode == RiptideConfig.AttackMode.ABILITY ? "Ability" : "Melee"));
    }

    private static Component aimLabel(RiptideConfig c) {
        return Component.literal("Aim down: " + (c.aimDown ? "ON" : "OFF"));
    }

    private static Component petsLabel(RiptideConfig c) {
        return Component.literal("Pet swap: " + (c.petEnabled ? "ON" : "OFF"));
    }

    private static Component runLabel() {
        return Component.literal(AutoFisher.isRunning() ? "Stop fishing" : "Start fishing");
    }
}
