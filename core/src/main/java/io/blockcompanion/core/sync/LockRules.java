package io.blockcompanion.core.sync;

import java.util.Set;
import java.util.UUID;

/**
 * Who may do what to a shared placement. Each check returns null when allowed, else the reason to show the player.
 *
 * <ul>
 *   <li>Moving or deleting needs {@link Permission#PLACE} (or admin). Nobody but the holder may move a placement while
 *       someone else holds an unexpired editing lock on it, admins included (they can drop the lock first).</li>
 *   <li>An owner-locked placement can only be moved or deleted by its owner or an admin.</li>
 *   <li>Locking and unlocking takes {@link Permission#LOCK} and being the owner, or being an admin.</li>
 *   <li>An editing lock can be dropped by its holder or an admin.</li>
 * </ul>
 */
public final class LockRules {
    private LockRules() {
    }

    /** The player asking. */
    public record Actor(UUID id, String name, Set<Permission> permissions) {
        public boolean admin() {
            return permissions.contains(Permission.ADMIN);
        }

        public boolean has(Permission p) {
            return admin() || permissions.contains(p);
        }
    }

    /** True when {@code p} has an editing lock held by someone other than {@code actor} that has not lapsed. */
    public static boolean editedByOther(SharedPlacement p, Actor actor, long editExpiresAt, long now) {
        return p.editor() != null && !p.editor().equals(actor.id()) && now < editExpiresAt;
    }

    /** Moving, turning, mirroring, and taking the editing lock. */
    public static String canModify(SharedPlacement p, Actor actor, long editExpiresAt, long now) {
        if (!actor.has(Permission.PLACE)) return "You may not change shared placements";
        if (editedByOther(p, actor, editExpiresAt, now)) return p.editorName() + " is moving " + p.name() + " right now";
        if (p.locked() && !p.owner().equals(actor.id()) && !actor.admin()) return p.name() + " is locked by " + p.ownerName();
        return null;
    }

    public static String canDelete(SharedPlacement p, Actor actor, long editExpiresAt, long now) {
        return canModify(p, actor, editExpiresAt, now);
    }

    /** Taking or dropping the owner lock. */
    public static String canOwnerLock(SharedPlacement p, Actor actor) {
        if (actor.admin()) return null;
        if (!p.owner().equals(actor.id())) return "Only " + p.ownerName() + " can lock or unlock " + p.name();
        if (!actor.has(Permission.LOCK)) return "You may not lock placements";
        return null;
    }

    /** Dropping the editing lock. */
    public static String canReleaseEdit(SharedPlacement p, Actor actor) {
        if (p.editor() == null || p.editor().equals(actor.id()) || actor.admin()) return null;
        return p.editorName() + " holds the editing lock";
    }

    /** Sharing a new placement. */
    public static String canCreate(Actor actor, int alreadyOwned, int maxPerPlayer) {
        if (!actor.has(Permission.PLACE)) return "You may not share placements";
        if (!actor.admin() && alreadyOwned >= maxPerPlayer) return "You already share " + alreadyOwned + " placements (limit " + maxPerPlayer + ")";
        return null;
    }
}
