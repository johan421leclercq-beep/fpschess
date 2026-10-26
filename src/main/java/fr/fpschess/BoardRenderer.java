package fr.fpschess;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.File;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Rank;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Construit le plateau, les pièces et l'arène dans le monde, uniquement avec des commandes vanilla
 * (fill, setblock, summon, kill) : c'est le plus stable d'une version à l'autre.
 *
 * Repère : colonne a..h le long de +X, rangée 1..8 le long de -Z (les Blancs au sud).
 * Chaque case fait CELL x CELL blocs. Le sol du plateau est à la hauteur y0.
 */
public class BoardRenderer {

    public static final int CELL = 3;
    public static final String TAG = "fpschess_piece";
    public static final String SQ_TAG_PREFIX = "fpschess_sq_";

    private static final String LIGHT = "minecraft:birch_planks";
    private static final String DARK = "minecraft:dark_oak_planks";

    private final MinecraftServer server;
    private final ServerWorld world;
    private final int x0, y0, z0;

    /** Ce qui est actuellement affiché, pour ne redessiner que les pièces qui changent. */
    private final Map<Square, Piece> shown = new HashMap<>();

    public BoardRenderer(MinecraftServer server, ServerWorld world, int x0, int y0, int z0) {
        this.server = server;
        this.world = world;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
    }

