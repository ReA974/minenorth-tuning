package fr.minenorth.banc.client;

import fr.minenorth.banc.NeonService;
import fr.minenorth.banc.network.BancNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Onglet « Néons » du banc (charte MineNorth, coins arrondis) : couleur (nuancier + barre de teinte), mode d'éclairage, pose / réglage / retrait.
 * Tant que l'écran est ouvert, le véhicule garé près du banc affiche les néons choisis en direct (aperçu local, rien n'est envoyé avant
 * « Poser » / « Appliquer »). Le serveur revérifie tout (personnel de l'entreprise, véhicule près du banc, kit dans l'inventaire).
 */
public class NeonScreen extends Screen {
    private static final int W = 300, H = 300, FRAME = 0xFF0E0E10, PANEL = 0xFF161048, LIST = 0xFF0E0A34, HOVER = 0xFF2E2480,
            CYAN = 0xFF20AAEB, PINK = 0xFFC83CF0, GREEN = 0xFF1E9E52, DARK = 0xFF4A3CB4, TEXT = 0xFFCFE3FF, MUTED = 0xFF8FA8E0,
            WARN = 0xFFFFE066, ALERT = 0xFFFF6A9A, OK = 0xFF6CFF9A, WHITE = 0xFFFFFFFF, DISABLED = 0xFF2A2468, RED = 0xFFB4304A;
    /** Nuancier : couleurs franches qui rendent bien en lumière additive. */
    private static final int[] PALETTE = {0x20AAEB, 0xC83CF0, 0x1EE26A, 0xFF2040, 0xFF7A1A, 0xFFE040,
            0xF0F4FF, 0x2050FF, 0x8A2BE2, 0x20F0D0, 0xFF30A0, 0xA0FF30};
    private static final int SW = 40, SH = 18, GAP = 4;

    private BancNetwork.State state;
    private int left, top, color, mode;
    /** Correction de forme du modèle en cours de réglage (largeur, avant, arrière, hauteur, en blocs). */
    private final float[] fit = new float[4];
    private static final String[] FIT_NAMES = {"Largeur", "Avant", "Arrière", "Hauteur"};
    private static final float FIT_STEP = 0.05f;
    private boolean draggingHue;

    NeonScreen(BancNetwork.State state) {
        super(Component.literal("Néons sous caisse"));
        this.state = state;
        BancNetwork.NeonSync n = state.neon();
        color = n.installed() ? n.color() : PALETTE[0];
        mode = n.installed() ? n.mode() : NeonService.MODE_FIXED;
        readFit(n);
    }

    private void readFit(BancNetwork.NeonSync n) {
        fit[0] = n.width(); fit[1] = n.front(); fit[2] = n.rear(); fit[3] = n.height();
    }

    /** Nouvel état reçu du serveur (après une action) : le choix en cours est gardé. */
    void update(BancNetwork.State s) {
        boolean fitSaved = !fitChanged();
        state = s;
        if (fitSaved) readFit(s.neon());
    }

    private boolean fitChanged() {
        BancNetwork.NeonSync n = state.neon();
        return Math.abs(n.width() - fit[0]) > 1e-3 || Math.abs(n.front() - fit[1]) > 1e-3 || Math.abs(n.rear() - fit[2]) > 1e-3 || Math.abs(n.height() - fit[3]) > 1e-3;
    }

