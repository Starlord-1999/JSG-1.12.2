package tauri.dev.jsg.tileentity.stargate;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import tauri.dev.jsg.block.JSGBlocks;
import tauri.dev.jsg.config.JSGConfig;
import tauri.dev.jsg.config.ingame.JSGTileEntityConfig;
import tauri.dev.jsg.renderer.biomes.BiomeOverlayEnum;
import tauri.dev.jsg.renderer.dialhomedevice.DHDAbstractRendererState;
import tauri.dev.jsg.renderer.stargate.StargateAbstractRendererState;
import tauri.dev.jsg.renderer.stargate.StargateMilkyWayRendererState;
import tauri.dev.jsg.renderer.stargate.StargateMilkyWayRendererState.StargateMilkyWayRendererStateBuilder;
import tauri.dev.jsg.sound.SoundEventEnum;
import tauri.dev.jsg.sound.SoundPositionedEnum;
import tauri.dev.jsg.sound.StargateSoundEventEnum;
import tauri.dev.jsg.sound.StargateSoundPositionedEnum;
import tauri.dev.jsg.stargate.EnumDialingType;
import tauri.dev.jsg.stargate.EnumScheduledTask;
import tauri.dev.jsg.stargate.EnumStargateState;
import tauri.dev.jsg.stargate.StargateOpenResult;
import tauri.dev.jsg.stargate.merging.StargateAbstractMergeHelper;
import tauri.dev.jsg.stargate.merging.StargateMilkyWayMergeHelper;
import tauri.dev.jsg.stargate.network.*;
import tauri.dev.jsg.state.State;
import tauri.dev.jsg.state.StateTypeEnum;
import tauri.dev.jsg.state.stargate.StargateRendererActionState;
import tauri.dev.jsg.tileentity.dialhomedevice.DHDAbstractTile;
import tauri.dev.jsg.tileentity.dialhomedevice.DHDAbstractTile.DHDUpgradeEnum;
import tauri.dev.jsg.tileentity.dialhomedevice.DHDMilkyWayTile;
import tauri.dev.jsg.tileentity.util.ScheduledTask;
import tauri.dev.jsg.util.ILinkable;
import tauri.dev.jsg.util.LinkingHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

import static tauri.dev.jsg.tileentity.stargate.StargateClassicBaseTile.ConfigOptions.DHD_TOP_LOCK;
import static tauri.dev.jsg.tileentity.stargate.StargateClassicBaseTile.ConfigOptions.SPIN_GATE_INCOMING;


public class StargateMilkyWayBaseTile extends StargateClassicBaseTile implements ILinkable {

    // ------------------------------------------------------------------------
    // Stargate state

    @Override
    protected void disconnectGate() {
        super.disconnectGate();
        resetToDialSymbols();

        if (isLinkedAndDHDOperational()) Objects.requireNonNull(getLinkedDHD(world)).clearSymbols();
    }

    @Override
    protected void failGate() {
        super.failGate();
        resetToDialSymbols();

        if (isLinkedAndDHDOperational()) Objects.requireNonNull(getLinkedDHD(world)).clearSymbols();
    }

    @Override
    public boolean abortDialingSequence() {
        if (super.abortDialingSequence()) {
            toDialSymbols.clear();
            markDirty();
            return true;
        }
        return false;
    }

    @Override
    protected void dialingFailed(StargateOpenResult reason) {
        resetToDialSymbols();
        super.dialingFailed(reason);
    }

    @Override
    protected void addFailedTaskAndPlaySound() {
        addTask(new ScheduledTask(EnumScheduledTask.STARGATE_FAIL, stargateState.dialingComputer() ? 83 : 53));
        playSoundEvent(StargateSoundEventEnum.DIAL_FAILED);
    }


    // ------------------------------------------------------------------------
    // Stargate connection

    @Override
    public void openGate(StargatePos targetGatePos, boolean isInitiating, boolean noxDialing) {
        super.openGate(targetGatePos, isInitiating, noxDialing);
        resetToDialSymbols();

        if (isLinkedAndDHDOperational()) {
            Objects.requireNonNull(getLinkedDHD(world)).activateSymbol(SymbolMilkyWayEnum.BRB);
        }
    }

