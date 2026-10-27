package fr.fpschess;

import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Square;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Gère un duel à la fois : sauvegarde de l'état des joueurs, téléportation dans l'arène,
 * kits, détection de la fin (mort, temps écoulé, déconnexion) et retour au plateau.
 *
 * Joueur contre joueur, ou joueur contre zombie (mode solo, quand defender == null).
 */
public final class DuelManager {

    private static final int GRACE_TICKS = 60;          // 3 s de préparation
    private static final int DURATION_TICKS = 20 * 90;  // 90 s de combat max

    private DuelManager() {}

    private static class Saved {
        ItemStack[] stacks;
        GameMode gameMode;
        double x, y, z;
        float yaw, pitch;
        String dimension;
    }

    private static class Duel {
        BoardRenderer renderer;
        UUID attacker, defender, dummy; // defender == null -> zombie (dummy)
        Map<UUID, Saved> saved = new HashMap<>();
        Map<UUID, PieceType> types = new HashMap<>(); // pièce incarnée par chaque joueur
        Map<UUID, double[]> spawns = new HashMap<>(); // point de départ de chaque joueur (pour le ramener sur le plateau)
        int ticks = 0;
        Boolean attackerWon = null;
        Consumer<Boolean> onFinish;
    }

    private static Duel current = null;

    public static boolean isActive() {
        return current != null;
    }

    // ---------- Démarrage ----------

    public static void start(MinecraftServer server, BoardRenderer r, CaptureInfo c,
                             ServerPlayerEntity attacker, ServerPlayerEntity defender,
                             Consumer<Boolean> onFinish) {
        // Le duel a lieu sur le plateau : les statues des deux pièces sont remplacées par les joueurs
        Square atkSq = c.from();
        Square defSq = c.capturedSquare();
        r.hidePiece(atkSq);
        r.hidePiece(defSq);
        double[] atkSpawn = r.squareSpawn(atkSq, defSq);
        double[] defSpawn = r.squareSpawn(defSq, atkSq);
        r.clearDrops();
        Duel d = new Duel();
        d.renderer = r;
        d.attacker = attacker.getUuid();
        d.defender = defender == null ? null : defender.getUuid();
        d.dummy = defender == null ? UUID.randomUUID() : null;
        d.onFinish = onFinish;

        PieceType atkType = c.attacker().getPieceType();
        PieceType defType = c.defender().getPieceType();

        save(d, attacker);
        prepare(d, attacker, atkType, atkSpawn);
        if (defender != null) {
            save(d, defender);
            prepare(d, defender, defType, defSpawn);
        } else {
            spawnDummy(d, defType, defSpawn);
        }
        current = d;

        FpsChessMod.LOGGER.info("Duel : attaquant {} ({}), défenseur {} ({})",
                attacker.getName().getString(), Kits.pieceName(atkType),
                defender == null ? "zombie" : defender.getName().getString(), Kits.pieceName(defType));
        announce(d, atkType, defType);
    }

