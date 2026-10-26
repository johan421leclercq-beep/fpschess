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

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Construit le plateau dans le monde et affiche les pièces.
 * Tout passe par des commandes vanilla (fill, summon, kill) : c'est le plus
 * stable d'une version à l'autre et ça évite de manipuler les classes d'entités.
 *
 * Repère : colonne a..h le long de +X, rangée 1..8 le long de -Z (les Blancs au sud).
 * Chaque case fait CELL x CELL blocs. Le sol du plateau est à la hauteur y0.
 */
public class BoardRenderer {

    public static final int CELL = 3;
    public static final String TAG = "fpschess_piece";

    private static final String LIGHT = "minecraft:birch_planks";
    private static final String DARK = "minecraft:dark_oak_planks";

    private final MinecraftServer server;
    private final ServerWorld world;
    private final int x0, y0, z0;

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

    // ---------- Coordonnées ----------

    private int fileIndex(Square sq) { return sq.getFile().ordinal(); } // 0 = a
    private int rankIndex(Square sq) { return sq.getRank().ordinal(); } // 0 = rangée 1

    private int cellX(int file) { return x0 + file * CELL; }
    private int cellZ(int rank) { return z0 + (7 - rank) * CELL; }

    // ---------- Plateau ----------

    public void buildBoard() {
        int size = 8 * CELL;
        // Dégage l'espace au-dessus
        run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                x0, y0 + 1, z0, x0 + size - 1, y0 + 6, z0 + size - 1));
        drawFloor(Map.of());
    }

    /** Dessine le sol. 'overrides' permet de colorer certaines cases (sélection, coups possibles). */
    public void drawFloor(Map<Square, String> overrides) {
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Square sq = Square.encode(Rank.values()[r], File.values()[f]);
                String block = overrides.getOrDefault(sq, ((f + r) % 2 == 0) ? DARK : LIGHT); // a1 est sombre
                int x = cellX(f);
                int z = cellZ(r);
                run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s",
                        x, y0, z, x + CELL - 1, y0, z + CELL - 1, block));
            }
        }
    }

    public ServerWorld world() {
        return world;
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

    // ---------- Pièces ----------

    public static final String SQ_TAG_PREFIX = "fpschess_sq_";

    /** Un morceau de statue : taille, centre (cx, cz) par rapport au centre de la case, hauteur y depuis le sol. */
    private record Part(double sx, double sy, double sz, double cx, double y, double cz, boolean gold) {}

    private static Part p(double sx, double sy, double sz, double cx, double y, double cz) {
        return new Part(sx, sy, sz, cx, y, cz, false);
    }

    private static Part g(double sx, double sy, double sz, double cx, double y, double cz) {
        return new Part(sx, sy, sz, cx, y, cz, true);
    }

    /** Dessin de chaque pièce. cz négatif = vers l'avant (le museau du cavalier). */
    private static List<Part> parts(PieceType type) {
        return switch (type) {
            case PAWN -> List.of(
                p(1.4, 0.25, 1.4, 0, 0, 0),
                p(0.5, 0.6, 0.5, 0, 0.25, 0),
                p(0.8, 0.8, 0.8, 0, 0.85, 0));
            case ROOK -> List.of(
                p(2.0, 0.3, 2.0, 0, 0, 0),
                p(1.2, 1.2, 1.2, 0, 0.3, 0),
                p(1.6, 0.4, 1.6, 0, 1.5, 0),
                p(0.4, 0.4, 0.4, 0.6, 1.9, 0.6),
                p(0.4, 0.4, 0.4, -0.6, 1.9, 0.6),
                p(0.4, 0.4, 0.4, 0.6, 1.9, -0.6),
                p(0.4, 0.4, 0.4, -0.6, 1.9, -0.6));
            case KNIGHT -> List.of(
                p(1.8, 0.3, 1.8, 0, 0, 0),
                p(1.0, 1.0, 1.0, 0, 0.3, 0.1),
                p(0.9, 1.0, 0.9, 0, 1.3, -0.1),
                p(0.6, 0.5, 0.7, 0, 1.4, -0.8),
                p(0.25, 0.35, 0.25, 0.25, 2.3, 0.1),
                p(0.25, 0.35, 0.25, -0.25, 2.3, 0.1));
            case BISHOP -> List.of(
                p(1.8, 0.3, 1.8, 0, 0, 0),
                p(0.9, 1.2, 0.9, 0, 0.3, 0),
                p(1.2, 0.2, 1.2, 0, 1.5, 0),
                p(0.7, 0.9, 0.7, 0, 1.7, 0),
                g(0.25, 0.25, 0.25, 0, 2.6, 0));
            case QUEEN -> List.of(
                p(2.0, 0.3, 2.0, 0, 0, 0),
                p(1.0, 1.6, 1.0, 0, 0.3, 0),
                p(1.5, 0.25, 1.5, 0, 1.9, 0),
                p(0.35, 0.5, 0.35, 0.55, 2.15, 0.55),
                p(0.35, 0.5, 0.35, -0.55, 2.15, 0.55),
                p(0.35, 0.5, 0.35, 0.55, 2.15, -0.55),
                p(0.35, 0.5, 0.35, -0.55, 2.15, -0.55),
                g(0.5, 0.5, 0.5, 0, 2.15, 0));
            case KING -> List.of(
                p(2.0, 0.3, 2.0, 0, 0, 0),
                p(1.1, 1.8, 1.1, 0, 0.3, 0),
                p(1.5, 0.25, 1.5, 0, 2.1, 0),
                p(0.9, 0.45, 0.9, 0, 2.35, 0),
                g(0.3, 0.9, 0.3, 0, 2.8, 0),
                g(0.9, 0.3, 0.3, 0, 3.1, 0));
            default -> List.of();
        };
    }

    public void clearPieces() {
        run("kill @e[tag=" + TAG + "]");
    }

    /** Efface et redessine toutes les pièces d'après l'état du plateau. */
    public void renderPieces(Board board) {
        clearPieces();
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) continue;
            Piece p = board.getPiece(sq);
            if (p == Piece.NONE) continue;
            summonPiece(p, sq);
        }
    }

    private void summonPiece(Piece piece, Square sq) {
        double centerX = cellX(fileIndex(sq)) + CELL / 2.0;
        double centerZ = cellZ(rankIndex(sq)) + CELL / 2.0;
        double floor = y0 + 1;

        boolean white = piece.getPieceSide() == Side.WHITE;
        String material = white ? "minecraft:white_concrete" : "minecraft:black_concrete";
        double dir = white ? 1 : -1; // les Blancs regardent le nord (-Z), les Noirs le sud
        String sqTag = SQ_TAG_PREFIX + sq.toString().toLowerCase(Locale.ROOT);
        String tags = "Tags:[\"" + TAG + "\",\"" + sqTag + "\"]";

        for (Part part : parts(piece.getPieceType())) {
            String block = part.gold() ? "minecraft:gold_block" : material;
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
                "summon minecraft:interaction %.3f %.3f %.3f {%s,width:1.8f,height:2.6f,response:1b}",
                centerX, floor, centerZ, tags));
    }

    // ---------- Arène de duel (à l'est du plateau) ----------

    public static final int ARENA_SIZE = 25;

    public int arenaX() { return x0 + 8 * CELL + 8; }

    /** Spawn 0 = côté ouest (regarde l'est), spawn 1 = côté est (regarde l'ouest). {x, y, z, yaw} */
    public double[] arenaSpawn(int index) {
        double z = z0 + ARENA_SIZE / 2.0;
        return index == 0
                ? new double[]{arenaX() + 3.5, y0 + 1, z, -90}
                : new double[]{arenaX() + ARENA_SIZE - 3.5, y0 + 1, z, 90};
    }

    public String dimension() {
        return world.getRegistryKey().getValue().toString();
    }

    public void buildArena() {
        int ax = arenaX(), e = ARENA_SIZE - 1;
        // Boîte fermée (murs + plafond), intérieur vidé
        run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks hollow",
                ax, y0, z0, ax + e, y0 + 9, z0 + e));
        run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:deepslate_tiles",
                ax, y0, z0, ax + e, y0, z0 + e));
        // Piliers pour se mettre à couvert
        int[][] pillars = {{10, 5}, {13, 5}, {10, 18}, {13, 18}};
        for (int[] pl : pillars) {
            run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:chiseled_stone_bricks",
                    ax + pl[0], y0 + 1, z0 + pl[1], ax + pl[0] + 1, y0 + 3, z0 + pl[1] + 1));
        }
        run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:chiseled_stone_bricks",
                ax + 12, y0 + 1, z0 + 12, ax + 12, y0 + 2, z0 + 12));
        // Lumière invisible en grille : aucun endroit à 0 de luminosité, donc aucun monstre ne peut apparaître
        for (int lx = 2; lx < ARENA_SIZE; lx += 4) {
            for (int lz = 2; lz < ARENA_SIZE; lz += 4) {
                run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:light[level=15]",
                        ax + lx, y0 + 5, z0 + lz));
            }
        }
        // Lumière visible au plafond
        int[][] lamps = {{6, 6}, {18, 6}, {6, 18}, {18, 18}, {12, 12}};
        for (int[] l : lamps) {
            run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:sea_lantern",
                    ax + l[0], y0 + 9, z0 + l[1]));
        }
    }

    /** Supprime tout ce qui n'est pas un joueur dans l'arène (objets, orbes, monstres). */
    public void clearArenaEntities() {
        run(String.format(Locale.ROOT,
                "kill @e[type=!minecraft:player,x=%d,y=%d,z=%d,dx=%d,dy=10,dz=%d]",
                arenaX(), y0, z0, ARENA_SIZE, ARENA_SIZE));
    }

    // ---------- Nettoyage ----------

    public void removeAll() {
        clearPieces();
        run("kill @e[tag=fpschess_dummy]");
        run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                arenaX(), y0, z0, arenaX() + ARENA_SIZE - 1, y0 + 9, z0 + ARENA_SIZE - 1));
        int size = 8 * CELL;
        run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                x0, y0, z0, x0 + size - 1, y0 + 6, z0 + size - 1));
    }
}
