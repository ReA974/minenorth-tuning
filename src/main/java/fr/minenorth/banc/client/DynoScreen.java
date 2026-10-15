package fr.minenorth.banc.client;

import fr.minenorth.banc.network.BancNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Écran du banc de puissance (charte MineNorth, même fond bleu nuit que les autres mods) : état du moteur du véhicule garé près du
 * banc, puis niveau de reprogrammation à appliquer. Utilisé par le personnel ; le client est facturé séparément. Le serveur revérifie tout.
 */
public class DynoScreen extends Screen {
    private static final int W = 300, ROW_H = 26, FRAME = 0xFF0E0E10, PANEL = 0xFF161048, LIST = 0xFF0E0A34, HOVER = 0xFF2E2480,
            CYAN = 0xFF20AAEB, GREEN = 0xFF1E9E52, DARK = 0xFF4A3CB4, TEXT = 0xFFCFE3FF, MUTED = 0xFF8FA8E0, WARN = 0xFFFFE066,
            ALERT = 0xFFFF6A9A, OK = 0xFF6CFF9A, BLUE = 0xFF20AAEB, WHITE = 0xFFFFFFFF, DISABLED = 0xFF2A2468;

    private BancNetwork.State state;
    private int left, top, h, rowsTop, tabsTop;
    private int selected = -1, group;
    private Btn apply;
    private static final String[] TABS = {"Moteur", "Suralim.", "Pont", "Pneus", "Freins", "Nitro"};
    private static final int TAB_H = 20;

    private DynoScreen(BancNetwork.State state) {
        super(Component.literal("Banc de puissance"));
        this.state = state;
    }

