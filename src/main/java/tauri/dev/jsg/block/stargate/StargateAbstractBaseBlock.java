package tauri.dev.jsg.block.stargate;

import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Explosion;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import tauri.dev.jsg.JSG;
import tauri.dev.jsg.block.TechnicalBlock;
import tauri.dev.jsg.config.JSGConfig;
import tauri.dev.jsg.creativetabs.JSGCreativeTabsHandler;
import tauri.dev.jsg.stargate.EnumMemberVariant;
import tauri.dev.jsg.stargate.merging.StargateAbstractMergeHelper;
import tauri.dev.jsg.tileentity.stargate.StargateAbstractBaseTile;
import tauri.dev.jsg.util.main.JSGProps;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;

public abstract class StargateAbstractBaseBlock extends TechnicalBlock {

    public StargateAbstractBaseBlock(String blockName) {
        super(Material.IRON);

        setRegistryName(JSG.MOD_ID + ":" + blockName);
        setUnlocalizedName(JSG.MOD_ID + "." + blockName);

        setSoundType(SoundType.METAL);
        setCreativeTab(JSGCreativeTabsHandler.JSG_GATES_CREATIVE_TAB);

        setDefaultState(blockState.getBaseState().withProperty(JSGProps.FACING_HORIZONTAL, EnumFacing.NORTH).withProperty(JSGProps.FACING_VERTICAL, EnumFacing.SOUTH).withProperty(JSGProps.RENDER_BLOCK, true));

        setLightOpacity(0);
        setHardness(JSGConfig.Stargate.mechanics.enableGateDisassembleWrench ? -1 : 5);
        setResistance(60.0f);
        if(JSGConfig.Stargate.mechanics.enableGateDisassembleWrench)
            setHarvestLevel("wrench", -1);
        else
            setHarvestLevel("pickaxe", 2);

    }

    @Override
    @ParametersAreNonnullByDefault
    public boolean canEntityDestroy(IBlockState state, IBlockAccess world, BlockPos pos, Entity entity){
        return false;
    }

    public void destroyAndGiveDrops(boolean isShifting, EntityPlayer player, World world, BlockPos pos, EnumHand hand, IBlockState state) {
        if (isShifting) {
            collectGate(player, hand, world, pos, state);
            return;
        }

        dropBlockAsItem(world, pos, state, -2);
        world.setBlockToAir(pos);
        player.getHeldItem(hand).damageItem(1, player);
    }

    protected void collectGate(EntityPlayer player, EnumHand hand, World world, BlockPos basePos, IBlockState state) {
        ItemStack toolStack = player.getHeldItem(hand);
        if (toolStack.isEmpty()) return;
        final StargateAbstractBaseTile gateTile = (StargateAbstractBaseTile) world.getTileEntity(basePos);
        final EnumFacing facing = Objects.requireNonNull(gateTile).getFacing();
        final EnumFacing facingVertical = gateTile.getFacingVertical();

        StargateAbstractMergeHelper mergeHelper = gateTile.getMergeHelper();


        // Collect member blocks
        for (EnumMemberVariant variant : EnumMemberVariant.values()) {
            List<BlockPos> posList = mergeHelper.getPlacedBlockPositions(world, basePos, facing, facingVertical, variant);

            if (posList.isEmpty()) continue;
            for (BlockPos pos : posList) {

                if (toolStack.isEmpty()) return;
                IBlockState memberState = world.getBlockState(pos);

                List<ItemStack> stacks = memberState.getBlock().getDrops(world, pos, memberState, -2);
                boolean shouldSkip = false;
                for (ItemStack stack : stacks) {
                    if (!player.addItemStackToInventory(stack)) {
                        shouldSkip = true;
                    }
                }

                if (shouldSkip) continue;
                toolStack.damageItem(1, player);

                SoundType soundtype = memberState.getBlock().getSoundType(memberState, world, pos, player);
                world.playSound(null, pos, soundtype.getBreakSound(), SoundCategory.BLOCKS, (soundtype.getVolume() + 1.0F) / 2.0F, soundtype.getPitch() * 0.8F);

                world.setBlockToAir(pos);

                gateTile.updateMergeState(false, facing, facingVertical);
            }
        }

        // Collect base block
        if (toolStack.isEmpty()) return;
        List<ItemStack> stacks = state.getBlock().getDrops(world, basePos, state, -2);
        boolean shouldSkip = false;
        for (ItemStack stack : stacks) {
            if (!player.addItemStackToInventory(stack)) {
                shouldSkip = true;
            }
        }

        if (shouldSkip) return;
        toolStack.damageItem(1, player);

        world.setBlockToAir(basePos);
    }

    // -----------------------------------
    // Explosions

    @Override
    public void onBlockDestroyedByExplosion(World worldIn, BlockPos pos, @Nonnull Explosion explosionIn) {
        worldIn.newExplosion(null, pos.getX(), pos.getY(), pos.getZ(), 30, true, true).doExplosionA();
    }


