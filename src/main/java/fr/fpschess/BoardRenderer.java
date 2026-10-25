package fr.fpschess;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.File;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Rank;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

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

    private static final String LIGHT = "minecraft:smooth_quartz";
    private static final String DARK = "minecraft:polished_blackstone";

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

    private void run(String command) {
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

    public void clearPieces() {
        run("kill @e[type=minecraft:text_display,tag=" + TAG + "]");
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
        double x = cellX(fileIndex(sq)) + CELL / 2.0;
        double z = cellZ(rankIndex(sq)) + CELL / 2.0;
        double y = y0 + 1.6; // le glyphe est centré sur ce point

        boolean white = piece.getPieceSide() == Side.WHITE;
        String color = white ? "#FFFFFF" : "#000000";
        // Fond semi-transparent de couleur opposée pour rester lisible sur toutes les cases
        int background = white ? 0xA0404040 : 0xA0D0D0D0;

        String nbt = String.format(Locale.ROOT,
                "{Tags:[\"%s\",\"fpschess_%s\"],"
              + "text:'{\"text\":\"%s\",\"color\":\"%s\"}',"
              + "billboard:\"center\",background:%d,"
              + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
              + "translation:[0f,0f,0f],scale:[7f,7f,7f]}}",
                TAG, sq.toString().toLowerCase(Locale.ROOT), glyph(piece), color, background);

        run(String.format(Locale.ROOT, "summon minecraft:text_display %.2f %.2f %.2f %s", x, y, z, nbt));
    }

    /** Symboles d'échecs Unicode (versions pleines). */
    private static String glyph(Piece piece) {
        return switch (piece.getPieceType()) {
            case KING -> "\u265A";
            case QUEEN -> "\u265B";
            case ROOK -> "\u265C";
            case BISHOP -> "\u265D";
            case KNIGHT -> "\u265E";
            case PAWN -> "\u265F";
            default -> "?";
        };
    }

    // ---------- Nettoyage ----------

    public void removeAll() {
        clearPieces();
        int size = 8 * CELL;
        run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                x0, y0, z0, x0 + size - 1, y0 + 6, z0 + size - 1));
    }
}
