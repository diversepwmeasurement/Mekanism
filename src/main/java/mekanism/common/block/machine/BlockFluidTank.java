package mekanism.common.block.machine;

import java.util.Locale;
import javax.annotation.Nonnull;
import mekanism.api.EnumColor;
import mekanism.api.IMekWrench;
import mekanism.common.Mekanism;
import mekanism.common.base.IActiveState;
import mekanism.common.base.IComparatorSupport;
import mekanism.common.base.ISustainedInventory;
import mekanism.common.base.ISustainedTank;
import mekanism.common.block.BlockMekanismContainer;
import mekanism.common.block.interfaces.IColoredBlock;
import mekanism.common.block.interfaces.IHasGui;
import mekanism.common.block.interfaces.IHasModel;
import mekanism.common.block.interfaces.ITieredBlock;
import mekanism.common.block.states.BlockStateHelper;
import mekanism.common.block.states.IStateActive;
import mekanism.common.block.states.IStateFacing;
import mekanism.common.config.MekanismConfig;
import mekanism.common.integration.wrenches.Wrenches;
import mekanism.common.security.ISecurityItem;
import mekanism.common.tier.FluidTankTier;
import mekanism.common.tile.TileEntityFluidTank;
import mekanism.common.tile.prefab.TileEntityBasicBlock;
import mekanism.common.util.FluidContainerUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.PipeUtils;
import mekanism.common.util.SecurityUtils;
import mekanism.common.util.StackUtils;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Explosion;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class BlockFluidTank extends BlockMekanismContainer implements IHasModel, IHasGui, IColoredBlock, IStateFacing, IStateActive, ITieredBlock<FluidTankTier> {

    private static final AxisAlignedBB TANK_BOUNDS = new AxisAlignedBB(0.125F, 0.0F, 0.125F, 0.875F, 1.0F, 0.875F);

    private final FluidTankTier tier;

    public BlockFluidTank(FluidTankTier tier) {
        super(Material.IRON);
        this.tier = tier;
        setHardness(3.5F);
        setResistance(16F);
        setRegistryName(new ResourceLocation(Mekanism.MODID, tier.getBaseTier().getSimpleName().toLowerCase(Locale.ROOT) + "_fluid_tank"));
    }

    public static boolean isInstance(ItemStack stack) {
        //TODO: Do some better sort of isInstance check? Once we have separate ItemBlock implementations can compare the getItem() to that
        return !stack.isEmpty() && stack.getItem() instanceof ItemBlock && ((ItemBlock) stack.getItem()).getBlock() instanceof BlockFluidTank;
    }

    @Override
    public FluidTankTier getTier() {
        return tier;
    }

    @Nonnull
    @Override
    public BlockStateContainer createBlockState() {
        return BlockStateHelper.getBlockState(this);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        //TODO
        return 0;
    }

    @Nonnull
    @Override
    @Deprecated
    public IBlockState getActualState(@Nonnull IBlockState state, IBlockAccess world, BlockPos pos) {
        return BlockStateHelper.getActualState(this, state, MekanismUtils.getTileEntitySafe(world, pos));
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
        TileEntityBasicBlock tileEntity = (TileEntityBasicBlock) world.getTileEntity(pos);
        if (tileEntity == null) {
            return;
        }

        EnumFacing change = EnumFacing.SOUTH;
        if (tileEntity.canSetFacing(EnumFacing.DOWN) && tileEntity.canSetFacing(EnumFacing.UP)) {
            int height = Math.round(placer.rotationPitch);
            if (height >= 65) {
                change = EnumFacing.UP;
            } else if (height <= -65) {
                change = EnumFacing.DOWN;
            }
        }

        if (change != EnumFacing.DOWN && change != EnumFacing.UP) {
            int side = MathHelper.floor((placer.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3;
            switch (side) {
                case 0:
                    change = EnumFacing.NORTH;
                    break;
                case 1:
                    change = EnumFacing.EAST;
                    break;
                case 2:
                    change = EnumFacing.SOUTH;
                    break;
                case 3:
                    change = EnumFacing.WEST;
                    break;
            }
        }

        tileEntity.setFacing(change);
        tileEntity.redstone = world.getRedstonePowerFromNeighbors(pos) > 0;
    }

    @Override
    public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
        if (MekanismConfig.current().client.enableAmbientLighting.val()) {
            TileEntity tileEntity = MekanismUtils.getTileEntitySafe(world, pos);
            if (tileEntity instanceof IActiveState && ((IActiveState) tileEntity).lightUpdate() && ((IActiveState) tileEntity).wasActiveRecently()) {
                return MekanismConfig.current().client.ambientLightingLevel.val();
            }
        }
        return 0;
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer entityplayer, EnumHand hand, EnumFacing side, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntityBasicBlock tileEntity = (TileEntityBasicBlock) world.getTileEntity(pos);
        ItemStack stack = entityplayer.getHeldItem(hand);
        if (!stack.isEmpty()) {
            IMekWrench wrenchHandler = Wrenches.getHandler(stack);
            if (wrenchHandler != null) {
                RayTraceResult raytrace = new RayTraceResult(new Vec3d(hitX, hitY, hitZ), side, pos);
                if (wrenchHandler.canUseWrench(entityplayer, hand, stack, raytrace)) {
                    if (SecurityUtils.canAccess(entityplayer, tileEntity)) {
                        wrenchHandler.wrenchUsed(entityplayer, hand, stack, raytrace);
                        if (entityplayer.isSneaking()) {
                            MekanismUtils.dismantleBlock(this, state, world, pos);
                            return true;
                        }
                        if (tileEntity != null) {
                            EnumFacing change = tileEntity.facing.rotateY();
                            tileEntity.setFacing(change);
                            world.notifyNeighborsOfStateChange(pos, this, true);
                        }
                    } else {
                        SecurityUtils.displayNoAccess(entityplayer);
                    }
                    return true;
                }
            }
        }

        if (tileEntity != null) {
            if (!entityplayer.isSneaking()) {
                if (SecurityUtils.canAccess(entityplayer, tileEntity)) {
                    if (!stack.isEmpty() && FluidContainerUtils.isFluidContainer(stack)) {
                        if (manageInventory(entityplayer, (TileEntityFluidTank) tileEntity, hand, stack)) {
                            entityplayer.inventory.markDirty();
                            return true;
                        }
                    } else {
                        entityplayer.openGui(Mekanism.instance, getGuiID(), world, pos.getX(), pos.getY(), pos.getZ());
                    }
                } else {
                    SecurityUtils.displayNoAccess(entityplayer);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public TileEntity createTileEntity(@Nonnull World world, @Nonnull IBlockState state) {
        return new TileEntityFluidTank(tier);
    }

    @Override
    @Deprecated
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @SideOnly(Side.CLIENT)
    @Nonnull
    @Override
    public BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT;
    }

    @Override
    @Deprecated
    public float getPlayerRelativeBlockHardness(IBlockState state, @Nonnull EntityPlayer player, @Nonnull World world, @Nonnull BlockPos pos) {
        TileEntity tile = world.getTileEntity(pos);
        return SecurityUtils.canAccess(player, tile) ? super.getPlayerRelativeBlockHardness(state, player, world, pos) : 0.0F;
    }

    @Override
    public float getExplosionResistance(World world, BlockPos pos, Entity exploder, Explosion explosion) {
        //TODO: This is how it was before, but should it be divided by 5 like in Block.java
        return blockResistance;
    }

    @Override
    @Deprecated
    public boolean hasComparatorInputOverride(IBlockState state) {
        return true;
    }

    @Override
    @Deprecated
    public int getComparatorInputOverride(IBlockState state, World world, BlockPos pos) {
        TileEntity tileEntity = world.getTileEntity(pos);
        if (tileEntity instanceof IComparatorSupport) {
            return ((IComparatorSupport) tileEntity).getRedstoneLevel();
        }
        return 0;
    }

    private boolean manageInventory(EntityPlayer player, TileEntityFluidTank tileEntity, EnumHand hand, ItemStack itemStack) {
        ItemStack copyStack = StackUtils.size(itemStack.copy(), 1);
        if (FluidContainerUtils.isFluidContainer(itemStack)) {
            IFluidHandlerItem handler = FluidUtil.getFluidHandler(copyStack);
            if (FluidUtil.getFluidContained(copyStack) == null) {
                if (tileEntity.fluidTank.getFluid() != null) {
                    int filled = handler.fill(tileEntity.fluidTank.getFluid(), !player.capabilities.isCreativeMode);
                    copyStack = handler.getContainer();
                    if (filled > 0) {
                        if (itemStack.getCount() == 1) {
                            player.setHeldItem(hand, copyStack);
                        } else if (itemStack.getCount() > 1 && player.inventory.addItemStackToInventory(copyStack)) {
                            itemStack.shrink(1);
                        } else {
                            player.dropItem(copyStack, false, true);
                            itemStack.shrink(1);
                        }
                        if (tileEntity.tier != FluidTankTier.CREATIVE) {
                            tileEntity.fluidTank.drain(filled, true);
                        }
                        return true;
                    }
                }
            } else {
                FluidStack itemFluid = FluidUtil.getFluidContained(copyStack);
                int needed = tileEntity.getCurrentNeeded();
                if (tileEntity.fluidTank.getFluid() != null && !tileEntity.fluidTank.getFluid().isFluidEqual(itemFluid)) {
                    return false;
                }
                boolean filled = false;
                FluidStack drained = handler.drain(needed, !player.capabilities.isCreativeMode);
                copyStack = handler.getContainer();
                if (copyStack.getCount() == 0) {
                    copyStack = ItemStack.EMPTY;
                }
                if (drained != null) {
                    if (player.capabilities.isCreativeMode) {
                        filled = true;
                    } else if (!copyStack.isEmpty()) {
                        if (itemStack.getCount() == 1) {
                            player.setHeldItem(hand, copyStack);
                            filled = true;
                        } else if (player.inventory.addItemStackToInventory(copyStack)) {
                            itemStack.shrink(1);

                            filled = true;
                        }
                    } else {
                        itemStack.shrink(1);
                        if (itemStack.getCount() == 0) {
                            player.setHeldItem(hand, ItemStack.EMPTY);
                        }
                        filled = true;
                    }

                    if (filled) {
                        int toFill = tileEntity.fluidTank.getCapacity() - tileEntity.fluidTank.getFluidAmount();
                        if (tileEntity.tier != FluidTankTier.CREATIVE) {
                            toFill = Math.min(toFill, drained.amount);
                        }
                        tileEntity.fluidTank.fill(PipeUtils.copy(drained, toFill), true);
                        if (drained.amount - toFill > 0) {
                            tileEntity.pushUp(PipeUtils.copy(itemFluid, drained.amount - toFill), true);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    @Deprecated
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block neighborBlock, BlockPos neighborPos) {
        if (!world.isRemote) {
            TileEntity tileEntity = world.getTileEntity(pos);
            if (tileEntity instanceof TileEntityBasicBlock) {
                ((TileEntityBasicBlock) tileEntity).onNeighborChange(neighborBlock);
            }
        }
    }

    @Nonnull
    @Override
    protected ItemStack getDropItem(@Nonnull IBlockState state, @Nonnull IBlockAccess world, @Nonnull BlockPos pos) {
        TileEntityFluidTank tile = (TileEntityFluidTank) world.getTileEntity(pos);
        ItemStack itemStack = new ItemStack(this);
        if (tile == null) {
            return itemStack;
        }
        if (!itemStack.hasTagCompound()) {
            itemStack.setTagCompound(new NBTTagCompound());
        }
        //Security
        ISecurityItem securityItem = (ISecurityItem) itemStack.getItem();
        securityItem.setOwnerUUID(itemStack, tile.getSecurity().getOwnerUUID());
        securityItem.setSecurity(itemStack, tile.getSecurity().getMode());
        //Sustained Inventory
        if (tile.inventory.size() > 0) {
            ISustainedInventory inventory = (ISustainedInventory) itemStack.getItem();
            inventory.setInventory(tile.getInventory(), itemStack);
        }
        //Sustained Tank
        if (tile.getFluidStack() != null) {
            ISustainedTank sustainedTank = (ISustainedTank) itemStack.getItem();
            if (sustainedTank.hasTank(itemStack)) {
                sustainedTank.setFluidStack(tile.getFluidStack(), itemStack);
            }
        }
        return itemStack;
    }

    @Nonnull
    @Override
    @Deprecated
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return TANK_BOUNDS;
    }

    @Override
    @Deprecated
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    @Deprecated
    public boolean isSideSolid(IBlockState state, @Nonnull IBlockAccess world, @Nonnull BlockPos pos, EnumFacing side) {
        return side == EnumFacing.UP || side == EnumFacing.DOWN;
    }

    @Nonnull
    @Override
    @Deprecated
    public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
        return face != EnumFacing.UP && face != EnumFacing.DOWN ? BlockFaceShape.UNDEFINED : BlockFaceShape.SOLID;
    }

    @Override
    public int getGuiID() {
        return 41;
    }

    @Override
    public EnumColor getColor() {
        return getTier().getBaseTier().getColor();
    }
}