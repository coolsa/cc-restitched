/*
 * This file is part of ComputerCraft - http://www.computercraft.info
 * Copyright Daniel Ratcliffe, 2011-2020. Do not distribute without permission.
 * Send enquiries to dratcliffe@gmail.com
 */

package dan200.computercraft.shared.turtle.upgrades;

import java.util.List;
import java.util.function.Function;

import javax.annotation.Nonnull;

import dan200.computercraft.ComputerCraft;
import dan200.computercraft.api.client.TransformedModel;
import dan200.computercraft.api.turtle.AbstractTurtleUpgrade;
import dan200.computercraft.api.turtle.ITurtleAccess;
import dan200.computercraft.api.turtle.TurtleCommandResult;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.api.turtle.TurtleUpgradeType;
import dan200.computercraft.api.turtle.TurtleVerb;
import dan200.computercraft.api.turtle.event.TurtleAttackEvent;
import dan200.computercraft.api.turtle.event.TurtleBlockEvent;
import dan200.computercraft.api.turtle.event.TurtleEvent;
import dan200.computercraft.shared.TurtlePermissions;
import dan200.computercraft.shared.turtle.core.TurtlePlaceCommand;
import dan200.computercraft.shared.turtle.core.TurtlePlayer;
import dan200.computercraft.shared.util.DropConsumer;
import dan200.computercraft.shared.util.FillableMatrix4f;
import dan200.computercraft.shared.util.InventoryUtil;
import dan200.computercraft.shared.util.WorldUtil;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.util.math.*;
import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.util.math.AffineTransformation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

public class TurtleTool extends AbstractTurtleUpgrade {
    protected final ItemStack item;

    public TurtleTool(Identifier id, String adjective, Item item) {
        super(id, TurtleUpgradeType.TOOL, adjective, item);
        this.item = new ItemStack(item);
    }

    public TurtleTool(Identifier id, Item item) {
        super(id, TurtleUpgradeType.TOOL, item);
        this.item = new ItemStack(item);
    }

    public TurtleTool(Identifier id, ItemStack craftItem, ItemStack toolItem) {
        super(id, TurtleUpgradeType.TOOL, craftItem);
        this.item = toolItem;
    }

    @Nonnull
    @Override
    public TurtleCommandResult useTool(@Nonnull ITurtleAccess turtle, @Nonnull TurtleSide side, @Nonnull TurtleVerb verb, @Nonnull Direction direction) {
        switch (verb) {
        case ATTACK:
            return this.attack(turtle, direction, side);
        case DIG:
            return this.dig(turtle, direction, side);
        default:
            return TurtleCommandResult.failure("Unsupported action");
        }
    }

    @Nonnull
    @Override
    @Environment (EnvType.CLIENT)
    public TransformedModel getModel(ITurtleAccess turtle, @Nonnull TurtleSide side) {
        float xOffset = side == TurtleSide.LEFT ? -0.40625f : 0.40625f;
        return TransformedModel.of(this.getCraftingItem(), new AffineTransformation(new Vector3f(xOffset + 1, 0, 1), Vector3f.POSITIVE_Y.getDegreesQuaternion(270), new Vector3f(1, 1, 1), Vector3f.POSITIVE_Z.getDegreesQuaternion(90)));
    }

    private TurtleCommandResult attack(final ITurtleAccess turtle, Direction direction, TurtleSide side) {
        // Create a fake player, and orient it appropriately
        final World world = turtle.getWorld();
        final BlockPos position = turtle.getPosition();
        final TurtlePlayer turtlePlayer = TurtlePlaceCommand.createPlayer(turtle, position, direction);

        // See if there is an entity present
        Vec3d turtlePos = turtlePlayer.getPos();
        Vec3d rayDir = turtlePlayer.getRotationVec(1.0f);
        Pair<Entity, Vec3d> hit = WorldUtil.rayTraceEntities(world, turtlePos, rayDir, 1.5);
        if (hit != null) {
            // Load up the turtle's inventory
            ItemStack stackCopy = this.item.copy();
            turtlePlayer.loadInventory(stackCopy);

            Entity hitEntity = hit.getKey();

            // Fire several events to ensure we have permissions.
            if (AttackEntityCallback.EVENT.invoker()
                                          .interact(turtlePlayer,
                                                    world,
                                                    Hand.MAIN_HAND,
                                                    hitEntity,
                                                    null) == ActionResult.FAIL || !hitEntity.isAttackable()) {
                return TurtleCommandResult.failure("Nothing to attack here");
            }

            TurtleAttackEvent attackEvent = new TurtleAttackEvent(turtle, turtlePlayer, hitEntity, this, side);
            if (TurtleEvent.post(attackEvent)) {
                return TurtleCommandResult.failure(attackEvent.getFailureMessage());
            }

            // Start claiming entity drops
            DropConsumer.set(hitEntity, turtleDropConsumer(turtle));

            // Attack the entity
            boolean attacked = false;
            if (!hitEntity.handleAttack(turtlePlayer)) {
                float damage = (float) turtlePlayer.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
                damage *= this.getDamageMultiplier();
                if (damage > 0.0f) {
                    DamageSource source = DamageSource.player(turtlePlayer);
                    if (hitEntity instanceof ArmorStandEntity) {
                        // Special case for armor stands: attack twice to guarantee destroy
                        hitEntity.damage(source, damage);
                        if (hitEntity.isAlive()) {
                            hitEntity.damage(source, damage);
                        }
                        attacked = true;
                    } else {
                        if (hitEntity.damage(source, damage)) {
                            attacked = true;
                        }
                    }
                }
            }

            // Stop claiming drops
            stopConsuming(turtle);

            // Put everything we collected into the turtles inventory, then return
            if (attacked) {
                turtlePlayer.unloadInventory(turtle);
                return TurtleCommandResult.success();
            }
        }

        return TurtleCommandResult.failure("Nothing to attack here");
    }

