package fr.fpschess;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gère les clics sur le plateau : 1er clic = choisir une pièce, 2e clic = destination.
 * Si le coup est une prise, un duel est lancé. Deux modes :
 *  - SOLO : tu joues les deux camps, le défenseur est un zombie équipé.
 *  - FRIEND : un joueur par camp, duel joueur contre joueur.
 */
public class ClickHandler {

    public enum Mode { NONE, SOLO, FRIEND }

    private static final String SELECTED = "minecraft:yellow_concrete";
    private static final String MOVE = "minecraft:lime_concrete";
    private static final String CAPTURE = "minecraft:red_concrete";
    private static final String LAST = "minecraft:light_blue_concrete"; // dernier coup
    private static final String CHECK = "minecraft:orange_concrete";     // roi en échec

    private final ChessGame game;
    private final BoardRenderer renderer;
    private Square selected = null;
    private Square lastFrom = null;
    private Square lastTo = null;
    private Square pendingFrom = null; // promotion en attente de choix
    private Square pendingTo = null;

    private Mode mode = Mode.NONE; // il faut choisir solo ou ami avant de jouer
    private UUID owner = null;     // en mode solo : le seul joueur autorisé
    private String ownerName = "";
    private UUID white = null;
    private UUID black = null;

    public ClickHandler(ChessGame game, BoardRenderer renderer) {
        this.game = game;
        this.renderer = renderer;
    }

    // ---------- Charge de sélection ----------

    /** Nom de la charge de vent spéciale qui sert à viser les cases (pour la distinguer des charges du cavalier). */
    public static final String TOOL_NAME = "Charge de sélection";

    public static boolean isTool(ItemStack stack) {
        return stack.isOf(Items.WIND_CHARGE) && stack.getName().getString().equals(TOOL_NAME);
    }