    @Override
    public void activateDHDSymbolBRB() {
        if (isLinkedAndDHDOperational()) {
            Objects.requireNonNull(getLinkedDHD(world)).activateSymbol(SymbolMilkyWayEnum.BRB);
        }
    }

    public void clearDHDSymbols() {
        if (isLinkedAndDHDOperational()) Objects.requireNonNull(getLinkedDHD(world)).clearSymbols();
    }

    @Override
    public void setConfig(JSGTileEntityConfig config) {
        super.setConfig(config);
        if (isLinked()) {
            DHDAbstractTile dhd = getLinkedDHD(world);
            if (dhd != null) {
                DHDAbstractRendererState state = ((DHDAbstractRendererState) dhd.getState(StateTypeEnum.RENDERER_STATE));
                state.gateConfig = getConfig();
                dhd.sendState(StateTypeEnum.RENDERER_STATE, state);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Stargate Network

    @Override
    public SymbolTypeEnum getSymbolType() {
        return SymbolTypeEnum.MILKYWAY;
    }

    @Override
    public void addSymbolToAddressDHD(SymbolInterface symbol) {
        stargateState = EnumStargateState.DIALING;
        markDirty();
        if (((SymbolMilkyWayEnum) symbol).brb()) {
            attemptOpenAndFail();
            markDirty();
            return;
        }
        addSymbolToAddress(symbol);
        doIncomingAnimation((isNoxDialing ? 1 : 10), false);
        int plusTime = new Random().nextInt(5);

        if(!isNoxDialing) {
            if (stargateWillLock(symbol)) {
                isFinalActive = true;
                if (config.getOption(DHD_TOP_LOCK.id).getBooleanValue())
                    addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_OPEN, 5 + plusTime));
                else
                    addTask(new ScheduledTask(EnumScheduledTask.STARGATE_ACTIVATE_CHEVRON, 10 + plusTime));
            } else
                addTask(new ScheduledTask(EnumScheduledTask.STARGATE_ACTIVATE_CHEVRON, 10 + plusTime));
        }

        sendSignal(null, "stargate_dhd_chevron_engaged", new Object[]{dialedAddress.size(), isFinalActive, symbol.getEnglishName()});

        markDirty();
    }

    @Override
    protected int getMaxChevrons() {
        if(dialingWithoutEnergy || isNoxDialing) return 9;
        return isLinkedAndDHDOperational() && stargateState != EnumStargateState.DIALING_COMPUTER && !getLinkedDHD(world).hasUpgrade(DHDUpgradeEnum.CHEVRON_UPGRADE) ? 7 : 9;
    }

    public boolean dialAddress(StargateAddress address, int symbolCount, boolean withoutEnergy, EnumDialingType dialingType) {
        if (!getStargateState().idle()) return false;
        super.dialAddress(address, symbolCount, withoutEnergy, dialingType);
        for (int i = 0; i < symbolCount; i++) {
            addSymbolToAddressUsingList(address.get(i));
        }
        addSymbolToAddressUsingList(getSymbolType().getOrigin());
        addSymbolToAddressUsingList(SymbolMilkyWayEnum.BRB);
        return true;
    }

    protected List<SymbolMilkyWayEnum> toDialSymbols = new ArrayList<>();

    public void resetToDialSymbols() {
        toDialSymbols.clear();
    }

    public boolean canAddSymbolToList(SymbolInterface symbol) {
        int size = toDialSymbols.size();
        for (SymbolMilkyWayEnum s : toDialSymbols) {
            if (s.brb()) size--;
        }
        if (dialedAddress.size() + size + (this.stargateState.dialing() && !isNoxDialing ? 1 : 0) >= getMaxChevrons()) return false;
        if (toDialSymbols.contains((SymbolMilkyWayEnum) symbol)) return false;

        return super.canAddSymbol(symbol);
    }

    public void addSymbolToAddressUsingList(SymbolInterface targetSymbol) {
        if (targetSymbol != SymbolMilkyWayEnum.BRB && !canAddSymbolToList(targetSymbol)) return;
        if (!(targetSymbol instanceof SymbolMilkyWayEnum)) return;
        if(isNoxDialing){
            addSymbolToAddressByNox(targetSymbol);
            return;
        }
        if (toDialSymbols.contains(targetSymbol)) return;
        toDialSymbols.add((SymbolMilkyWayEnum) targetSymbol);
    }


    @Override
    public void addSymbolToAddress(SymbolInterface symbol) {
        super.addSymbolToAddress(symbol);

        DHDAbstractTile dhd = getLinkedDHD(world);
        if (isLinkedAndDHDOperational() && dhd != null) {
            dhd.activateSymbol(symbol);
        }
    }

    @Override
    public void addSymbolToAddressManual(SymbolInterface targetSymbol, Object context) {
        stargateState = EnumStargateState.DIALING_COMPUTER;

        super.addSymbolToAddressManual(targetSymbol, context);
    }

    @Override
    public void incomingWormhole(int dialedAddressSize) {
        super.incomingWormhole(dialedAddressSize);

        if (isLinkedAndDHDOperational()) {
            getLinkedDHD(world).clearSymbols();
        }

        startIncomingAnimation(dialedAddressSize, 400);
        markDirty();
    }

    @Override
    public void incomingWormhole(int dialedAddressSize, int time) {
        super.incomingWormhole(dialedAddressSize);

        if (isLinkedAndDHDOperational()) {
            getLinkedDHD(world).clearSymbols();
        }
        startIncomingAnimation(dialedAddressSize, time);
        markDirty();
    }

    @Override
    public void startIncomingAnimation(int addressSize, int period) {
        super.startIncomingAnimation(addressSize, period);
        incomingPeriod -= (int) Math.round((double) 20 / addressSize);

        // spin ring
        if (config.getOption(SPIN_GATE_INCOMING.id).getBooleanValue() && incomingPeriod > 9)
            // disable ringsSpin when dialing with DHD or dialing (somehow) fast
            spinRing(1, false, true, incomingPeriod * addressSize);

        markDirty();
    }

    @Override
    protected void lightUpChevronByIncoming(boolean disableAnimation) {
        super.lightUpChevronByIncoming(disableAnimation);
        if (incomingPeriod == -1) return;

        if (!disableAnimation) {
            if (!stargateState.idle()) {
                if (incomingLastChevronLightUp < incomingAddressSize) {
                    playSoundEvent(StargateSoundEventEnum.INCOMING);
                    sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_ACTIVATE, incomingLastChevronLightUp + 9, false);
                } else {
                    addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_OPEN, 1));
                    resetIncomingAnimation();
                    markDirty();
                    return;
                }
            } else {
                stargateState = EnumStargateState.IDLE;
                markDirty();
                sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CLEAR_CHEVRONS, 0, false);
                resetIncomingAnimation();
                markDirty();
                return;
            }
        } else {
            sendRenderingUpdate(StargateRendererActionState.EnumGateAction.LIGHT_UP_CHEVRONS, incomingAddressSize, false);
            playSoundEvent(StargateSoundEventEnum.INCOMING);
            isIncoming = false;
            resetIncomingAnimation();
            markDirty();
        }
        markDirty();
    }


    // ------------------------------------------------------------------------
    // Merging

    @Override
    public void onGateBroken() {
        super.onGateBroken();

        if (isLinked()) {
            getLinkedDHD(world).clearSymbols();
            getLinkedDHD(world).setLinkedGate(null, -1);
            setLinkedDHD(null, -1);
        }
    }

    @Override
    protected void onGateMerged() {
        super.onGateMerged();
        this.updateLinkStatus();
    }

    @Override
    public StargateAbstractMergeHelper getMergeHelper() {
        return StargateMilkyWayMergeHelper.INSTANCE;
    }


    // ------------------------------------------------------------------------
    // Linking

    private BlockPos linkedDHD = null;

    private int linkId = -1;

    @Nullable
    public DHDMilkyWayTile getLinkedDHD(World world) {
        if (linkedDHD == null) return null;

        return (DHDMilkyWayTile) world.getTileEntity(linkedDHD);
    }

    public boolean isLinked() {
        return linkedDHD != null && world.getTileEntity(linkedDHD) instanceof DHDMilkyWayTile;
    }

    public boolean isLinkedAndDHDOperational() {
        if (!isLinked()) return false;

        DHDMilkyWayTile dhdMilkyWayTile = getLinkedDHD(world);
        if (!dhdMilkyWayTile.hasControlCrystal()) return false;

        return true;
    }

    public void setLinkedDHD(BlockPos dhdPos, int linkId) {
        this.linkedDHD = dhdPos;
        this.linkId = linkId;

        markDirty();
    }

    public void updateLinkStatus() {
        if (!isMerged()) return;
        BlockPos closestDhd = LinkingHelper.findClosestUnlinked(world, pos, LinkingHelper.getDhdRange(), JSGBlocks.DHD_BLOCK, this.getLinkId());
        int linkId = LinkingHelper.getLinkId();

        if (closestDhd != null) {
            DHDMilkyWayTile dhdMilkyWayTile = (DHDMilkyWayTile) world.getTileEntity(closestDhd);

            dhdMilkyWayTile.setLinkedGate(pos, linkId);
            setLinkedDHD(closestDhd, linkId);
            markDirty();
        }
    }

    @Override
    public boolean canLinkTo() {
        return isMerged() && !isLinked();
    }

    @Override
    public int getLinkId() {
        return linkId;
    }

    // ------------------------------------------------------------------------
    // NBT

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(@Nonnull NBTTagCompound compound) {
        if (isLinked()) {
            compound.setLong("linkedDHD", linkedDHD.toLong());
            compound.setInteger("linkId", linkId);
        }

        return super.writeToNBT(compound);
    }

    @Override
    public void readFromNBT(@Nonnull NBTTagCompound compound) {
        if (compound.hasKey("linkedDHD")) this.linkedDHD = BlockPos.fromLong(compound.getLong("linkedDHD"));
        if (compound.hasKey("linkId")) this.linkId = compound.getInteger("linkId");

        super.readFromNBT(compound);
    }

    @Override
    public boolean prepare(ICommandSender sender, ICommand command) {
        setLinkedDHD(null, -1);

        return super.prepare(sender, command);
    }


    // ------------------------------------------------------------------------
    // Sounds

    @Override
    protected SoundPositionedEnum getPositionedSound(StargateSoundPositionedEnum soundEnum) {
        switch (soundEnum) {
            case GATE_RING_ROLL:
                return SoundPositionedEnum.MILKYWAY_RING_ROLL;
            case GATE_RING_ROLL_START:
                return SoundPositionedEnum.MILKYWAY_RING_ROLL_START;
        }

        return null;
    }

    @Override
    protected SoundEventEnum getSoundEvent(StargateSoundEventEnum soundEnum) {
        switch (soundEnum) {
            case OPEN:
                return SoundEventEnum.GATE_MILKYWAY_OPEN;
            case OPEN_NOX:
                return SoundEventEnum.GATE_NOX_OPEN;
            case CLOSE:
                return SoundEventEnum.GATE_MILKYWAY_CLOSE;
            case DIAL_FAILED:
                return stargateState.dialingComputer() ? SoundEventEnum.GATE_MILKYWAY_DIAL_FAILED_COMPUTER : SoundEventEnum.GATE_MILKYWAY_DIAL_FAILED;
            case INCOMING:
                return SoundEventEnum.GATE_MILKYWAY_INCOMING;
            case CHEVRON_OPEN:
                return SoundEventEnum.GATE_MILKYWAY_CHEVRON_OPEN;
            case CHEVRON_SHUT:
                return SoundEventEnum.GATE_MILKYWAY_CHEVRON_SHUT;
        }

        return null;
    }


    // ------------------------------------------------------------------------
    // Ticking and loading

    private BlockPos lastPos = BlockPos.ORIGIN;

    @Override
    protected boolean onGateMergeRequested() {
        if (stargateSize != JSGConfig.Stargate.stargateSize) {
            StargateMilkyWayMergeHelper.INSTANCE.convertToPattern(world, pos, facing, facingVertical, stargateSize, tauri.dev.jsg.config.JSGConfig.Stargate.stargateSize);
            stargateSize = tauri.dev.jsg.config.JSGConfig.Stargate.stargateSize;
        }

        return StargateMilkyWayMergeHelper.INSTANCE.checkBlocks(world, pos, facing, facingVertical);
    }

    @Override
    public void update() {
        super.update();
        if (!world.isRemote) {

            if ((toDialSymbols.size() > 0) && ((world.getTotalWorldTime() - lastSpinFinishedIn) > 5) && stargateState.idle()) {
                if (toDialSymbols.get(0) == SymbolMilkyWayEnum.BRB || canAddSymbolInternal(toDialSymbols.get(0))) {
                    if(isFastDialing || toDialSymbols.get(0) == SymbolMilkyWayEnum.BRB)
                        addSymbolToAddressDHD(toDialSymbols.get(0));
                    else
                        addSymbolToAddressManual(toDialSymbols.get(0), null);
                }
                if (toDialSymbols.size() > 0)
                    toDialSymbols.remove(0);
                markDirty();
            }

            if (!lastPos.equals(pos)) {
                lastPos = pos;

                updateLinkStatus();
                markDirty();
            }
        }
    }

    public static final EnumSet<BiomeOverlayEnum> SUPPORTED_OVERLAYS = EnumSet.of(BiomeOverlayEnum.NORMAL, BiomeOverlayEnum.FROST, BiomeOverlayEnum.MOSSY, BiomeOverlayEnum.AGED, BiomeOverlayEnum.SOOTY);

    @Override
    public EnumSet<BiomeOverlayEnum> getSupportedOverlays() {
        return SUPPORTED_OVERLAYS;
    }


    // ------------------------------------------------------------------------
    // Rendering

    @Override
    protected StargateMilkyWayRendererStateBuilder getRendererStateServer() {
        return (StargateMilkyWayRendererStateBuilder) new StargateMilkyWayRendererStateBuilder(super.getRendererStateServer()).setStargateSize(stargateSize);
    }

    @Override
    protected StargateAbstractRendererState createRendererStateClient() {
        return new StargateMilkyWayRendererState();
    }

    @Override
    public StargateMilkyWayRendererState getRendererStateClient() {
        return (StargateMilkyWayRendererState) super.getRendererStateClient();
    }


    // -----------------------------------------------------------------
    // States

    @Override
    public State createState(StateTypeEnum stateType) {

        return super.createState(stateType);

    }

    @Override
    @SideOnly(Side.CLIENT)
    public void setState(StateTypeEnum stateType, State state) {
        if (getRendererStateClient() != null) {
            if (stateType == StateTypeEnum.RENDERER_UPDATE) {
                StargateRendererActionState gateActionState = (StargateRendererActionState) state;

                switch (gateActionState.action) {
                    case CHEVRON_OPEN:
                        getRendererStateClient().openChevron(world.getTotalWorldTime());
                        break;

                    case CHEVRON_CLOSE:
                        getRendererStateClient().closeChevron(world.getTotalWorldTime());
                        break;
                    case OPEN_GATE:
                    default:
                        break;
                }
            }
        }

        super.setState(stateType, state);
    }


    // -----------------------------------------------------------------
    // Scheduled tasks

    @Override
    public void executeTask(EnumScheduledTask scheduledTask, NBTTagCompound customData) {
        boolean onlySpin = false;
        if (customData != null && customData.hasKey("onlySpin"))
            onlySpin = customData.getBoolean("onlySpin");
        switch (scheduledTask) {
            case STARGATE_ACTIVATE_CHEVRON:
                stargateState = EnumStargateState.IDLE;
                markDirty();

                if(!isNoxDialing)
                    playSoundEvent(StargateSoundEventEnum.CHEVRON_OPEN);
                sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_ACTIVATE, -1, isFinalActive);
                updateChevronLight(dialedAddress.size(), isFinalActive);
                //			JSGPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(pos, StateTypeEnum.RENDERER_UPDATE, new StargateRendererActionState(EnumGateAction.CHEVRON_ACTIVATE, -1, customData.getBoolean("final"))), targetPoint);
                break;

            case STARGATE_SPIN_FINISHED:
                if (!onlySpin)
                    addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_OPEN, 7));
                else if (stargateState.dialingComputer())
                    stargateState = EnumStargateState.IDLE;

                markDirty();

                break;

            case STARGATE_CHEVRON_OPEN:
                playSoundEvent(StargateSoundEventEnum.CHEVRON_OPEN);
                sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_OPEN, 0, false);

                if (stargateState.incoming() || stargateState.unstable() || stargateState.dialingDHD() || isIncoming) {
                    addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_OPEN_SECOND, 7));
                    return;
                }

                if (canAddSymbol(targetRingSymbol)) {
                    addSymbolToAddress(targetRingSymbol);

                    if (stargateWillLock(targetRingSymbol)) {
                        if (checkAddressAndEnergy(dialedAddress).ok()) {
                            addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_OPEN_SECOND, 13));
                        } else addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_FAIL, 70));
                    } else addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_OPEN_SECOND, 7));
                } else addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_FAIL, 70));

                break;

            case STARGATE_CHEVRON_OPEN_SECOND:
                playSoundEvent(StargateSoundEventEnum.CHEVRON_OPEN);
                addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_LIGHT_UP, 3));

                break;

            case STARGATE_CHEVRON_LIGHT_UP:
                if (stargateState.incoming() || stargateState.unstable() || stargateState.dialingDHD() || isIncoming) {
                    sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_ACTIVATE, 0, true);
                    addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_CLOSE, 10));
                    return;
                }


                if (stargateWillLock(targetRingSymbol))
                    sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_ACTIVATE, 0, true);
                else sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_ACTIVATE_BOTH, 0, false);

                updateChevronLight(dialedAddress.size(), isFinalActive);

                addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_CLOSE, 10));

                break;

            case STARGATE_CHEVRON_CLOSE:
                if (stargateState.incoming() || stargateState.dialingDHD() || isIncoming) {
                    playSoundEvent(StargateSoundEventEnum.CHEVRON_SHUT);
                    sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_CLOSE, 0, false);
                    if (stargateState.dialingDHD())
                        stargateState = EnumStargateState.IDLE;
                    markDirty();
                    return;
                }

                playSoundEvent(StargateSoundEventEnum.CHEVRON_SHUT);
                sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_CLOSE, 0, false);

                if (stargateWillLock(targetRingSymbol)) {
                    stargateState = EnumStargateState.IDLE;
                    sendSignal(ringSpinContext, "stargate_spin_chevron_engaged", new Object[]{dialedAddress.size(), true, targetRingSymbol.getEnglishName()});
                } else addTask(new ScheduledTask(EnumScheduledTask.STARGATE_CHEVRON_DIM, 10));

                break;

            case STARGATE_CHEVRON_DIM:
                sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_DIM, 0, false);
                stargateState = EnumStargateState.IDLE;

                sendSignal(ringSpinContext, "stargate_spin_chevron_engaged", new Object[]{dialedAddress.size(), false, targetRingSymbol.getEnglishName()});

                break;

            case STARGATE_CHEVRON_FAIL:
                sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CHEVRON_CLOSE, 0, false);
                dialingFailed(checkAddressAndEnergy(dialedAddress));

                break;

            default:
                break;
        }

        super.executeTask(scheduledTask, customData);
    }

    @Override
    public int getDefaultCapacitors() {
        return 3;
    }
}