    public void run(String command) {
        ServerCommandSource src = server.getCommandSource().withWorld(world).withSilent();
        server.getCommandManager().executeWithPrefix(src, command);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x1, y1, z1, x2, y2, z2, block));
    }

    private void set(int x, int y, int z, String block) {
        run(String.format(Locale.ROOT, "setblock %d %d %d %s", x, y, z, block));
    }

    // ---------- Coordonnées ----------

    private int fileIndex(Square sq) { return sq.getFile().ordinal(); } // 0 = a
    private int rankIndex(Square sq) { return sq.getRank().ordinal(); } // 0 = rangée 1

    private int cellX(int file) { return x0 + file * CELL; }
    private int cellZ(int rank) { return z0 + (7 - rank) * CELL; }

    public ServerWorld world() {
        return world;
    }

    public String dimension() {
        return world.getRegistryKey().getValue().toString();
    }

    /** Renvoie la case d'échecs correspondant à un bloc du sol, ou null si hors plateau. */
    public Square squareAt(BlockPos pos) {
        if (pos.getY() != y0) return null;
        int dx = pos.getX() - x0;
        int dz = pos.getZ() - z0;
        int size = 8 * CELL;
        if (dx < 0 || dz < 0 || dx >= size || dz >= size) return null;
        int f = dx / CELL;
        int r = 7 - (dz / CELL);
        return Square.encode(Rank.values()[r], File.values()[f]);
    }

    // ---------- Plateau ----------

    public void buildBoard() {
        int size = 8 * CELL;
        // Dégage l'espace, puis pose le cadre (2 blocs de large) autour du plateau
        fill(x0 - 2, y0 + 1, z0 - 2, x0 + size + 1, y0 + 6, z0 + size + 1, "minecraft:air");
        fill(x0 - 2, y0, z0 - 2, x0 + size + 1, y0, z0 + size + 1, "minecraft:polished_blackstone_bricks");
        fill(x0 - 1, y0, z0 - 1, x0 + size, y0, z0 + size, "minecraft:stripped_dark_oak_log hollow");
        // Poteaux d'angle avec lanterne
        int[] xs = {x0 - 2, x0 + size + 1};
        int[] zs = {z0 - 2, z0 + size + 1};
        for (int cx : xs) {
            for (int cz : zs) {
                fill(cx, y0 + 1, cz, cx, y0 + 2, cz, "minecraft:stripped_dark_oak_log[axis=y]");
                set(cx, y0 + 3, cz, "minecraft:lantern");
            }
        }
        drawFloor(Map.of());
        buildLabels();
    }

    /** Lettres a..h et chiffres 1..8 sur les quatre côtés du cadre. */
    private void buildLabels() {
        int size = 8 * CELL;
        for (int f = 0; f < 8; f++) {
            String letter = String.valueOf((char) ('a' + f));
            double x = cellX(f) + CELL / 2.0;
            label(x, z0 + size + 1.0, letter);
            label(x, z0 - 1.0, letter);
        }
        for (int r = 0; r < 8; r++) {
            String number = String.valueOf(r + 1);
            double z = cellZ(r) + CELL / 2.0;
            label(x0 - 1.0, z, number);
            label(x0 + size + 1.0, z, number);
        }
    }

    private void label(double x, double z, String text) {
        run(String.format(Locale.ROOT,
                "summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"fpschess_label\"],"
              + "text:'{\"text\":\"%s\",\"color\":\"gold\",\"bold\":true}',billboard:\"center\",background:0,"
              + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
              + "translation:[0f,0f,0f],scale:[3f,3f,3f]}}",
                x, y0 + 1.4, z, text));
    }

    // ---------- Protection ----------

    /** Vrai si le bloc fait partie du plateau (cadre compris) ou de l'arène. */
    public boolean isProtected(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int size = 8 * CELL;
        boolean inBoard = x >= x0 - 2 && x <= x0 + size + 1 && z >= z0 - 2 && z <= z0 + size + 1
                && y >= y0 && y <= y0 + 8;
        int ax = arenaX();
        boolean inArena = x >= ax && x <= ax + ARENA_SIZE - 1 && z >= z0 && z <= z0 + ARENA_SIZE - 1
                && y >= y0 && y <= y0 + ARENA_HEIGHT;
        return inBoard || inArena;
    }

    // ---------- Effets ----------

    /** Son et particules sur une case : coup normal ou prise. */
    public void effect(Square sq, boolean capture) {
        double x = cellX(fileIndex(sq)) + CELL / 2.0;
        double z = cellZ(rankIndex(sq)) + CELL / 2.0;
        run(String.format(Locale.ROOT, "playsound %s block @a %.1f %.1f %.1f 2 1",
                capture ? "minecraft:block.anvil.land" : "minecraft:block.stone.place", x, y0 + 1.5, z));
        run(String.format(Locale.ROOT, "particle minecraft:%s %.1f %.1f %.1f 0.8 0.5 0.8 0.02 %d",
                capture ? "explosion" : "poof", x, y0 + 1.2, z, capture ? 3 : 12));
    }

    /** Dessine le sol. 'overrides' permet de colorer certaines cases (sélection, coups possibles). */
    public void drawFloor(Map<Square, String> overrides) {
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Square sq = Square.encode(Rank.values()[r], File.values()[f]);
                String block = overrides.getOrDefault(sq, ((f + r) % 2 == 0) ? DARK : LIGHT); // a1 est sombre
                int x = cellX(f);
                int z = cellZ(r);
                fill(x, y0, z, x + CELL - 1, y0, z + CELL - 1, block);
            }
        }
    }

    // ---------- Pièces ----------

    /** Un morceau de statue : taille, centre (cx, cz) par rapport au centre de la case, hauteur y depuis le sol. */
    private record Part(double sx, double sy, double sz, double cx, double y, double cz, int kind) {}

    private static final int MAIN = 0, TRIM = 1, GOLD = 2;

    private static Part p(double sx, double sy, double sz, double cx, double y, double cz) {
        return new Part(sx, sy, sz, cx, y, cz, MAIN);
    }

    private static Part t(double sx, double sy, double sz, double cx, double y, double cz) {
        return new Part(sx, sy, sz, cx, y, cz, TRIM);
    }

    private static Part g(double sx, double sy, double sz, double cx, double y, double cz) {
        return new Part(sx, sy, sz, cx, y, cz, GOLD);
    }

    /** Dessin de chaque pièce. cz négatif = vers l'avant (le museau du cavalier). */
    private static List<Part> parts(PieceType type) {
        return switch (type) {

            case PAWN -> List.of(
                t(1.6, 0.15, 1.6, 0, 0, 0),
                p(1.4, 0.15, 1.4, 0, 0.15, 0),
                p(0.55, 0.5, 0.55, 0, 0.3, 0),
                t(0.9, 0.12, 0.9, 0, 0.8, 0),
                p(0.4, 0.3, 0.4, 0, 0.92, 0),
                t(0.75, 0.1, 0.75, 0, 1.22, 0),
                p(0.6, 0.15, 0.6, 0, 1.32, 0),
                p(0.85, 0.5, 0.85, 0, 1.47, 0),
                p(0.6, 0.15, 0.6, 0, 1.97, 0));

            case ROOK -> List.of(
                t(2.0, 0.15, 2.0, 0, 0, 0),
                p(1.8, 0.15, 1.8, 0, 0.15, 0),
                p(1.3, 0.9, 1.3, 0, 0.3, 0),
                t(1.5, 0.12, 1.5, 0, 1.2, 0),
                p(1.1, 0.7, 1.1, 0, 1.32, 0),
                t(1.7, 0.18, 1.7, 0, 2.02, 0),
                p(1.7, 0.25, 1.7, 0, 2.2, 0),
                // créneaux : 4 coins + 4 milieux
                p(0.4, 0.35, 0.4, 0.65, 2.45, 0.65),
                p(0.4, 0.35, 0.4, -0.65, 2.45, 0.65),
                p(0.4, 0.35, 0.4, 0.65, 2.45, -0.65),
                p(0.4, 0.35, 0.4, -0.65, 2.45, -0.65),
                p(0.4, 0.35, 0.4, 0, 2.45, 0.65),
                p(0.4, 0.35, 0.4, 0, 2.45, -0.65),
                p(0.4, 0.35, 0.4, 0.65, 2.45, 0),
                p(0.4, 0.35, 0.4, -0.65, 2.45, 0));

            case KNIGHT -> List.of(
                t(1.9, 0.15, 1.9, 0, 0, 0),
                p(1.7, 0.15, 1.7, 0, 0.15, 0),
                p(1.1, 0.7, 1.1, 0, 0.3, 0.15),
                p(0.95, 0.6, 0.95, 0, 1.0, 0.1),
                p(0.85, 0.5, 0.85, 0, 1.6, -0.05),   // cou
                p(0.8, 0.5, 0.8, 0, 2.1, -0.2),
                p(0.8, 0.5, 0.9, 0, 2.6, -0.3),      // tête
                p(0.55, 0.4, 0.6, 0, 2.6, -0.95),    // museau
                t(0.6, 0.2, 0.2, 0, 2.6, -1.28),     // naseaux
                t(0.3, 0.5, 0.25, 0, 1.6, 0.4),      // crinière
                t(0.3, 0.5, 0.25, 0, 2.1, 0.3),
                t(0.3, 0.5, 0.25, 0, 2.6, 0.2),
                p(0.2, 0.3, 0.2, 0.25, 3.1, -0.1),   // oreilles
                p(0.2, 0.3, 0.2, -0.25, 3.1, -0.1),
                g(0.12, 0.12, 0.12, 0.42, 2.9, -0.55), // yeux
                g(0.12, 0.12, 0.12, -0.42, 2.9, -0.55));

            case BISHOP -> List.of(
                t(1.9, 0.15, 1.9, 0, 0, 0),
                p(1.6, 0.15, 1.6, 0, 0.15, 0),
                p(1.1, 0.5, 1.1, 0, 0.3, 0),
                t(1.3, 0.12, 1.3, 0, 0.8, 0),
                p(0.75, 0.9, 0.75, 0, 0.92, 0),
                t(1.15, 0.15, 1.15, 0, 1.82, 0),
                p(0.7, 0.3, 0.7, 0, 1.97, 0),
                p(0.85, 0.4, 0.85, 0, 2.27, 0),      // mitre
                t(0.2, 0.5, 0.95, 0, 2.25, 0),       // fente de la mitre
                p(0.6, 0.4, 0.6, 0, 2.67, 0),
                p(0.35, 0.3, 0.35, 0, 3.07, 0),
                g(0.28, 0.28, 0.28, 0, 3.37, 0));

            case QUEEN -> List.of(
                t(2.1, 0.15, 2.1, 0, 0, 0),
                p(1.9, 0.15, 1.9, 0, 0.15, 0),
                p(1.4, 0.5, 1.4, 0, 0.3, 0),
                t(1.6, 0.12, 1.6, 0, 0.8, 0),
                p(1.0, 0.9, 1.0, 0, 0.92, 0),
                t(1.25, 0.1, 1.25, 0, 1.82, 0),
                p(0.8, 0.9, 0.8, 0, 1.92, 0),
                t(1.4, 0.15, 1.4, 0, 2.82, 0),
                p(1.0, 0.35, 1.0, 0, 2.97, 0),
                // couronne : 4 pointes dorées
                p(0.3, 0.45, 0.3, 0.5, 3.32, 0.5),
                p(0.3, 0.45, 0.3, -0.5, 3.32, 0.5),
                p(0.3, 0.45, 0.3, 0.5, 3.32, -0.5),
                p(0.3, 0.45, 0.3, -0.5, 3.32, -0.5),
                g(0.2, 0.2, 0.2, 0.5, 3.77, 0.5),
                g(0.2, 0.2, 0.2, -0.5, 3.77, 0.5),
                g(0.2, 0.2, 0.2, 0.5, 3.77, -0.5),
                g(0.2, 0.2, 0.2, -0.5, 3.77, -0.5),
                g(0.4, 0.4, 0.4, 0, 3.32, 0));

            case KING -> List.of(
                t(2.1, 0.15, 2.1, 0, 0, 0),
                p(1.9, 0.15, 1.9, 0, 0.15, 0),
                p(1.5, 0.5, 1.5, 0, 0.3, 0),
                t(1.7, 0.12, 1.7, 0, 0.8, 0),
                p(1.1, 0.9, 1.1, 0, 0.92, 0),
                t(1.35, 0.1, 1.35, 0, 1.82, 0),
                p(0.9, 0.9, 0.9, 0, 1.92, 0),
                t(1.5, 0.15, 1.5, 0, 2.82, 0),
                p(1.1, 0.45, 1.1, 0, 2.97, 0),
                g(1.2, 0.12, 1.2, 0, 3.12, 0),       // bandeau doré de la couronne
                p(0.8, 0.3, 0.8, 0, 3.42, 0),
                g(0.3, 0.9, 0.3, 0, 3.72, 0),        // croix
                g(0.9, 0.3, 0.3, 0, 4.05, 0));

            default -> List.of();
        };
    }

    private static double hitboxHeight(PieceType type) {
        return switch (type) {
            case PAWN -> 2.3;
            case KING -> 4.7;
            case QUEEN -> 3.9;
            default -> 3.3;
        };
    }

    /** Efface toutes les pièces. */
    public void clearPieces() {
        run("kill @e[tag=" + TAG + "]");
        shown.clear();
    }

    /** Met l'affichage à jour : seules les cases qui ont changé sont redessinées. */
    public void renderPieces(Board board) {
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) continue;
            Piece now = board.getPiece(sq);
            Piece before = shown.getOrDefault(sq, Piece.NONE);
            if (now == before) continue;

            if (before != Piece.NONE) {
                run("kill @e[tag=" + SQ_TAG_PREFIX + sq.toString().toLowerCase(Locale.ROOT) + "]");
            }
            if (now != Piece.NONE) {
                summonPiece(now, sq);
                shown.put(sq, now);
            } else {
                shown.remove(sq);
            }
        }
    }

    private void summonPiece(Piece piece, Square sq) {
        double centerX = cellX(fileIndex(sq)) + CELL / 2.0;
        double centerZ = cellZ(rankIndex(sq)) + CELL / 2.0;
        double floor = y0 + 1;

        boolean white = piece.getPieceSide() == Side.WHITE;
        String main = white ? "minecraft:white_concrete" : "minecraft:black_concrete";
        String trim = white ? "minecraft:light_gray_concrete" : "minecraft:gray_concrete";
        double dir = white ? 1 : -1; // les Blancs regardent le nord (-Z), les Noirs le sud
        String sqTag = SQ_TAG_PREFIX + sq.toString().toLowerCase(Locale.ROOT);
        String tags = "Tags:[\"" + TAG + "\",\"" + sqTag + "\"]";

        for (Part part : parts(piece.getPieceType())) {
            String block = switch (part.kind()) {
                case GOLD -> "minecraft:gold_block";
                case TRIM -> trim;
                default -> main;
            };
            double tx = part.cx() * dir - part.sx() / 2;
            double tz = part.cz() * dir - part.sz() / 2;
            String nbt = String.format(Locale.ROOT,
                    "{%s,block_state:{Name:\"%s\"},transformation:{left_rotation:[0f,0f,0f,1f],"
                  + "right_rotation:[0f,0f,0f,1f],translation:[%.3ff,0f,%.3ff],scale:[%.3ff,%.3ff,%.3ff]}}",
                    tags, block, tx, tz, part.sx(), part.sy(), part.sz());
            run(String.format(Locale.ROOT, "summon minecraft:block_display %.3f %.3f %.3f %s",
                    centerX, floor + part.y(), centerZ, nbt));
        }

        // Zone cliquable invisible autour de la pièce
        run(String.format(Locale.ROOT,
                "summon minecraft:interaction %.3f %.3f %.3f {%s,width:1.8f,height:%.1ff,response:1b}",
                centerX, floor, centerZ, tags, hitboxHeight(piece.getPieceType())));
    }

    // ---------- Arène de duel (à l'est du plateau) ----------

    public static final int ARENA_SIZE = 37;   // côté de l'arène (impair : il y a un centre)
    public static final int ARENA_HEIGHT = 11; // hauteur de la salle

    public int arenaX() { return x0 + 8 * CELL + 8; }

    /** Spawn 0 = côté ouest (regarde l'est), spawn 1 = côté est (regarde l'ouest). {x, y, z, yaw} */
    public double[] arenaSpawn(int index) {
        double z = z0 + ARENA_SIZE / 2 + 0.5;
        return index == 0
                ? new double[]{arenaX() + 4.5, y0 + 1, z, -90}
                : new double[]{arenaX() + ARENA_SIZE - 4.5, y0 + 1, z, 90};
    }

    /** Matériau du sol d'une case de l'arène (coordonnées locales). */
    private static String arenaFloor(int x, int z) {
        int c = ARENA_SIZE / 2;
        int s = ARENA_SIZE;
        if (x >= 3 && x <= 5 && z >= c - 1 && z <= c + 1) return "minecraft:red_concrete";            // départ attaquant
        if (x >= s - 6 && x <= s - 4 && z >= c - 1 && z <= c + 1) return "minecraft:blue_concrete";   // départ défenseur
        double d = Math.hypot(x - c, z - c);
        if (d <= 3.5) return "minecraft:chiseled_polished_blackstone";
        if (d >= 6.5 && d <= 7.8) return "minecraft:red_terracotta";
        if (d >= 11.5 && d <= 12.6) return "minecraft:red_nether_bricks";
        return ((x + z) % 2 == 0) ? "minecraft:deepslate_tiles" : "minecraft:polished_deepslate";
    }

    /** Colisée de pierre sombre : sol en damier avec anneaux, colonnes, poutres, lanternes, bannières. */
    public void buildArena() {
        int ax = arenaX(), y = y0, z = z0, s = ARENA_SIZE, e = s - 1, h = ARENA_HEIGHT, c = s / 2;

        // 1. Coque fermée (murs + plafond), intérieur vidé
        fill(ax, y, z, ax + e, y + h, z + e, "minecraft:stone_bricks hollow");

        // 2. Sol
        fill(ax, y, z, ax + e, y, z + e, "minecraft:deepslate_tiles");
        for (int x = 0; x < s; x++) {
            for (int dz = 0; dz < s; dz++) {
                String block = arenaFloor(x, dz);
                if (!block.equals("minecraft:deepslate_tiles")) {
                    set(ax + x, y, z + dz, block);
                }
            }
        }

        // 3. Murs : soubassement, frise, plafond en briques noires
        fill(ax, y + 1, z, ax + e, y + 1, z + e, "minecraft:polished_blackstone_bricks hollow");
        fill(ax, y + h - 2, z, ax + e, y + h - 2, z + e, "minecraft:chiseled_stone_bricks hollow");
        fill(ax, y + h, z, ax + e, y + h, z + e, "minecraft:polished_blackstone_bricks");

        // 4. Colonnes en bois le long des murs
        int[] cols = {6, 12, 18, 24, 30};
        for (int col : cols) {
            fill(ax + 1, y + 1, z + col, ax + 1, y + h - 2, z + col, "minecraft:stripped_dark_oak_log[axis=y]");
            fill(ax + e - 1, y + 1, z + col, ax + e - 1, y + h - 2, z + col, "minecraft:stripped_dark_oak_log[axis=y]");
            fill(ax + col, y + 1, z + 1, ax + col, y + h - 2, z + 1, "minecraft:stripped_dark_oak_log[axis=y]");
            fill(ax + col, y + 1, z + e - 1, ax + col, y + h - 2, z + e - 1, "minecraft:stripped_dark_oak_log[axis=y]");
        }

        // 5. Poutres au plafond et lanternes suspendues
        for (int bz : new int[]{6, 18, 30}) {
            fill(ax + 1, y + h - 1, z + bz, ax + e - 1, y + h - 1, z + bz, "minecraft:dark_oak_log[axis=x]");
            for (int bx : new int[]{9, 18, 27}) {
                set(ax + bx, y + h - 2, z + bz, "minecraft:lantern[hanging=true]");
            }
        }

        // 6. Bannières : rouge côté attaquant (ouest), bleue côté défenseur (est)
        for (int bz : new int[]{9, 15, 21, 27}) {
            set(ax + 1, y + 5, z + bz, "minecraft:red_wall_banner[facing=east]");
            set(ax + e - 1, y + 5, z + bz, "minecraft:blue_wall_banner[facing=west]");
        }

        // 7. Couvertures : colonnes en ruine {x, z, hauteur}
        int[][] ruins = {{10, 8, 3}, {25, 8, 2}, {10, 27, 2}, {25, 27, 3}, {16, 12, 2}, {19, 24, 2}};
        for (int[] r : ruins) {
            String brick = r[2] == 3 ? "minecraft:mossy_stone_bricks" : "minecraft:cracked_stone_bricks";
            fill(ax + r[0], y + 1, z + r[1], ax + r[0] + 1, y + r[2], z + r[1] + 1, brick);
            fill(ax + r[0], y + r[2] + 1, z + r[1], ax + r[0] + 1, y + r[2] + 1, z + r[1] + 1,
                    "minecraft:stone_brick_slab[type=bottom]");
        }

        // Estrade centrale en gradins
        fill(ax + c - 2, y + 1, z + c - 2, ax + c + 2, y + 1, z + c + 2,
                "minecraft:polished_blackstone_brick_slab[type=bottom]");
        fill(ax + c - 1, y + 1, z + c - 1, ax + c + 1, y + 1, z + c + 1, "minecraft:polished_blackstone_bricks");
        set(ax + c, y + 2, z + c, "minecraft:gold_block");

        // 8. Lumière invisible en grille : aucun endroit à 0 de luminosité, aucun monstre ne peut apparaître
        for (int lx = 2; lx < s; lx += 4) {
            for (int lz = 2; lz < s; lz += 4) {
                set(ax + lx, y + 5, z + lz, "minecraft:light[level=15]");
            }
        }
    }

    /** Supprime tout ce qui n'est pas un joueur dans l'arène (objets, orbes, monstres). */
    public void clearArenaEntities() {
        run(String.format(Locale.ROOT,
                "kill @e[type=!minecraft:player,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]",
                arenaX(), y0, z0, ARENA_SIZE, ARENA_HEIGHT + 3, ARENA_SIZE));
    }

    // ---------- Nettoyage ----------

    public void removeAll() {
        clearPieces();
        run("kill @e[tag=fpschess_dummy]");
        run("kill @e[tag=fpschess_label]");
        fill(arenaX(), y0, z0, arenaX() + ARENA_SIZE - 1, y0 + ARENA_HEIGHT, z0 + ARENA_SIZE - 1, "minecraft:air");
        int size = 8 * CELL;
        fill(x0 - 2, y0 + 1, z0 - 2, x0 + size + 1, y0 + 6, z0 + size + 1, "minecraft:air");
    }
}