    private static boolean hasTool(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (isTool(p.getInventory().getStack(i))) return true;
        }
        return false;
    }

    private static void takeTool(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (isTool(p.getInventory().getStack(i))) {
                p.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
    }

    private static void giveTool(MinecraftServer server, ServerPlayerEntity p) {
        // /give n'accepte que des noms de joueurs (pas d'UUID) : on passe par le pseudo
        String command = "give @a[name=" + p.getName().getString() + "] minecraft:wind_charge["
                + "minecraft:custom_name='{\"text\":\"" + TOOL_NAME + "\",\"color\":\"gold\",\"italic\":false}'] 1";
        server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), command);
    }

    /** Donne la charge au joueur dont c'est le tour et la retire à tous les autres. */
    public void syncTools(MinecraftServer server) {
        UUID holder = null;
        if (!game.isOver() && !DuelManager.isActive()) {
            if (mode == Mode.SOLO) holder = owner;
            else if (mode == Mode.FRIEND) holder = game.sideToMove() == Side.WHITE ? white : black;
        }
        applyTools(server, holder);
    }

    /** Retire la charge à tout le monde. */
    public void removeTools(MinecraftServer server) {
        applyTools(server, null);
    }

    private void applyTools(MinecraftServer server, UUID holder) {
        Set<UUID> everyone = new HashSet<>();
        if (owner != null) everyone.add(owner);
        if (white != null) everyone.add(white);
        if (black != null) everyone.add(black);
        for (UUID id : everyone) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null) continue;
            boolean has = hasTool(p);
            if (id.equals(holder)) {
                if (!has) giveTool(server, p);
            } else if (has) {
                takeTool(p);
            }
        }
    }

    // ---------- Modes ----------

    public Mode mode() { return mode; }

    public void setSolo(ServerPlayerEntity player) {
        mode = Mode.SOLO;
        owner = player.getUuid();
        ownerName = player.getName().getString();
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
        pendingFrom = null;
        pendingTo = null;
        redrawFloor();
    }

    /** Nouvelle partie : on oublie aussi le dernier coup. */
    public void newGame() {
        lastFrom = null;
        lastTo = null;
        reset();
    }

    /** Couleurs permanentes du sol : dernier coup et roi en échec. */
    private Map<Square, String> baseColors() {
        Map<Square, String> m = new HashMap<>();
        if (lastFrom != null && lastTo != null) {
            m.put(lastFrom, LAST);
            m.put(lastTo, LAST);
        }
        try {
            if (game.board().isKingAttacked()) {
                m.put(game.board().getKingSquare(game.sideToMove()), CHECK);
            }
        } catch (Exception ignored) {
            // position inhabituelle : on n'affiche simplement pas l'échec
        }
        return m;
    }

    private void redrawFloor() {
        renderer.drawFloor(baseColors());
    }

    /** Abandon. Renvoie un message d'erreur, ou null si tout est bon. */
    public String resign(ServerPlayerEntity player) {
        if (DuelManager.isActive()) return "Un duel est en cours.";
        if (game.isOver()) return "La partie est déjà terminée.";
        Side loser;
        if (mode == Mode.FRIEND) {
            if (player.getUuid().equals(white)) loser = Side.WHITE;
            else if (player.getUuid().equals(black)) loser = Side.BLACK;
            else return "Tu ne joues pas cette partie.";
        } else if (mode == Mode.SOLO) {
            if (!player.getUuid().equals(owner)) return "Seul " + ownerName + " peut abandonner.";
            loser = game.sideToMove();
        } else {
            return "Aucune partie en cours.";
        }
        game.forceWin(loser.flip(), "Abandon des " + (loser == Side.WHITE ? "Blancs" : "Noirs") + ".");
        selected = null;
        redrawFloor();
        MinecraftServer server = player.getServer();
        if (server != null) {
            showState(server, 0);
            syncTools(server);
        }
        return null;
    }

    /** Renvoie la raison pour laquelle ce joueur ne peut pas jouer maintenant, ou null s'il peut. */
    private String permissionError(ServerPlayerEntity player) {
        if (mode == Mode.NONE) {
            return "Choisis d'abord un mode avec les boutons du chat (ou /fpschess mode solo | friend).";
        }
        if (mode == Mode.SOLO && !player.getUuid().equals(owner)) {
            return "Mode solo : seul " + ownerName + " peut jouer. Passez en mode ami pour jouer à deux.";
        }
        if (mode == Mode.FRIEND) {
            if (black == null) {
                return "En attente du joueur Noir (il doit cliquer sur Rejoindre ou taper /fpschess join).";
            }
            UUID expected = game.sideToMove() == Side.WHITE ? white : black;
            if (!player.getUuid().equals(expected)) {
                return "Ce n'est pas ton tour.";
            }
        }
        return null;
    }

    /** Appelé par /fpschess promote : termine un coup de promotion. Renvoie une erreur ou null. */
    public String promote(ServerPlayerEntity player, char piece) {
        if (pendingFrom == null) return "Aucune promotion en attente.";
        if (DuelManager.isActive()) return "Un duel est en cours.";
        if (game.isOver()) return "La partie est terminée.";
        String denied = permissionError(player);
        if (denied != null) return denied;
        Square from = pendingFrom;
        Square to = pendingTo;
        pendingFrom = null;
        pendingTo = null;
        execute(player, from, to, piece);
        return null;
    }

    private static Text promotionMenu() {
        return Text.literal("Promotion ! Choisis la pièce :  ").formatted(Formatting.GOLD)
            .append(FpsChessMod.button("[Dame]", "/fpschess promote q", Formatting.LIGHT_PURPLE, "Promouvoir en dame"))
            .append(Text.literal(" "))
            .append(FpsChessMod.button("[Tour]", "/fpschess promote r", Formatting.AQUA, "Promouvoir en tour"))
            .append(Text.literal(" "))
            .append(FpsChessMod.button("[Fou]", "/fpschess promote b", Formatting.GREEN, "Promouvoir en fou"))
            .append(Text.literal(" "))
            .append(FpsChessMod.button("[Cavalier]", "/fpschess promote n", Formatting.YELLOW, "Promouvoir en cavalier"));
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
        String denied = permissionError(player);
        if (denied != null) {
            tell(player, denied);
            return;
        }
        pendingFrom = null; // un clic ailleurs annule une promotion en attente
        pendingTo = null;

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

        // Un pion qui arrive au bout : on demande quelle pièce il devient
        if (game.needsPromotionChoice(selected, sq)) {
            pendingFrom = selected;
            pendingTo = sq;
            player.sendMessage(promotionMenu(), false);
            return;
        }
        execute(player, selected, sq, 'q');
    }

    /** Joue le coup from -> to : coup normal, ou duel en cas de prise. */
    private void execute(ServerPlayerEntity player, Square from, Square to, char promo) {
        MoveResult r = game.tryMove(from, to, promo);
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
            lastFrom = r.move().getFrom();
            lastTo = r.move().getTo();
            redrawFloor();
            renderer.renderPieces(game.board());
            renderer.effect(r.move().getTo(), false);
            broadcast(server, r.move() + (game.isOver() ? "" : ". Trait aux " + turnName() + "."));
            showState(server, 0);
            syncTools(server);
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
        redrawFloor();
        Titles.show(server, "@a", "PRISE !",
                Titles.cap(Kits.article(c.attacker().getPieceType())) + " attaque "
                + Kits.article(c.defender().getPieceType()) + " en " + c.capturedSquare(), "red");
        DuelManager.start(server, renderer, c, player, defender, attackerWon -> afterDuel(server, move, c, attackerWon));
    }

    private void afterDuel(MinecraftServer server, Move move, CaptureInfo c, boolean attackerWon) {
        String title;
        String subtitle;
        if (attackerWon) {
            game.applyMove(move);
            lastFrom = move.getFrom();
            lastTo = move.getTo();
            title = Titles.cap(Kits.article(c.attacker().getPieceType())) + " gagne le duel !";
            subtitle = "Pièce capturée : " + Kits.pieceName(c.defender().getPieceType()) + " en " + c.capturedSquare();
        } else {
            lastFrom = null;
            lastTo = null;
            if (c.attacker().getPieceType() == PieceType.KING) {
                game.forceWin(c.attacker().getPieceSide().flip());
            } else {
                game.removeLoserAttacker(c);
            }
            title = Titles.cap(Kits.article(c.defender().getPieceType())) + " gagne le duel !";
            subtitle = "Pièce éliminée : " + Kits.pieceName(c.attacker().getPieceType());
        }
        renderer.renderPieces(game.board());
        renderer.effect(c.capturedSquare(), true);
        redrawFloor();
        Titles.show(server, "@a", title, subtitle, "gold");
        showState(server, 100); // échec, mat... : annoncé juste après le résultat du duel
        syncTools(server);
    }

    /** Annonce en titre : échec, mat, pat, nulle, abandon ou roi tombé. delay = attente en ticks. */
    private void showState(MinecraftServer server, int delay) {
        String title = null;
        String subtitle = "";
        String color = "red";
        Side forced = game.forcedWinner();
        if (forced != null) {
            title = game.forcedReason();
            subtitle = "Victoire des " + (forced == Side.WHITE ? "Blancs" : "Noirs");
            color = "gold";
        } else if (game.isMate()) {
            title = "ÉCHEC ET MAT !";
            subtitle = "Victoire des " + (game.sideToMove() == Side.WHITE ? "Noirs" : "Blancs");
            color = "gold";
        } else if (game.isStalemate()) {
            title = "PAT";
            subtitle = "Partie nulle";
            color = "gray";
        } else if (game.isDrawn()) {
            title = "PARTIE NULLE";
            subtitle = "Matériel insuffisant, répétition ou règle des 50 coups";
            color = "gray";
        } else if (game.inCheck()) {
            title = "ÉCHEC !";
            subtitle = "Les " + turnName() + " sont en échec";
            color = "yellow";
        }
        if (title == null) return;
        final String t = title;
        final String st = subtitle;
        final String col = color;
        if (delay <= 0) {
            Titles.show(server, "@a", t, st, col);
        } else {
            Titles.later(delay, () -> Titles.show(server, "@a", t, st, col));
        }
    }

    private void select(Square sq) {
        selected = sq;
        Map<Square, String> colors = baseColors();
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
