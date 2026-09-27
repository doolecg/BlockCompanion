package io.blockcompanion.core.sync;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LockRulesTest {
    static final UUID OWNER = UUID.randomUUID(), OTHER = UUID.randomUUID(), ADMIN = UUID.randomUUID();
    static final EnumSet<Permission> PLAYER = EnumSet.of(Permission.USE, Permission.UPLOAD, Permission.PLACE, Permission.LOCK);

    static LockRules.Actor owner = new LockRules.Actor(OWNER, "Owner", PLAYER);
    static LockRules.Actor other = new LockRules.Actor(OTHER, "Other", PLAYER);
    static LockRules.Actor admin = new LockRules.Actor(ADMIN, "Admin", EnumSet.of(Permission.USE, Permission.ADMIN));
    static LockRules.Actor viewer = new LockRules.Actor(OTHER, "Viewer", EnumSet.of(Permission.USE));

    static SharedPlacement p(boolean locked, UUID editor) {
        return new SharedPlacement(UUID.randomUUID(), ProtocolTest.HASH, "tower.nbt", "minecraft:overworld", 0, 0, 0, 0, false, OWNER,
                "Owner", locked, editor, editor == null ? "" : "Someone", 0);
    }

    @Test
    void unlockedPlacementsAreEveryonesToMove() {
        assertThat(LockRules.canModify(p(false, null), other, 0, 100)).isNull();
        assertThat(LockRules.canModify(p(false, null), viewer, 0, 100)).contains("may not");
    }

    @Test
    void ownerLockKeepsOthersOut() {
        SharedPlacement locked = p(true, null);
        assertThat(LockRules.canModify(locked, owner, 0, 100)).isNull();
        assertThat(LockRules.canModify(locked, admin, 0, 100)).isNull();
        assertThat(LockRules.canModify(locked, other, 0, 100)).contains("locked by Owner");
        assertThat(LockRules.canDelete(locked, other, 0, 100)).isNotNull();
    }

    @Test
    void editingLockBlocksEveryoneElseUntilItLapses() {
        SharedPlacement editing = p(false, OTHER);
        assertThat(LockRules.canModify(editing, owner, 200, 100)).contains("is moving");
        assertThat(LockRules.canModify(editing, admin, 200, 100)).contains("is moving");
        assertThat(LockRules.canModify(editing, other, 200, 100)).isNull();
        // Lapsed: free again.
        assertThat(LockRules.canModify(editing, owner, 200, 200)).isNull();
    }

    @Test
    void onlyOwnerWithLockPermissionOrAdminLocks() {
        SharedPlacement sp = p(false, null);
        assertThat(LockRules.canOwnerLock(sp, owner)).isNull();
        assertThat(LockRules.canOwnerLock(sp, admin)).isNull();
        assertThat(LockRules.canOwnerLock(sp, other)).contains("Only Owner");
        LockRules.Actor ownerWithoutLock = new LockRules.Actor(OWNER, "Owner", EnumSet.of(Permission.USE, Permission.PLACE));
        assertThat(LockRules.canOwnerLock(sp, ownerWithoutLock)).contains("may not lock");
    }

    @Test
    void editingLockIsDroppedByHolderOrAdmin() {
        SharedPlacement editing = p(false, OTHER);
        assertThat(LockRules.canReleaseEdit(editing, other)).isNull();
        assertThat(LockRules.canReleaseEdit(editing, admin)).isNull();
        assertThat(LockRules.canReleaseEdit(editing, owner)).isNotNull();
    }

    @Test
    void placementLimitSparesAdmins() {
        assertThat(LockRules.canCreate(other, 3, 3)).contains("limit 3");
        assertThat(LockRules.canCreate(other, 2, 3)).isNull();
        assertThat(LockRules.canCreate(admin, 99, 3)).isNull();
        assertThat(LockRules.canCreate(viewer, 0, 3)).isNotNull();
    }

    @Test
    void quotaRules() {
        SyncConfig c = new SyncConfig();
        c.maxFileSize = 1000;
        c.playerQuota = 1500;
        c.totalQuota = 3000;
        assertThat(SyncServer.checkQuota(c, other, 1001, 0, 0, 0)).startsWith("Too large");
        assertThat(SyncServer.checkQuota(c, other, 800, 800, 800, 0)).startsWith("Over your quota");
        assertThat(SyncServer.checkQuota(c, admin, 800, 800, 800, 0)).isNull();
        assertThat(SyncServer.checkQuota(c, admin, 800, 0, 2000, 300)).contains("full");
        assertThat(SyncServer.checkQuota(c, other, 0, 0, 0, 0)).isNotNull();
        assertThat(SyncServer.checkQuota(c, other, 700, 800, 800, 0)).isNull();
    }

    @Test
    void configPermissionAccess() {
        SyncConfig c = new SyncConfig();
        assertThat(c.allows(Permission.UPLOAD, false)).isTrue();
        assertThat(c.allows(Permission.ADMIN, false)).isFalse();
        assertThat(c.allows(Permission.ADMIN, true)).isTrue();
        c.access.put(Permission.UPLOAD, SyncConfig.Access.NOBODY);
        assertThat(c.allows(Permission.UPLOAD, true)).isFalse();
    }
}
