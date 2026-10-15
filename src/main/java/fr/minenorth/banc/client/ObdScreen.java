package fr.minenorth.banc.client;

import fr.minenorth.banc.network.BancNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Écran de diagnostic de l'outil OBD2 (même charte bleu nuit que le banc) : une carte par moteur avec sa jauge de santé, puis l'état des pneus. */
public class ObdScreen extends Screen {
    private static final int W = 300, CARD_H = 70, FRAME = 0xFF0E0E10, PANEL = 0xFF161048, LIST = 0xFF0E0A34, DARK = 0xFF4A3CB4, TEXT = 0xFFCFE3FF,
            MUTED = 0xFF8FA8E0, WHITE = 0xFFFFFFFF, OK = 0xFF6CFF9A, WARN = 0xFFFFE066, ALERT = 0xFFFF6A9A, BAR_BG = 0xFF2A2468;

    private final BancNetwork.ObdReport report;
    private int left, top, h;

    private ObdScreen(BancNetwork.ObdReport report) {
        super(Component.literal("Diagnostic OBD2"));
        this.report = report;
    }

    public static void show(BancNetwork.ObdReport r) { Minecraft.getInstance().setScreen(new ObdScreen(r)); }

    @Override
    protected void init() {
        int cards = Math.max(1, report.engines().size());
        h = 40 + cards * (CARD_H + 6) + 36 + 28;
        left = (width - W) / 2;
        top = Math.max(4, (height - h) / 2);
        addRenderableWidget(new Btn(left + W / 2 - 50, top + h - 26, 100, 18, "Fermer", () -> onClose()));
    }

    private static int colorOf(double health) { return health >= 60 ? OK : health >= 35 ? WARN : ALERT; }

    private static String gradeOf(double h) { return h >= 85 ? "Excellent" : h >= 60 ? "Bon" : h >= 35 ? "Usé" : h >= 15 ? "Mauvais" : "Critique"; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.fill(left - 3, top - 3, left + W + 3, top + h + 3, FRAME);
        g.fill(left, top, left + W, top + h, PANEL);
        g.pose().pushPose();
        g.pose().scale(1.6f, 1.6f, 1f);
        g.drawString(font, Component.literal("Diagnostic OBD2").withStyle(net.minecraft.ChatFormatting.BOLD), Math.round((left + 14) / 1.6f), Math.round((top + 9) / 1.6f), WHITE, false);
        g.pose().popPose();
        g.drawString(font, font.plainSubstrByWidth(report.vehicle(), W - 28), left + 14, top + 28, MUTED, false);

        int y = top + 40;
        if (report.engines().isEmpty()) {
            g.fill(left + 10, y, left + W - 10, y + CARD_H, LIST);
            g.drawString(font, "Aucun moteur détecté.", left + 18, y + 8, ALERT, false);
            y += CARD_H + 6;
        }
        for (BancNetwork.EngineInfo e : report.engines()) {
            g.fill(left + 10, y, left + W - 10, y + CARD_H, LIST);
            double health = Math.max(0, Math.min(100, e.health()));
            int col = colorOf(health);
            g.drawString(font, e.label(), left + 18, y + 6, WHITE, false);
            String state = gradeOf(health) + String.format(Locale.ROOT, "  %.0f %%", health);
            g.drawString(font, state, left + W - 18 - font.width(state), y + 6, col, false);
            g.fill(left + 18, y + 19, left + W - 18, y + 27, BAR_BG);
            g.fill(left + 18, y + 19, left + 18 + (int) ((W - 36) * health / 100), y + 27, col);
            g.drawString(font, String.format(Locale.ROOT, "Dégâts %.0f %%   Heures %.1f h", e.damage(), e.hours()), left + 18, y + 33, TEXT, false);
            g.drawString(font, String.format(Locale.ROOT, "Température %.0f °C   Huile %.0f   Régime %.0f tr/min", e.temp(), e.oil(), e.rpm()), left + 18, y + 44, e.temp() > 100 ? ALERT : TEXT, false);
            String note = e.health() < 35 ? "À réviser : kit de réparation moteur" : e.temp() > 100 ? "Surchauffe" : e.running() ? "Moteur allumé" : "Moteur éteint";
            g.drawString(font, note, left + 18, y + 56, e.health() < 35 || e.temp() > 100 ? ALERT : MUTED, false);
            y += CARD_H + 6;
        }
        g.fill(left + 10, y, left + W - 10, y + 30, LIST);
        g.drawString(font, report.prep(), left + 18, y + 5, TEXT, false);
        String tires = report.wheels() == 0 ? "Pneus : illisibles"
                : report.flat() == 0 ? "Pneus : en bon état (" + report.wheels() + ")" : "Pneus : " + report.flat() + " crevé(s) sur " + report.wheels();
        g.drawString(font, tires, left + 18, y + 17, report.flat() > 0 ? ALERT : OK, false);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static final class Btn extends AbstractButton {
        private final Runnable action;

        Btn(int x, int y, int w, int h, String label, Runnable action) {
            super(x, y, w, h, Component.literal(label));
            this.action = action;
        }

        @Override public void onPress() { action.run(); }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            g.fill(getX(), getY(), getX() + width, getY() + height, isHoveredOrFocused() ? 0xFF6A5AD8 : DARK);
            var f = Minecraft.getInstance().font;
            g.drawString(f, getMessage(), getX() + width / 2 - f.width(getMessage()) / 2, getY() + (height - 8) / 2, WHITE, false);
        }
    }
}
