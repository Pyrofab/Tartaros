/*
 * Requiem
 * Copyright (C) 2019 Ladysnake
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses>.
 */
package ladysnake.requiem.api.v1.possession;

import ladysnake.requiem.api.v1.RequiemPlayer;
import ladysnake.requiem.api.v1.event.requiem.PossessionStartCallback;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.CheckForNull;

/**
 * A {@link PossessionComponent} handles a player's possession status.
 */
public interface PossessionComponent {
    /**
     * Attempts to start possessing a mob.
     * <p>
     * Starting possession sets internal state for both the {@link Possessable mob} and the
     * {@link ladysnake.requiem.api.v1.RequiemPlayer player} represented by this component.
     * It will also make any necessary change to the global game state (eg. teleporting the
     * player to the possessed mob, or transferring equipment).
     * <p>
     * This method returns <code>true</code> if the <code>mob</code> has been successfully
     * possessed.
     * <p>
     * After this method returns, and if the attempt is successful, further calls to
     * {@link #getPossessedEntity()} will return <code>mob</code> until either {@link #stopPossessing()} is called,
     * or <code>mob</code> cannot be found, whichever happens first. Likewise, calling {@link Possessable#getPossessor()}
     * on <code>mob</code> will return the player represented by this component.
     * <p>
     * Calling this method is equivalent to calling <code>startPossessing(mob, false)</code>.
     *
     * @param mob      a mob to possess
     * @return <code>true</code> if the attempt succeeded, <code>false</code> otherwise
     * @see #startPossessing(MobEntity, boolean)
     */
    default boolean startPossessing(MobEntity mob) {
        return startPossessing(mob, false);
    }

    /**
     * Attempts to start possessing a mob.
     * <p>
     * Starting possession sets internal state for both the {@link Possessable mob} and the
     * {@link ladysnake.requiem.api.v1.RequiemPlayer player} represented by this component.
     * It will also make any necessary change to the global game state (eg. teleporting the
     * player to the possessed mob, or transferring equipment).
     * <p>
     * This method returns <code>true</code> if the <code>mob</code> has been successfully
     * possessed.
     *
     * <p>
     * If <code>simulate</code> is true, the attempt is simulated.
     * When the attempt is simulated, the state of the game is not altered by this method's execution.
     * This means that this method is effectively pure during simulated possession attempts,
     * in the following sense:
     * <em>If its return value is not used, removing its invocation won't
     * affect program state and change the semantics. Exception throwing is not considered to be a side effect.</em>
     * <p>
     * After this method returns, and if the attempt is successful and not simulated, further calls to
     * {@link #getPossessedEntity()} will return <code>mob</code> until either {@link #stopPossessing()} is called,
     * or <code>mob</code> cannot be found, whichever happens first. Likewise, calling {@link Possessable#getPossessor()}
     * on <code>mob</code> will return the player represented by this component.
     *
     * @param mob      a mob to possess
     * @param simulate whether the possession attempt should only be simulated
     * @return <code>true</code> if the attempt succeeded, <code>false</code> otherwise
     *
     * @implSpec implementations of this method should call
     * {@link PossessionStartCallback#onPossessionAttempted(MobEntity, PlayerEntity, boolean)} before
     * proceeding with the actual possession.
     *
     * @see PossessionStartCallback
     */
    boolean startPossessing(MobEntity mob, boolean simulate);

    /**
     * Stops an ongoing possession.
     * Equipment will be transferred if the player is not in creative.
     */
    default void stopPossessing() {
        this.stopPossessing(!this.asRequiemPlayer().asPlayer().isCreative());
    }

    /**
     * Stops an ongoing possession.
     * @param transfer whether equipment should be transferred to the entity
     */
    void stopPossessing(boolean transfer);

    @CheckForNull
    MobEntity getPossessedEntity();

    boolean isPossessing();

    void startCuring();

    void update();

    RequiemPlayer asRequiemPlayer();

    CompoundTag toTag(CompoundTag tag);

    void fromTag(CompoundTag compound);
}