    private TurtleCommandResult dig(ITurtleAccess turtle, Direction direction, TurtleSide side) {
        // Get ready to dig
        World world = turtle.getWorld();
        BlockPos turtlePosition = turtle.getPosition();
        BlockPos blockPosition = turtlePosition.offset(direction);

        if (world.isAir(blockPosition) || WorldUtil.isLiquidBlock(world, blockPosition)) {
            return TurtleCommandResult.failure("Nothing to dig here");
        }

        BlockState state = world.getBlockState(blockPosition);
        FluidState fluidState = world.getFluidState(blockPosition);

        TurtlePlayer turtlePlayer = TurtlePlaceCommand.createPlayer(turtle, turtlePosition, direction);
        turtlePlayer.loadInventory(this.item.copy());

        if (ComputerCraft.turtlesObeyBlockProtection) {
            if (!TurtlePermissions.isBlockEditable(world, blockPosition, turtlePlayer)) {
                return TurtleCommandResult.failure("Cannot break protected block");
            }
        }

        // Check if we can break the block
        if (!this.canBreakBlock(state, world, blockPosition, turtlePlayer)) {
            return TurtleCommandResult.failure("Unbreakable block detected");
        }

        // Fire the dig event, checking whether it was cancelled.
        TurtleBlockEvent.Dig digEvent = new TurtleBlockEvent.Dig(turtle, turtlePlayer, world, blockPosition, state, this, side);
        if (TurtleEvent.post(digEvent)) {
            return TurtleCommandResult.failure(digEvent.getFailureMessage());
        }

        // Consume the items the block drops
        DropConsumer.set(world, blockPosition, turtleDropConsumer(turtle));

        BlockEntity tile = world.getBlockEntity(blockPosition);

        // Much of this logic comes from PlayerInteractionManager#tryHarvestBlock, so it's a good idea
        // to consult there before making any changes.

        // Play the destruction sound and particles
        world.syncWorldEvent(2001, blockPosition, Block.getRawIdFromState(state));

        // Destroy the block
        state.getBlock()
             .onBroken(world, blockPosition, state);
        if (world.removeBlock(blockPosition, false)) {
            state.getBlock()
                 .onBroken(world, blockPosition, state);
            if (turtlePlayer.isUsingEffectiveTool(state)) {
                state.getBlock()
                     .afterBreak(world, turtlePlayer, blockPosition, state, tile, turtlePlayer.getMainHandStack());
            }
        }

        stopConsuming(turtle);

        return TurtleCommandResult.success();

    }

    private static Function<ItemStack, ItemStack> turtleDropConsumer(ITurtleAccess turtle) {
        return drop -> InventoryUtil.storeItems(drop, turtle.getItemHandler(), turtle.getSelectedSlot());
    }

    protected float getDamageMultiplier() {
        return 3.0f;
    }

    private static void stopConsuming(ITurtleAccess turtle) {
        List<ItemStack> extra = DropConsumer.clear();
        for (ItemStack remainder : extra) {
            WorldUtil.dropItemStack(remainder,
                                    turtle.getWorld(),
                                    turtle.getPosition(),
                                    turtle.getDirection()
                                          .getOpposite());
        }
    }

    protected boolean canBreakBlock(BlockState state, World world, BlockPos pos, TurtlePlayer player) {
        Block block = state.getBlock();
        return !state.isAir() && block != Blocks.BEDROCK && state.calcBlockBreakingDelta(player, world, pos) > 0;
    }
}
