package fr.fpschess;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les clics sur le plateau : 1er clic = choisir une pièce, 2e clic = destination.
 * Si le coup est une prise, un duel est lancé. Deux modes :
 *  - SOLO : tu joues les deux camps, le défenseur est un zombie équipé.
 *  - FRIEND : un joueur par camp, duel joueur contre joueur.
 */
public class ClickHandler {

    public enum Mode { SOLO, FRIEND }

    private static final String SELECTED = "minecraft:yellow_concrete";
    private static final String MOVE = "minecraft:lime_concrete";
    private static final String CAPTURE = "minecraft:red_concrete";

    private final ChessGame game;
    private final BoardRenderer renderer;
    private Square selected = null;

    private Mode mode = Mode.SOLO;
    private UUID white = null;
    private UUID black = null;

    public ClickHandler(ChessGame game, BoardRenderer renderer) {
        this.game = game;
        this.renderer = renderer;
    }

    // ---------- Modes ----------

    public Mode mode() { return mode; }

    public void setSolo() {
        mode = Mode.SOLO;
        white = null;
        black = null;
        reset();
    }

    public void setFriend(ServerPlayerEntity whitePlayer) {
        mode = Mode.FRIEND;
        white = whitePlayer.getUuid();
        black = null;
        reset();
    }

    /** Renvoie un message d'erreur, ou null si tout est bon. */
    public String join(ServerPlayerEntity p) {
        if (mode != Mode.FRIEND) return "Le mode ami n'est pas activé.";
        if (p.getUuid().equals(white)) return "Tu joues déjà les Blancs.";
        black = p.getUuid();
        return null;
    }

    // ---------- Clics ----------

    /** Annule la sélection et remet le sol normal. */
    public void reset() {
        selected = null;
        renderer.drawFloor(Map.of());
    }

    public void onClick(ServerPlayerEntity player, Square sq) {
        if (DuelManager.isActive()) {
            tell(player, "Un duel est en cours...");
            return;
        }
        if (game.isOver()) {
            tell(player, "La partie est terminée. Tape /fpschess new pour rejouer.");
            return;
        }
        if (mode == Mode.FRIEND) {
            if (black == null) {
                tell(player, "En attente du joueur Noir (il doit cliquer sur Rejoindre ou taper /fpschess join).");
                return;
            }
            UUID expected = game.sideToMove() == Side.WHITE ? white : black;
            if (!player.getUuid().equals(expected)) {
                tell(player, "Ce n'est pas ton tour.");
                return;
            }
        }

        Piece clicked = game.board().getPiece(sq);
        boolean own = clicked != Piece.NONE && clicked.getPieceSide() == game.sideToMove();

        if (selected == null) {
            if (!own) {
                tell(player, "Clique sur une de tes pièces (trait aux " + turnName() + ").");
                return;
            }
            select(sq);
            return;
        }
        if (sq == selected) {
            reset();
            return;
        }
        if (own) {
            select(sq);
            return;
        }

        MoveResult r = game.tryMove(selected, sq, 'q');
        if (!r.legal()) {
            tell(player, r.message());
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) return;

        // Pas de prise : coup normal
        if (r.capture() == null) {
            game.applyMove(r.move());
            selected = null;
            renderer.drawFloor(Map.of());
            renderer.renderPieces(game.board());
            broadcast(server, r.move() + " " + game.statusText());
            return;
        }

        // Prise : duel !
        CaptureInfo c = r.capture();
        Move move = r.move();
        ServerPlayerEntity defender = null;
        if (mode == Mode.FRIEND) {
            UUID other = game.sideToMove() == Side.WHITE ? black : white;
            defender = server.getPlayerManager().getPlayer(other);
            if (defender == null) {
                tell(player, "L'autre joueur est déconnecté.");
                return;
            }
        }
        selected = null;
        renderer.drawFloor(Map.of());
        broadcast(server, "PRISE ! " + Kits.pieceName(c.attacker().getPieceType()) + " attaque "
                + Kits.pieceName(c.defender().getPieceType()) + " en " + c.capturedSquare() + " : duel !");
        DuelManager.start(server, renderer, c, player, defender, attackerWon -> afterDuel(server, move, c, attackerWon));
    }

    private void afterDuel(MinecraftServer server, Move move, CaptureInfo c, boolean attackerWon) {
        String msg;
        if (attackerWon) {
            game.applyMove(move);
            msg = Kits.pieceName(c.attacker().getPieceType()) + " gagne le duel et prend la case "
                    + c.capturedSquare() + ".";
        } else {
            if (c.attacker().getPieceType() == PieceType.KING) {
                game.forceWin(c.attacker().getPieceSide().flip());
            } else {
                game.removeLoserAttacker(c);
            }
            msg = Kits.pieceName(c.attacker().getPieceType()) + " perd le duel et est retiré du plateau.";
        }
        renderer.renderPieces(game.board());
        broadcast(server, msg + " " + game.statusText());
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
        player.sendMessage(Text.literal(message), true);
    }

    private static void broadcast(MinecraftServer server, String message) {
        server.getPlayerManager().broadcast(Text.literal(message), false);
    }
}
