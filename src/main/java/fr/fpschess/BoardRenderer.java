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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Construit le plateau et les pièces dans le monde avec de vrais blocs, uniquement via des commandes
 * vanilla (fill, setblock, summon, kill).
 *
 * Repère : colonne a..h le long de +X, rangée 1..8 le long de -Z (les Blancs au sud).
 * Chaque case fait CELL x CELL blocs, le sol du plateau est à la hauteur y0.
 * Les pièces tiennent dans la zone locale x,z = 1..5 de chaque case (le bord 0 reste libre pour la lumière).
 * Le duel se joue directement sur le plateau.
 */
public class BoardRenderer {

    public static final int CELL = 7;
    public static final String SQ_TAG_PREFIX = "fpschess_sq_";

    /** Hauteur maximale d'une pièce (le roi fait 16 blocs). */
    private static final int PIECE_TOP = 17;

    private static final String LIGHT = "minecraft:birch_planks";
    private static final String DARK = "minecraft:dark_oak_planks";

    private final MinecraftServer server;
    private final ServerWorld world;
    private final int x0, y0, z0;

    /** Ce qui est actuellement construit, pour ne refaire que les pièces qui changent. */
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

    /** Comme fill, mais découpe en tranches : une commande fill est limitée à 32 768 blocs. */
    private void fillBig(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        int slice = Math.max(1, 30000 / ((y2 - y1 + 1) * (z2 - z1 + 1)));
        for (int x = x1; x <= x2; x += slice) {
            fill(x, y1, z1, Math.min(x + slice - 1, x2), y2, z2, block);
        }
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

    public int floorY() {
        return y0;
    }

    public String dimension() {
        return world.getRegistryKey().getValue().toString();
    }

    /** Case d'échecs correspondant à un bloc du plateau ou d'une pièce, ou null si hors plateau. */
    public Square squareAt(BlockPos pos) {
        if (pos.getY() < y0 || pos.getY() > y0 + PIECE_TOP) return null;
        int dx = pos.getX() - x0;
        int dz = pos.getZ() - z0;
        int size = 8 * CELL;
        if (dx < 0 || dz < 0 || dx >= size || dz >= size) return null;
        return Square.encode(Rank.values()[7 - (dz / CELL)], File.values()[dx / CELL]);
    }

    /** Point d'apparition au centre d'une case, tourné vers une autre case. {x, y, z, yaw} */
    public double[] squareSpawn(Square sq, Square toward) {
        double x = cellX(fileIndex(sq)) + CELL / 2.0;
        double z = cellZ(rankIndex(sq)) + CELL / 2.0;
        int dx = cellX(fileIndex(toward)) - cellX(fileIndex(sq));
        int dz = cellZ(rankIndex(toward)) - cellZ(rankIndex(sq));
        double yaw = (dx == 0 && dz == 0) ? 0 : Math.toDegrees(Math.atan2(-dx, dz));
        return new double[]{x, y0 + 1, z, yaw};
    }

    /**
     * Camp d'un joueur pendant un duel : sur sa rangée de départ, entre la dame (colonne d)
     * et le roi (colonne e), tourné vers l'adversaire. {x, y, z, yaw}
     */
    public double[] campSpawn(Side side) {
        boolean white = side == Side.WHITE;
        double x = x0 + 4 * CELL; // frontière entre les cases d et e
        double z = cellZ(white ? 0 : 7) + CELL / 2.0;
        return new double[]{x, y0 + 1, z, white ? 180 : 0};
    }

    /** Vrai si la position est sur le plateau (ou juste à côté) : sert à garder les duellistes dedans. */
    public boolean isInsideField(double x, double z) {
        int size = 8 * CELL;
        return x >= x0 - 1 && x <= x0 + size + 1 && z >= z0 - 1 && z <= z0 + size + 1;
    }

    // ---------- Plateau ----------

    /** Origine (coin nord-ouest) d'une case de cimetière. West = pièces blanches prises. */
    private int graveOriginX(boolean west, int col) {
        int size = 8 * CELL;
        return west ? x0 - 10 - CELL * col : x0 + size + 3 + CELL * col;
    }

    public void buildBoard() {
        int size = 8 * CELL;

        // Dégage tout l'espace (plateau + cadre + cimetières)
        fillBig(x0 - 17, y0 + 1, z0 - 2, x0 + size + 16, y0 + PIECE_TOP + 3, z0 + size + 1, "minecraft:air");

        // Plateformes des cimetières, de chaque côté du cadre
        fill(x0 - 17, y0, z0, x0 - 3, y0, z0 + size - 1, "minecraft:polished_deepslate");
        fill(x0 + size + 2, y0, z0, x0 + size + 16, y0, z0 + size - 1, "minecraft:polished_deepslate");

        // Cadre de 2 blocs autour du plateau
        fill(x0 - 2, y0, z0 - 2, x0 + size + 1, y0, z0 + size + 1, "minecraft:polished_blackstone_bricks");
        fill(x0 - 1, y0, z0 - 1, x0 + size, y0, z0 + size, "minecraft:stripped_dark_oak_log hollow");

        // Poteaux d'angle avec lanterne
        int[] xs = {x0 - 2, x0 + size + 1};
        int[] zs = {z0 - 2, z0 + size + 1};
        for (int cx : xs) {
            for (int cz : zs) {
                fill(cx, y0 + 1, cz, cx, y0 + 3, cz, "minecraft:stripped_dark_oak_log[axis=y]");
                set(cx, y0 + 4, cz, "minecraft:lantern");
            }
        }

        drawFloor(Map.of());
        buildLabels();
        buildLights();
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
              + "translation:[0f,0f,0f],scale:[5f,5f,5f]}}",
                x, y0 + 1.8, z, text));
    }

    /**
     * Blocs de lumière invisibles au coin de chaque case (là où il n'y a jamais de pièce) :
     * aucun endroit à 0 de luminosité, donc aucun monstre ne peut apparaître sur le plateau.
     */
    private void buildLights() {
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                set(cellX(f), y0 + 1, cellZ(r), "minecraft:light[level=15]");
            }
        }
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 2; col++) {
                set(graveOriginX(true, col), y0 + 1, cellZ(row), "minecraft:light[level=15]");
                set(graveOriginX(false, col), y0 + 1, cellZ(row), "minecraft:light[level=15]");
            }
        }
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

    // ---------- Pièces en blocs ----------

    private static final int MAIN = 0, TRIM = 1, GOLD = 2;

    /** Un pavé de blocs en coordonnées locales de la case (x, z de 0 à 6, y = hauteur au-dessus du sol). */
    private record Box(int x1, int y1, int z1, int x2, int y2, int z2, int kind) {}

    private static Box p(int x1, int y1, int z1, int x2, int y2, int z2) { return new Box(x1, y1, z1, x2, y2, z2, MAIN); }
    private static Box t(int x1, int y1, int z1, int x2, int y2, int z2) { return new Box(x1, y1, z1, x2, y2, z2, TRIM); }
    private static Box g(int x1, int y1, int z1, int x2, int y2, int z2) { return new Box(x1, y1, z1, x2, y2, z2, GOLD); }

    /** Dessin de chaque pièce. Un pavé posé plus tard remplace ce qui est dessous. Devant = z faible (nord). */
    private static List<Box> boxes(PieceType type) {
        return switch (type) {

            case PAWN -> List.of(
                t(1, 1, 1, 5, 1, 5),
                p(2, 2, 2, 4, 3, 4),
                p(3, 4, 3, 3, 4, 3),
                t(2, 5, 2, 4, 5, 4),
                p(2, 6, 2, 4, 8, 4),
                p(3, 9, 3, 3, 9, 3));

            case ROOK -> List.of(
                t(1, 1, 1, 5, 1, 5),
                p(1, 2, 1, 5, 2, 5),
                p(2, 3, 2, 4, 7, 4),
                t(1, 4, 1, 5, 4, 5),
                p(1, 8, 1, 5, 9, 5),
                // créneaux : 4 coins + 4 milieux
                p(1, 10, 1, 1, 10, 1), p(5, 10, 1, 5, 10, 1),
                p(1, 10, 5, 1, 10, 5), p(5, 10, 5, 5, 10, 5),
                p(3, 10, 1, 3, 10, 1), p(3, 10, 5, 3, 10, 5),
                p(1, 10, 3, 1, 10, 3), p(5, 10, 3, 5, 10, 3));

            case KNIGHT -> List.of(
                t(1, 1, 1, 5, 1, 5),
                p(2, 2, 3, 4, 4, 5),      // poitrail
                p(2, 5, 3, 4, 6, 4),      // cou
                p(2, 7, 2, 4, 8, 4),      // tête
                p(3, 6, 1, 3, 7, 2),      // museau
                t(3, 6, 1, 3, 6, 1),      // naseaux
                t(3, 6, 4, 3, 8, 4),      // crinière
                p(2, 9, 3, 2, 9, 3),      // oreilles
                p(4, 9, 3, 4, 9, 3),
                g(2, 8, 2, 2, 8, 2),      // yeux
                g(4, 8, 2, 4, 8, 2));

            case BISHOP -> List.of(
                t(1, 1, 1, 5, 1, 5),
                p(2, 2, 2, 4, 3, 4),
                t(2, 4, 2, 4, 4, 4),
                p(2, 5, 2, 4, 7, 4),
                t(1, 8, 1, 5, 8, 5),
                p(2, 9, 2, 4, 10, 4),     // mitre
                t(3, 9, 2, 3, 10, 2),     // fente de la mitre
                p(3, 11, 3, 3, 11, 3),
                g(3, 12, 3, 3, 12, 3));

            case QUEEN -> List.of(
                t(1, 1, 1, 5, 1, 5),
                p(1, 2, 1, 5, 2, 5),
                p(2, 3, 2, 4, 8, 4),
                t(1, 5, 1, 5, 5, 5),
                t(1, 9, 1, 5, 9, 5),
                p(1, 10, 1, 5, 10, 5),
                // couronne : 4 pointes d'angle + 4 milieux
                p(1, 11, 1, 1, 12, 1), p(5, 11, 1, 5, 12, 1),
                p(1, 11, 5, 1, 12, 5), p(5, 11, 5, 5, 12, 5),
                p(3, 11, 1, 3, 11, 1), p(3, 11, 5, 3, 11, 5),
                p(1, 11, 3, 1, 11, 3), p(5, 11, 3, 5, 11, 3),
                g(1, 13, 1, 1, 13, 1), g(5, 13, 1, 5, 13, 1),
                g(1, 13, 5, 1, 13, 5), g(5, 13, 5, 5, 13, 5),
                g(3, 11, 3, 3, 11, 3));

            case KING -> List.of(
                t(1, 1, 1, 5, 1, 5),
                p(1, 2, 1, 5, 2, 5),
                p(2, 3, 2, 4, 8, 4),
                t(1, 5, 1, 5, 5, 5),
                t(1, 9, 1, 5, 9, 5),
                g(1, 10, 1, 5, 10, 5),    // bandeau doré
                p(2, 11, 2, 4, 12, 4),    // couronne
                g(3, 13, 3, 3, 16, 3),    // croix
                g(2, 15, 3, 4, 15, 3));

            default -> List.of();
        };
    }

    /** Efface tout ce qui se trouve dans la zone des pièces d'une case (la lumière du bord reste). */
    private void clearCell(int ox, int oz) {
        fill(ox + 1, y0 + 1, oz + 1, ox + 5, y0 + PIECE_TOP, oz + 5, "minecraft:air");
    }

    private void buildPiece(Piece piece, int ox, int oz) {
        boolean white = piece.getPieceSide() == Side.WHITE;
        String main = white ? "minecraft:white_concrete" : "minecraft:black_concrete";
        String trim = white ? "minecraft:light_gray_concrete" : "minecraft:gray_concrete";
        for (Box b : boxes(piece.getPieceType())) {
            String block = switch (b.kind()) {
                case GOLD -> "minecraft:gold_block";
                case TRIM -> trim;
                default -> main;
            };
            int x1 = b.x1(), x2 = b.x2(), z1 = b.z1(), z2 = b.z2();
            if (!white) { // les Noirs sont tournés à 180° : ils regardent vers le sud
                x1 = 6 - b.x2();
                x2 = 6 - b.x1();
                z1 = 6 - b.z2();
                z2 = 6 - b.z1();
            }
            fill(ox + x1, y0 + b.y1(), oz + z1, ox + x2, y0 + b.y2(), oz + z2, block);
        }
    }

    /** Efface la statue d'une case (pendant un duel, les deux pièces sont remplacées par les joueurs). */
    public void hidePiece(Square sq) {
        clearCell(cellX(fileIndex(sq)), cellZ(rankIndex(sq)));
        shown.remove(sq);
    }

    /** Met l'affichage à jour : seules les cases qui ont changé sont reconstruites. */
    public void renderPieces(Board board) {
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) continue;
            Piece now = board.getPiece(sq);
            Piece before = shown.getOrDefault(sq, Piece.NONE);
            if (now == before) continue;

            int ox = cellX(fileIndex(sq));
            int oz = cellZ(rankIndex(sq));
            clearCell(ox, oz);
            if (now != Piece.NONE) {
                buildPiece(now, ox, oz);
                shown.put(sq, now);
            } else {
                shown.remove(sq);
            }
        }
        updateGraveyard(board);
    }

    // ---------- Cimetières ----------

    private static final Piece[] WHITE_SET = {Piece.WHITE_QUEEN, Piece.WHITE_ROOK, Piece.WHITE_BISHOP,
            Piece.WHITE_KNIGHT, Piece.WHITE_PAWN};
    private static final Piece[] BLACK_SET = {Piece.BLACK_QUEEN, Piece.BLACK_ROOK, Piece.BLACK_BISHOP,
            Piece.BLACK_KNIGHT, Piece.BLACK_PAWN};

    private String graveSignature = "";

    private static int startCount(Piece p) {
        return switch (p.getPieceType()) {
            case PAWN -> 8;
            case QUEEN -> 1;
            default -> 2;
        };
    }

    /** Pièces d'un camp qui ont disparu du plateau (en tenant compte des promotions). */
    private List<Piece> captured(Board board, Piece[] set) {
        Map<Piece, Integer> now = new HashMap<>();
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) continue;
            Piece pc = board.getPiece(sq);
            if (pc != Piece.NONE) now.merge(pc, 1, Integer::sum);
        }
        int promoted = 0; // pions devenus dame, tour, fou ou cavalier : ils ne sont pas "pris"
        for (Piece pc : set) {
            if (pc.getPieceType() != PieceType.PAWN) {
                promoted += Math.max(0, now.getOrDefault(pc, 0) - startCount(pc));
            }
        }
        List<Piece> out = new ArrayList<>();
        for (Piece pc : set) {
            int missing = startCount(pc) - now.getOrDefault(pc, 0);
            if (pc.getPieceType() == PieceType.PAWN) missing -= promoted;
            for (int i = 0; i < missing; i++) out.add(pc);
        }
        return out;
    }

    /** Reconstruit les cimetières seulement si les pièces prises ont changé. */
    private void updateGraveyard(Board board) {
        List<Piece> whiteLost = captured(board, WHITE_SET);
        List<Piece> blackLost = captured(board, BLACK_SET);
        String signature = whiteLost + "|" + blackLost;
        if (signature.equals(graveSignature)) return;
        graveSignature = signature;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 2; col++) {
                clearCell(graveOriginX(true, col), cellZ(row));
                clearCell(graveOriginX(false, col), cellZ(row));
            }
        }
        placeGraves(whiteLost, true);
        placeGraves(blackLost, false);
    }

    private void placeGraves(List<Piece> pieces, boolean west) {
        for (int k = 0; k < pieces.size() && k < 16; k++) {
            buildPiece(pieces.get(k), graveOriginX(west, k / 8), cellZ(k % 8));
        }
    }

    // ---------- Protection ----------

    /** Vrai si le bloc fait partie du plateau, de son cadre ou des cimetières. */
    public boolean isProtected(BlockPos pos) {
        int size = 8 * CELL;
        return pos.getX() >= x0 - 17 && pos.getX() <= x0 + size + 16
                && pos.getZ() >= z0 - 2 && pos.getZ() <= z0 + size + 1
                && pos.getY() >= y0 && pos.getY() <= y0 + PIECE_TOP + 2;
    }

    // ---------- Effets ----------

    /** Son et particules sur une case : coup normal ou prise. */
    public void effect(Square sq, boolean capture) {
        double x = cellX(fileIndex(sq)) + CELL / 2.0;
        double z = cellZ(rankIndex(sq)) + CELL / 2.0;
        run(String.format(Locale.ROOT, "playsound %s block @a %.1f %.1f %.1f 4 1",
                capture ? "minecraft:block.anvil.land" : "minecraft:block.stone.place", x, y0 + 2.0, z));
        run(String.format(Locale.ROOT, "particle minecraft:%s %.1f %.1f %.1f 2 1 2 0.02 %d",
                capture ? "explosion" : "poof", x, y0 + 1.5, z, capture ? 5 : 25));
    }

    /** Supprime les objets et orbes d'expérience tombés sur le plateau après un duel. */
    public void clearDrops() {
        int size = 8 * CELL;
        run(String.format(Locale.ROOT, "kill @e[type=minecraft:item,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]",
                x0, y0, z0, size, PIECE_TOP + 5, size));
        run(String.format(Locale.ROOT, "kill @e[type=minecraft:experience_orb,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]",
                x0, y0, z0, size, PIECE_TOP + 5, size));
    }

    // ---------- Nettoyage ----------

    public void removeAll() {
        run("kill @e[tag=fpschess_dummy]");
        run("kill @e[tag=fpschess_label]");
        shown.clear();
        graveSignature = "";
        int size = 8 * CELL;
        fillBig(x0 - 17, y0 + 1, z0 - 2, x0 + size + 16, y0 + PIECE_TOP + 3, z0 + size + 1, "minecraft:air");
    }
}
