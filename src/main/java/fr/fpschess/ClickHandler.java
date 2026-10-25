package fr.fpschess;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gère les clics sur le plateau : 1er clic = choisir une pièce, 2e clic = choisir la destination.
 * Pour l'instant n'importe quel joueur peut jouer les deux camps (étape 5/6 : joueurs assignés).
 */
public class ClickHandler {

    private static final String SELECTED = "minecraft:yellow_concrete";
    private static final String MOVE = "minecraft:lime_concrete";
    private static final String CAPTURE = "minecraft:red_concrete";

    private final ChessGame game;
    private final BoardRenderer renderer;
    private Square selected = null;

    public ClickHandler(ChessGame game, BoardRenderer renderer) {
        this.game = game;
        this.renderer = renderer;
    }

    /** Annule la sélection et remet le sol normal. */
    public void reset() {
        selected = null;
        renderer.drawFloor(Map.of());
    }

    public void onClick(ServerPlayerEntity player, Square sq) {
        if (game.isOver()) {
            tell(player, "La partie est terminée. Tape /fpschess new pour rejouer.");
            return;
        }

        Piece clicked = game.board().getPiece(sq);
        boolean own = clicked != Piece.NONE && clicked.getPieceSide() == game.sideToMove();

        // 1er clic : choisir une pièce
        if (selected == null) {
            if (!own) {
                tell(player, "Clique sur une de tes pièces (trait aux " + turnName() + ").");
                return;
            }
            select(sq);
            return;
        }

        // Re-clic sur la même case : on annule
        if (sq == selected) {
            reset();
            return;
        }

        // Clic sur une autre pièce à soi : on change de sélection
        if (own) {
            select(sq);
            return;
        }

        // 2e clic : tenter le coup
        MoveResult r = game.tryMove(selected, sq, 'q');
        if (!r.legal()) {
            tell(player, r.message());
            return;
        }

        String info = "";
        if (r.capture() != null) {
            CaptureInfo c = r.capture();
            info = " PRISE : " + c.attacker() + " prend " + c.defender()
                 + " en " + c.capturedSquare() + " (le duel arrive à l'étape 5).";
        }
        game.applyMove(r.move());
        selected = null;
        renderer.drawFloor(Map.of());
        renderer.renderPieces(game.board());

        broadcast(player, r.move() + info + " " + game.statusText());
    }

    private void select(Square sq) {
        selected = sq;
        Map<Square, String> colors = new HashMap<>();
        colors.put(sq, SELECTED);
        for (String m : game.legalMovesFrom(sq)) {
            Square dest = Square.fromValue(m.substring(2, 4).toUpperCase(Locale.ROOT));
            boolean capture = game.board().getPiece(dest) != Piece.NONE;
            colors.put(dest, capture ? CAPTURE : MOVE);
        }
        renderer.drawFloor(colors);
    }

    private String turnName() {
        return game.sideToMove() == Side.WHITE ? "Blancs" : "Noirs";
    }

    private static void tell(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), true); // texte au-dessus de la barre d'objets
    }

    private static void broadcast(ServerPlayerEntity player, String message) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.getPlayerManager().broadcast(Text.literal(message), false);
        }
    }
}