    // --------------------------------------------------------------------------------------
    // Block states

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, JSGProps.FACING_HORIZONTAL, JSGProps.FACING_VERTICAL, JSGProps.RENDER_BLOCK);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return (state.getValue(JSGProps.RENDER_BLOCK) ? 0x04 : 0) | state.getValue(JSGProps.FACING_HORIZONTAL).getHorizontalIndex();
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(JSGProps.RENDER_BLOCK, (meta & 0x04) != 0).withProperty(JSGProps.FACING_HORIZONTAL, EnumFacing.getHorizontal(meta & 0x03));
    }


    // ------------------------------------------------------------------------
    // Block behavior

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            if (tryBreak(player.getHeldItem(hand), false, player.isSneaking(), player, world, pos, hand, state))
                return true;
            if (!player.isSneaking() && !tryAutobuild(player, world, pos, hand)) {
                showGateInfo(player, hand, world, pos);
            }
        }
        return !player.isSneaking();
    }

    protected abstract void showGateInfo(EntityPlayer player, EnumHand hand, World world, BlockPos pos);

    protected boolean tryAutobuild(EntityPlayer player, World world, BlockPos basePos, EnumHand hand) {
        final StargateAbstractBaseTile gateTile = (StargateAbstractBaseTile) world.getTileEntity(basePos);
        final EnumFacing facing = gateTile.getFacing();
        final EnumFacing facingVertical = gateTile.getFacingVertical();

        StargateAbstractMergeHelper mergeHelper = gateTile.getMergeHelper();
        ItemStack stack = player.getHeldItem(hand);

        if (!gateTile.isMerged()) {

            // This check ensures that stack represents matching member block.
            EnumMemberVariant variant = mergeHelper.getMemberVariantFromItemStack(stack);

            if (variant != null) {
                List<BlockPos> posList = mergeHelper.getAbsentBlockPositions(world, basePos, facing, facingVertical, variant);

                if (!posList.isEmpty()) {
                    BlockPos pos = posList.get(0);

                    if (world.getBlockState(pos).getBlock().isReplaceable(world, pos)) {
                        IBlockState memberState = mergeHelper.getMemberBlock().getDefaultState();
                        world.setBlockState(pos, createMemberState(memberState, facing, facingVertical, stack.getMetadata()));

                        SoundType soundtype = memberState.getBlock().getSoundType(memberState, world, pos, player);
                        world.playSound(null, pos, soundtype.getBreakSound(), SoundCategory.BLOCKS, (soundtype.getVolume() + 1.0F) / 2.0F, soundtype.getPitch() * 0.8F);

                        if (!player.capabilities.isCreativeMode) stack.shrink(1);

                        // If it was the last chevron/ring
                        if (posList.size() == 1)
                            gateTile.updateMergeState(gateTile.getMergeHelper().checkBlocks(world, basePos, facing, facingVertical), facing, facingVertical);

                        return true;
                    }
                }
            } // variant == null, wrong block held
        }

        return false;
    }

    protected abstract IBlockState createMemberState(IBlockState memberState, EnumFacing facing, EnumFacing facingVertical, int meta);

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote) {
            StargateAbstractBaseTile gateTile = (StargateAbstractBaseTile) world.getTileEntity(pos);
            gateTile.updateMergeState(false, state.getValue(JSGProps.FACING_HORIZONTAL), state.getValue(JSGProps.FACING_VERTICAL));
            gateTile.onBlockBroken();
        }
    }

    @Override
    public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean willHarvest) {
        if (willHarvest) return true; //If it will harvest, delay deletion of the block until after getDrops
        return super.removedByPlayer(state, world, pos, player, willHarvest);
    }

    @Override
    public void harvestBlock(World world, EntityPlayer player, BlockPos pos, IBlockState state, @Nullable TileEntity te, ItemStack tool) {
        tryBreak(null, true, false, player, world, pos, player.getActiveHand(), state);
        super.harvestBlock(world, player, pos, state, te, tool);
        world.setBlockToAir(pos);
    }

    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        StargateAbstractBaseTile gateTile = (StargateAbstractBaseTile) world.getTileEntity(pos);
        if (gateTile != null)
            gateTile.refresh();
    }

    // --------------------------------------------------------------------------------------
    // TileEntity

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public abstract TileEntity createTileEntity(World world, IBlockState state);


    // --------------------------------------------------------------------------------------
    // Rendering

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        if (state.getValue(JSGProps.RENDER_BLOCK)) return EnumBlockRenderType.MODEL;
        else return EnumBlockRenderType.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullBlock(IBlockState state) {
        return false;
    }

    @Override
    public boolean renderHighlight(IBlockState state) {
        return state.getValue(JSGProps.RENDER_BLOCK);
    }

    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess worldIn, IBlockState state, BlockPos pos, EnumFacing face) {
        return BlockFaceShape.UNDEFINED;
    }
}
