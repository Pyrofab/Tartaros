/*
 * Requiem
 * Copyright (C) 2017-2021 Ladysnake
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
 *
 * Linking this mod statically or dynamically with other
 * modules is making a combined work based on this mod.
 * Thus, the terms and conditions of the GNU General Public License cover the whole combination.
 *
 * In addition, as a special exception, the copyright holders of
 * this mod give you permission to combine this mod
 * with free software programs or libraries that are released under the GNU LGPL
 * and with code included in the standard release of Minecraft under All Rights Reserved (or
 * modified versions of such code, with unchanged license).
 * You may copy and distribute such a system following the terms of the GNU GPL for this mod
 * and the licenses of the other code concerned.
 *
 * Note that people who make modified versions of this mod are not obligated to grant
 * this special exception for their modified versions; it is their choice whether to do so.
 * The GNU General Public License gives permission to release a modified version without this exception;
 * this exception also makes it possible to release a modified version which carries forward this exception.
 */
package ladysnake.requiem.common.network;

import ladysnake.requiem.api.v1.dialogue.DialogueTracker;
import ladysnake.requiem.api.v1.entity.MovementAlterer;
import ladysnake.requiem.api.v1.entity.ability.AbilityType;
import ladysnake.requiem.api.v1.entity.ability.MobAbilityController;
import ladysnake.requiem.api.v1.event.requiem.InitiateFractureCallback;
import ladysnake.requiem.api.v1.possession.PossessionComponent;
import ladysnake.requiem.api.v1.remnant.RemnantComponent;
import ladysnake.requiem.api.v1.remnant.RemnantType;
import ladysnake.requiem.common.item.OpusDemoniumItem;
import ladysnake.requiem.common.item.RequiemItems;
import ladysnake.requiem.common.remnant.RemnantTypes;
import ladysnake.requiem.common.tag.RequiemEntityTypeTags;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import static ladysnake.requiem.common.network.RequiemNetworking.*;

public class ServerMessageHandling {

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(USE_DIRECT_ABILITY, (server, player, handler, buf, responseSender) -> {
            AbilityType type = buf.readEnumConstant(AbilityType.class);
            int entityId = buf.readVarInt();
            server.execute(() -> {
                MobAbilityController abilityController = MobAbilityController.get(player);
                Entity targetedEntity = player.world.getEntityById(entityId);

                // allow a slightly longer reach in case of lag
                if (targetedEntity != null && (abilityController.getRange(type) + 3) > targetedEntity.distanceTo(player)) {
                    abilityController.useDirect(type, targetedEntity);
                }

                // sync abilities in case the server disagrees with the client's guess
                MobAbilityController.KEY.sync(player);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(USE_INDIRECT_ABILITY, (server, player, handler, buf, responseSender) -> {
            AbilityType type = buf.readEnumConstant(AbilityType.class);
            server.execute(() -> MobAbilityController.get(player).useIndirect(type));
        });
        ServerPlayNetworking.registerGlobalReceiver(ETHEREAL_FRACTURE, (server, player, handler, buf, responseSender) -> server.execute(() -> {
            RemnantComponent remnantState = RemnantComponent.get(player);

            if (remnantState.getRemnantType().isDemon()) {
                PossessionComponent possessionComponent = PossessionComponent.get(player);
                MobEntity possessedEntity = possessionComponent.getPossessedEntity();
                if (possessedEntity != null && RemnantComponent.get(player).canDissociateFrom(possessedEntity)) {
                    possessionComponent.stopPossessing();
                    RequiemNetworking.sendEtherealAnimationMessage(player);
                } else {
                    InitiateFractureCallback.EVENT.invoker().performFracture(player);
                }
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(OPUS_UPDATE, (server, player, handler, buf, responseSender) -> {
            String content = buf.readString(32767);
            boolean sign = buf.readBoolean();
            RemnantType type = sign ? RemnantTypes.get(buf.readIdentifier()) : null;
            Hand hand = buf.readEnumConstant(Hand.class);
            server.execute(() -> {
                ItemStack book = player.getStackInHand(hand);
                if (book.getItem() != RequiemItems.OPUS_DEMONIUM) {
                    return;
                }
                int requiredXp = player.isCreative() ? 0 : OpusDemoniumItem.REQUIRED_CONVERSION_XP;
                if (sign && player.experienceLevel >= requiredXp) {
                    player.setStackInHand(hand, type.getConversionBook(player));
                    player.world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.BLOCKS, 1.0F, player.world.random.nextFloat() * 0.1F + 0.9F);
                    player.experienceLevel -= requiredXp;
                    if (player.experienceLevel < 0) {
                        player.experienceLevel = 0;
                        player.experienceProgress = 0.0F;
                        player.totalExperience = 0;
                    }
                    player.networkHandler.sendPacket(new ExperienceBarUpdateS2CPacket(player.experienceProgress, player.experienceLevel, player.experienceLevel));
                } else {
                    ListTag pages = new ListTag();
                    pages.add(StringTag.of(content));
                    book.putSubTag("pages", pages);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(DIALOGUE_ACTION, (server, player, handler, buf, responseSender) -> {
            Identifier action = buf.readIdentifier();
            server.execute(() -> DialogueTracker.get(player).handleAction(action));
        });
        ServerPlayNetworking.registerGlobalReceiver(HUGGING_WALL, (server, player, handler, buf, responseSender) -> {
            boolean yes = buf.readBoolean();
            // Possible failure points: the player may not actually be against a block, or it may not have the right movement
            // we do not handle those right now, as movement is entirely done clientside
            server.execute(() -> MovementAlterer.get(player).hugWall(yes));
        });
        ServerPlayNetworking.registerGlobalReceiver(OPEN_CRAFTING_MENU, (server, player, handler, buf, responseSender) -> server.execute(() -> {
            MobEntity possessed = PossessionComponent.get(player).getPossessedEntity();
            if (possessed != null && RequiemEntityTypeTags.SUPERCRAFTERS.contains(possessed.getType())) {
                player.openHandledScreen(Blocks.CRAFTING_TABLE.getDefaultState().createScreenHandlerFactory(player.world, player.getBlockPos()));
            }
        }));
    }
}