    BancNetwork.State state() { return state; }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(4, (height - H) / 2);
    }

    @Override
    public void tick() {
        ClientNeons.preview(state.vehicleId() < 0 ? null : new BancNetwork.NeonSync(state.vehicleId(), true, true, color, mode,
                fit[0], fit[1], fit[2], fit[3]));
    }

    @Override
    public void removed() {
        ClientNeons.preview(null);
        super.removed();
    }

    // ------------------------------------------------------------------ géométrie

    private int swX(int i) { return left + 14 + (i % 6) * (SW + GAP); }
    private int swY(int i) { return top + 88 + (i / 6) * (SH + GAP); }
    private int hueX0() { return left + 14; }
    private int hueX1() { return left + W - 14; }
    private int hueY() { return top + 136; }
    private int modeW() { return (W - 28 - 3 * GAP) / 4; }
    private int modeX(int i) { return left + 14 + i * (modeW() + GAP); }
    private int modeY() { return top + 166; }
    private int fitY() { return top + 212; }
    private int fitBtnY() { return top + 234; }
    private int btnY() { return top + 260; }

    private boolean in(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    private boolean installed() { return state.neon().installed(); }

    private boolean changed() { return !installed() || state.neon().color() != color || state.neon().mode() != mode; }

    private boolean canApply() { return state.vehicleId() >= 0 && changed() && (installed() || state.neonKits() > 0); }

    // ------------------------------------------------------------------ actions

    private void send(int op) {
        BancNetwork.CHANNEL.sendToServer(new BancNetwork.NeonApply(state.pos(), op, color, mode));
    }

    private static float clampFit(float v) { return Math.round(Math.max(-1.5f, Math.min(1.5f, v)) * 100) / 100f; }

    private void setHue(double mx) {
        float h = (float) Mth.clamp((mx - hueX0()) / (double) (hueX1() - hueX0()), 0, 0.999);
        color = Mth.hsvToRgb(h, 1f, 1f) & 0xFFFFFF;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        for (int i = 0; i < PALETTE.length; i++) if (in(mx, my, swX(i), swY(i), SW, SH)) { color = PALETTE[i]; return true; }
        if (in(mx, my, hueX0(), hueY() - 2, hueX1() - hueX0(), 14)) { draggingHue = true; setHue(mx); return true; }
        for (int i = 0; i < NeonService.MODE_NAMES.length; i++) if (in(mx, my, modeX(i), modeY(), modeW(), SH)) { mode = i; return true; }
        for (int i = 0; i < 4; i++) {
            int bx = modeX(i);
            if (in(mx, my, bx, fitY(), 14, 14)) { fit[i] = clampFit(fit[i] - FIT_STEP); return true; }
            if (in(mx, my, bx + modeW() - 14, fitY(), 14, 14)) { fit[i] = clampFit(fit[i] + FIT_STEP); return true; }
        }
        if (in(mx, my, left + 14, fitBtnY(), 150, SH) && state.vehicleId() >= 0 && fitChanged()) {
            BancNetwork.CHANNEL.sendToServer(new BancNetwork.NeonFit(state.pos(), fit[0], fit[1], fit[2], fit[3]));
            return true;
        }
        if (in(mx, my, left + 170, fitBtnY(), W - 184, SH)) { java.util.Arrays.fill(fit, 0f); return true; }
        int y = btnY();
        if (in(mx, my, left + 14, y, 126, SH) && canApply()) { send(BancNetwork.NEON_INSTALL); return true; }
        if (in(mx, my, left + 146, y, 66, SH) && installed()) { send(BancNetwork.NEON_REMOVE); return true; }
        if (in(mx, my, left + W - 14 - 72, y, 72, SH)) { Minecraft.getInstance().setScreen(new DynoScreen(state)); return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingHue) { setHue(mx); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingHue = false;
        return super.mouseReleased(mx, my, button);
    }

    // ------------------------------------------------------------------ dessin

    /** Rectangle aux coins arrondis (rayon r en pixels GUI), comme les autres écrans MineNorth. */
    private static void rfill(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int c) {
        r = Math.max(0, Math.min(r, Math.min(x2 - x1, y2 - y1) / 2));
        g.fill(x1, y1 + r, x2, y2 - r, c);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * (double) r - dy * dy));
            g.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, c);
            g.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, c);
        }
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 35), gr = Math.min(255, ((c >> 8) & 255) + 35), b = Math.min(255, (c & 255) + 35);
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }

    private void button(GuiGraphics g, int x, int y, int w, String label, int color, boolean active, int mx, int my) {
        int bg = !active ? DISABLED : in(mx, my, x, y, w, SH) ? lighten(color) : color;
        rfill(g, x, y, x + w, y + SH, 6, bg);
        g.drawString(font, label, x + w / 2 - font.width(label) / 2, y + 5, active ? WHITE : MUTED, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // pas d'assombrissement du monde : l'aperçu des néons sur le véhicule reste bien visible derrière l'écran
        rfill(g, left - 3, top - 3, left + W + 3, top + H + 3, 11, FRAME);
        rfill(g, left, top, left + W, top + H, 9, PANEL);
        g.pose().pushPose();
        g.pose().scale(1.6f, 1.6f, 1f);
        g.drawString(font, Component.literal("Néons sous caisse").withStyle(ChatFormatting.BOLD), Math.round((left + 14) / 1.6f), Math.round((top + 9) / 1.6f), WHITE, false);
        g.pose().popPose();
        g.drawString(font, state.company(), left + 14, top + 28, MUTED, false);
        rfill(g, left + 10, top + 40, left + W - 10, top + 42, 1, HOVER);

        int x = left + 14;
        if (state.vehicle().isEmpty()) {
            g.drawString(font, "Aucun véhicule à proximité : garez-le près du banc.", x, top + 48, WARN, false);
        } else {
            g.drawString(font, "Véhicule :", x, top + 48, CYAN, false);
            g.drawString(font, font.plainSubstrByWidth(state.vehicle(), W - 100), x + font.width("Véhicule :") + 4, top + 48, WHITE, false);
        }
        String status = !installed() ? "Aucun néon posé  •  kits néons sur vous : " + state.neonKits()
                : "Néons posés (" + (state.neon().on() ? "allumés" : "éteints") + ")  •  touche " + NeonKeys.NEONS.getTranslatedKeyMessage().getString() + " au volant";
        g.drawString(font, status, x, top + 60, installed() ? OK : state.neonKits() > 0 ? TEXT : WARN, false);

        // couleur
        g.drawString(font, "Couleur", x, top + 76, CYAN, false);
        rfill(g, x + 46, top + 74, x + 86, top + 85, 5, 0xFF000000 | color);
        g.drawString(font, String.format("#%06X", color), x + 92, top + 76, MUTED, false);
        for (int i = 0; i < PALETTE.length; i++) {
            int sx = swX(i), sy = swY(i);
            boolean sel = PALETTE[i] == color, hov = in(mx, my, sx, sy, SW, SH);
            if (sel || hov) rfill(g, sx - 2, sy - 2, sx + SW + 2, sy + SH + 2, 8, sel ? WHITE : HOVER);
            rfill(g, sx, sy, sx + SW, sy + SH, 7, 0xFF000000 | PALETTE[i]);
        }
        // barre de teinte (toutes les couleurs saturées)
        int hx0 = hueX0(), hx1 = hueX1(), hy = hueY();
        rfill(g, hx0 - 2, hy - 2, hx1 + 2, hy + 12, 6, LIST);
        for (int px = hx0 + 2; px < hx1 - 2; px++) {
            int c = Mth.hsvToRgb((px - hx0) / (float) (hx1 - hx0), 1f, 1f);
            g.fill(px, hy + 1, px + 1, hy + 9, 0xFF000000 | c);
        }
        g.drawString(font, "Teinte libre : cliquez ou glissez sur la barre", x, hy + 15, MUTED, false);

        // mode
        g.drawString(font, "Mode", x, modeY() - 11, CYAN, false);
        for (int i = 0; i < NeonService.MODE_NAMES.length; i++) {
            int bx = modeX(i);
            boolean on = i == mode, hov = in(mx, my, bx, modeY(), modeW(), SH);
            rfill(g, bx, modeY(), bx + modeW(), modeY() + SH, 7, on ? PINK : hov ? HOVER : LIST);
            String name = NeonService.MODE_NAMES[i];
            g.drawString(font, name, bx + modeW() / 2 - font.width(name) / 2, modeY() + 5, on ? WHITE : TEXT, false);
        }

        // forme du modèle : corrections +/- (aperçu en direct), enregistrées pour tous les exemplaires du modèle
        g.drawString(font, "Forme (pour tout ce modèle)", x, fitY() - 22, CYAN, false);
        for (int i = 0; i < 4; i++) {
            int bx = modeX(i), fy = fitY();
            g.drawString(font, FIT_NAMES[i], bx + modeW() / 2 - font.width(FIT_NAMES[i]) / 2, fy - 10, MUTED, false);
            rfill(g, bx, fy, bx + modeW(), fy + 14, 6, LIST);
            rfill(g, bx, fy, bx + 14, fy + 14, 6, in(mx, my, bx, fy, 14, 14) ? HOVER : DARK);
            rfill(g, bx + modeW() - 14, fy, bx + modeW(), fy + 14, 6, in(mx, my, bx + modeW() - 14, fy, 14, 14) ? HOVER : DARK);
            g.drawString(font, "-", bx + 5, fy + 3, WHITE, false);
            g.drawString(font, "+", bx + modeW() - 10, fy + 3, WHITE, false);
            String v = String.format(java.util.Locale.ROOT, "%+.2f", fit[i]);
            g.drawString(font, v, bx + modeW() / 2 - font.width(v) / 2, fy + 3, Math.abs(fit[i]) < 1e-3 ? MUTED : WHITE, false);
        }
        button(g, left + 14, fitBtnY(), 150, "Enregistrer la forme", CYAN, state.vehicleId() >= 0 && fitChanged(), mx, my);
        button(g, left + 170, fitBtnY(), W - 184, "Forme auto (0)", DARK, true, mx, my);

        // boutons
        int y = btnY();
        button(g, left + 14, y, 126, installed() ? "Appliquer le réglage" : "Poser (1 kit néons)", GREEN, canApply(), mx, my);
        button(g, left + 146, y, 66, "Retirer", RED, installed(), mx, my);
        button(g, left + W - 14 - 72, y, 72, "‹ Banc", DARK, true, mx, my);

        String msg = state.message().isEmpty() ? "Aperçu en direct sur le véhicule. Facturez le client (FACTURES)." : state.message();
        g.drawString(font, font.plainSubstrByWidth(msg, W - 28), x, top + H - 16, state.message().isEmpty() ? MUTED : state.ok() ? OK : ALERT, false);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