    private static void save(Duel d, ServerPlayerEntity p) {
        Saved s = new Saved();
        int size = p.getInventory().size();
        s.stacks = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            s.stacks[i] = p.getInventory().getStack(i).copy();
        }
        s.gameMode = p.interactionManager.getGameMode();
        s.x = p.getX();
        s.y = p.getY();
        s.z = p.getZ();
        s.yaw = p.getYaw();
        s.pitch = p.getPitch();
        s.dimension = p.getWorld().getRegistryKey().getValue().toString();
        d.saved.put(p.getUuid(), s);
    }

    private static void prepare(Duel d, ServerPlayerEntity p, PieceType type, double[] sp) {
        BoardRenderer r = d.renderer;
        String sel = p.getUuidAsString();
        p.getInventory().clear();
        p.clearStatusEffects();
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);
        p.changeGameMode(GameMode.ADVENTURE);
        d.spawns.put(p.getUuid(), sp);
        d.types.put(p.getUuid(), type);
        r.run(String.format(Locale.ROOT, "execute in %s run tp %s %.2f %.2f %.2f %.1f 0",
                r.dimension(), sel, sp[0], sp[1], sp[2], sp[3]));
        Kits.apply(r, sel, type, false);
        p.setHealth(p.getMaxHealth()); // après le kit : la vie maximale a pu changer
        r.run("effect give " + sel + " minecraft:resistance 3 4 true"); // protection pendant la préparation
    }

    private static void spawnDummy(Duel d, PieceType type, double[] sp) {
        BoardRenderer r = d.renderer;
        long msb = d.dummy.getMostSignificantBits();
        long lsb = d.dummy.getLeastSignificantBits();
        String uuidNbt = String.format(Locale.ROOT, "[I;%d,%d,%d,%d]",
                (int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb);
        r.run(String.format(Locale.ROOT,
                "execute in %s run summon minecraft:zombie %.2f %.2f %.2f "
              + "{UUID:%s,NoAI:1b,PersistenceRequired:1b,CanPickUpLoot:0b,IsBaby:0b,"
              + "DeathLootTable:\"minecraft:empty\",Tags:[\"fpschess_dummy\"],"
              + "CustomName:'{\"text\":\"%s adverse\"}'}",
                r.dimension(), sp[0], sp[1], sp[2], uuidNbt, Kits.pieceName(type)));
        Kits.apply(r, d.dummy.toString(), type, true);
    }

    /** Chaque joueur voit son rôle et la pièce qu'il incarne. */
    private static void announce(Duel d, PieceType atk, PieceType def) {
        for (UUID id : d.saved.keySet()) {
            boolean isAttacker = id.equals(d.attacker);
            String sub = isAttacker
                    ? "Tu attaques avec : " + Kits.pieceName(atk) + " (contre " + Kits.pieceName(def) + ")"
                    : "Tu défends avec : " + Kits.pieceName(def) + " (contre " + Kits.pieceName(atk) + ")";
            String sel = id.toString();
            d.renderer.run("title " + sel + " subtitle {\"text\":\"" + sub + "\",\"color\":\"gray\"}");
            d.renderer.run("title " + sel + " title {\"text\":\"DUEL\",\"color\":\"red\",\"bold\":true}");
        }
    }

    // ---------- À chaque tick ----------

    public static void tick(MinecraftServer server) {
        Duel d = current;
        if (d == null) return;
        d.ticks++;

        if (d.attackerWon == null) {
            for (UUID id : d.spawns.keySet()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null && (!d.renderer.isInsideField(p.getX(), p.getZ()) || p.getY() < d.renderer.floorY() - 3)) {
                    double[] sp = d.spawns.get(id);
                    d.renderer.run(String.format(Locale.ROOT, "execute in %s run tp %s %.2f %.2f %.2f %.1f 0",
                            d.renderer.dimension(), p.getUuidAsString(), sp[0], sp[1], sp[2], sp[3]));
                }
            }
            if (d.ticks == GRACE_TICKS && d.dummy != null) {
                d.renderer.run("data modify entity " + d.dummy + " NoAI set value 0b");
            }
            if (d.ticks >= GRACE_TICKS + DURATION_TICKS) {
                d.attackerWon = false; // temps écoulé : le défenseur garde la case
            }
            if (d.ticks % 20 == 0) {
                String text = d.ticks < GRACE_TICKS
                        ? "Début dans " + ((GRACE_TICKS - d.ticks) / 20 + 1) + " s"
                        : "Temps restant : " + ((GRACE_TICKS + DURATION_TICKS - d.ticks) / 20) + " s";
                for (UUID id : d.saved.keySet()) {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                    if (p != null) p.sendMessage(Text.literal(text), true);
                }
            }
        }

        if (d.attackerWon != null) {
            finish(server, d);
        }
    }

    // ---------- Événements ----------

    /** Appelé quand une entité va mourir. Renvoie false pour annuler la mort (joueurs en duel). */
    public static boolean allowDeath(LivingEntity entity) {
        Duel d = current;
        if (d == null) return true;
        UUID id = entity.getUuid();
        if (id.equals(d.dummy)) {
            if (d.attackerWon == null) d.attackerWon = true;
            return true;
        }
        if (id.equals(d.attacker) || id.equals(d.defender)) {
            if (d.attackerWon == null) d.attackerWon = !id.equals(d.attacker);
            entity.setHealth(1.0F); // le joueur ne meurt pas vraiment ; tout est remis en ordre à la fin
            return false;
        }
        return true;
    }

    /** Les perles d'Ender du fou ne lui font aucun dégât (elles infligent des dégâts de chute). */
    public static boolean allowDamage(LivingEntity entity, DamageSource source) {
        Duel d = current;
        if (d == null) return true;
        return !(d.types.get(entity.getUuid()) == PieceType.BISHOP && source.isOf(DamageTypes.FALL));
    }

    /** Un joueur quitte en plein duel : il perd, et on lui rend son inventaire tout de suite. */
    public static void onDisconnect(ServerPlayerEntity p) {
        Duel d = current;
        if (d == null || !d.saved.containsKey(p.getUuid())) return;
        if (d.attackerWon == null) d.attackerWon = !p.getUuid().equals(d.attacker);
        restore(d, p);
    }

    // ---------- Fin ----------

    private static void finish(MinecraftServer server, Duel d) {
        for (UUID id : d.saved.keySet().toArray(new UUID[0])) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) restore(d, p);
        }
        if (d.dummy != null) {
            d.renderer.run("kill " + d.dummy);
        }
        d.renderer.clearDrops();
        current = null;
        d.onFinish.accept(d.attackerWon);
    }

    private static void restore(Duel d, ServerPlayerEntity p) {
        Saved s = d.saved.remove(p.getUuid());
        if (s == null) return;
        BoardRenderer r = d.renderer;
        p.getInventory().clear();
        for (int i = 0; i < s.stacks.length; i++) {
            p.getInventory().setStack(i, s.stacks[i]);
        }
        r.run("attribute " + p.getUuidAsString() + " minecraft:generic.max_health base set 20");
        p.clearStatusEffects();
        p.extinguish();
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);
        String sel = p.getUuidAsString();
        p.changeGameMode(s.gameMode);
        r.run(String.format(Locale.ROOT, "execute in %s run tp %s %.2f %.2f %.2f %.1f %.1f",
                s.dimension, sel, s.x, s.y, s.z, s.yaw, s.pitch));
    }
}