    /** Ouvre l'écran, ou met à jour celui qui est déjà ouvert sur ce banc (la sélection est gardée). */
    public static void show(BancNetwork.State s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DynoScreen d && d.state.pos().equals(s.pos())) {
            d.state = s;
            if (d.selected >= d.rowsOfGroup().size()) d.selected = -1;
            return;
        }
        mc.setScreen(new DynoScreen(s));
    }

    @Override
    protected void init() {
        int lines = Math.min(state.lines().size(), 4);
        h = 46 + 20 + lines * 10 + 6 + TAB_H + 4 + 4 * ROW_H + 8 + 20 + 22;
        left = (width - W) / 2;
        top = Math.max(4, (height - h) / 2);
        tabsTop = top + 46 + 20 + lines * 10 + 6;
        rowsTop = tabsTop + TAB_H + 4;
        int y = rowsTop + 4 * ROW_H + 8;
        apply = new Btn(left + 14, y, 150, 18, "Appliquer ce choix", GREEN, this::applySelected);
        addRenderableWidget(apply);
        addRenderableWidget(new Btn(left + W - 14 - 100, y, 100, 18, "Actualiser", DARK, () -> send(BancNetwork.ACT_REFRESH, 0)));
    }

    private void send(int action, int index) {
        BancNetwork.CHANNEL.sendToServer(new BancNetwork.Action(state.pos(), action, group, index));
    }

    /** Choix de l'onglet affiché, dans l'ordre reçu du serveur (l'index envoyé est la position dans cette liste). */
    private List<BancNetwork.Row> rowsOfGroup() {
        return state.rows().stream().filter(r -> r.group() == group).toList();
    }

    private void applySelected() {
        List<BancNetwork.Row> rows = rowsOfGroup();
        if (selected >= 0 && selected < rows.size() && !rows.get(selected).current()) send(BancNetwork.ACT_APPLY, selected);
    }

    private int tabW() { return (W - 20) / TABS.length; }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && my >= tabsTop && my < tabsTop + TAB_H && mx >= left + 10 && mx < left + 10 + tabW() * TABS.length) {
            int t = (int) ((mx - left - 10) / tabW());
            if (t != group) { group = t; selected = -1; }
            return true;
        }
        if (button == 0 && mx >= left + 10 && mx < left + W - 10 && my >= rowsTop && my < rowsTop + 4 * ROW_H) {
            int i = (int) ((my - rowsTop) / ROW_H);
            if (i < rowsOfGroup().size()) { selected = i; return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.fill(left - 3, top - 3, left + W + 3, top + h + 3, FRAME);
        g.fill(left, top, left + W, top + h, PANEL);
        g.pose().pushPose();
        g.pose().scale(1.6f, 1.6f, 1f);
        g.drawString(font, Component.literal("Banc de puissance").withStyle(net.minecraft.ChatFormatting.BOLD), Math.round((left + 14) / 1.6f), Math.round((top + 9) / 1.6f), WHITE, false);
        g.pose().popPose();
        g.drawString(font, state.company(), left + 14, top + 28, MUTED, false);
        g.fill(left + 10, top + 40, left + W - 10, top + 41, HOVER);

        int x = left + 14, y = top + 48;
        if (state.vehicle().isEmpty()) {
            g.drawString(font, "Aucun véhicule à proximité : garez-le près du banc.", x, y, WARN, false);
        } else {
            g.drawString(font, "Véhicule :", x, y, BLUE, false);
            g.drawString(font, font.plainSubstrByWidth(state.vehicle(), W - 100), x + font.width("Véhicule :") + 4, y, WHITE, false);
        }
        for (int i = 0; i < Math.min(state.lines().size(), 4); i++)
            g.drawString(font, font.plainSubstrByWidth(state.lines().get(i), W - 28), x, top + 46 + 20 + i * 10, TEXT, false);

        for (int t = 0; t < TABS.length; t++) {
            int tx = left + 10 + t * tabW();
            boolean on = t == group, hov = mx >= tx && mx < tx + tabW() && my >= tabsTop && my < tabsTop + TAB_H;
            g.fill(tx + 1, tabsTop, tx + tabW() - 1, tabsTop + TAB_H, on ? CYAN : hov ? HOVER : LIST);
            g.drawString(font, TABS[t], tx + tabW() / 2 - font.width(TABS[t]) / 2, tabsTop + 6, on ? WHITE : MUTED, false);
        }
        List<BancNetwork.Row> rows = rowsOfGroup();
        apply.active = selected >= 0 && selected < rows.size() && !rows.get(selected).current();
        for (int i = 0; i < Math.min(rows.size(), 4); i++) {
            BancNetwork.Row row = rows.get(i);
            int ry = rowsTop + i * ROW_H;
            boolean hov = mx >= left + 10 && mx < left + W - 10 && my >= ry && my < ry + ROW_H;
            g.fill(left + 10, ry + 1, left + W - 10, ry + ROW_H - 1, i == selected || hov ? HOVER : LIST);
            g.fill(left + 10, ry + 1, left + 13, ry + ROW_H - 1, row.current() ? GREEN : CYAN);
            g.drawString(font, row.label(), left + 18, ry + 4, WHITE, false);
            g.pose().pushPose();
            g.pose().scale(0.8f, 0.8f, 1f);
            g.drawString(font, font.plainSubstrByWidth(row.detail(), (int) ((W - 90) / 0.8f)), Math.round((left + 18) / 0.8f), Math.round((ry + 15) / 0.8f),
                    row.current() ? OK : MUTED, false);
            g.pose().popPose();
            if (row.current()) g.drawString(font, "actuel", left + W - 18 - font.width("actuel"), ry + 4, OK, false);
        }
        String msg = state.message().isEmpty() ? "Facturez le client depuis la tablette d'entreprise (FACTURES)." : state.message();
        g.drawString(font, font.plainSubstrByWidth(msg, W - 28), x, top + h - 14, state.message().isEmpty() ? MUTED : state.ok() ? OK : ALERT, false);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** Bouton plat de la charte MineNorth (dessiné ici : le mod n'embarque pas les classes graphiques du mod Entreprises). */
    private static final class Btn extends AbstractButton {
        private final Runnable action;
        private final int color;

        Btn(int x, int y, int w, int h, String label, int color, Runnable action) {
            super(x, y, w, h, Component.literal(label));
            this.action = action; this.color = color;
        }

        @Override public void onPress() { if (active) action.run(); }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int base = !active ? DISABLED : color;
            int bg = active && isHoveredOrFocused() ? lighten(base) : base;
            g.fill(getX(), getY(), getX() + width, getY() + height, bg);
            var f = Minecraft.getInstance().font;
            g.drawString(f, getMessage(), getX() + width / 2 - f.width(getMessage()) / 2, getY() + (height - 8) / 2, active ? WHITE : MUTED, false);
        }

        private static int lighten(int c) {
            int r = Math.min(255, ((c >> 16) & 255) + 35), gr = Math.min(255, ((c >> 8) & 255) + 35), b = Math.min(255, (c & 255) + 35);
            return 0xFF000000 | (r << 16) | (gr << 8) | b;
        }
    }
}
