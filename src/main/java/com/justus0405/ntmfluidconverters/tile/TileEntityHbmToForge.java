package com.justus0405.ntmfluidconverters.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.hbm.inventory.fluid.FluidType;
import com.hbm.inventory.fluid.Fluids;
import com.hbm.inventory.fluid.tank.FluidTank;
import com.hbm.tileentity.TileEntityLoadedBase;
import com.hbm.tileentity.TilePort.PortDef;
import com.hbm.tileentity.TilePortShapes;
import com.hbm.tileentity.network.TileEntityPipeBaseNT;
import com.justus0405.ntmfluidconverters.FluidConverter;
import com.justus0405.ntmfluidconverters.ModConfig;

import api.hbm.fluidmk2.IFluidConnectorMK2;
import api.hbm.fluidmk2.IFluidStandardReceiverMK2;
import api.hbm.fluidmk2.IFluidStandardSenderMK2;

// Accepts fluid from HBM pipes and exposes it as a Forge IFluidHandler so Forge pipes / AE2 buses can drain it.
// Patched for HBM NTM X5808+ (port-based fluid networks, trySubscribe was removed).
public class TileEntityHbmToForge extends TileEntityLoadedBase implements IFluidStandardReceiverMK2, IFluidHandler {

    private final FluidTank hbmTank = new FluidTank(Fluids.NONE, ModConfig.hbmToForgeBufferSize);
    private PortDef[] cachedPorts;

    // IFluidUserMK2
    @Override
    public FluidTank[] getAllTanks() {
        return new FluidTank[] { hbmTank };
    }

    // IFluidStandardReceiverMK2
    @Override
    public FluidTank[] getReceivingTanks() {
        return new FluidTank[] { hbmTank };
    }

    // Accept any fluid type when the tank is empty, not just whatever is already stored.
    @Override
    public long getDemand(FluidType type, int pressure) {
        if (pressure != 0) return 0;
        if (hbmTank.getTankType() == type) {
            return hbmTank.getMaxFill() - hbmTank.getFill();
        }
        if (hbmTank.getTankType() == Fluids.NONE || hbmTank.getFill() == 0) {
            return hbmTank.getMaxFill();
        }
        return 0;
    }

    @Override
    public long transferFluid(FluidType type, int pressure, long amount) {
        if (pressure != 0) return amount;
        if (hbmTank.getTankType() != type) {
            if (hbmTank.getFill() > 0) return amount;
            hbmTank.setTankType(type);
        }
        int toAdd = (int) Math.min(amount, hbmTank.getMaxFill() - hbmTank.getFill());
        hbmTank.setFill(hbmTank.getFill() + toAdd);
        return amount - toAdd;
    }

    private PortDef[] getPorts() {
        if (cachedPorts == null) cachedPorts = TilePortShapes.around(xCoord, yCoord, zCoord);
        return cachedPorts;
    }

    // Tick
    @Override
    public void updateEntity() {
        if (worldObj.isRemote) return;

        // While empty, follow the fluid type of the neighbouring HBM pipe/machine so the port joins that network.
        if (hbmTank.getFill() <= 0) {
            FluidType wanted = detectNeighborType();
            if (wanted != hbmTank.getTankType()) hbmTank.setTankType(wanted);
        }

        setupFluidPorts(getPorts());
        updatePortFIFO();
    }

    private FluidType detectNeighborType() {
        FluidType current = hbmTank.getTankType();
        FluidType first = Fluids.NONE;

        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity te = worldObj.getTileEntity(
                xCoord + dir.offsetX,
                yCoord + dir.offsetY,
                zCoord + dir.offsetZ);
            FluidType found = Fluids.NONE;

            if (te instanceof TileEntityPipeBaseNT) {
                found = ((TileEntityPipeBaseNT) te).getType();
            } else if (te instanceof IFluidStandardSenderMK2) {
                FluidTank[] tanks = ((IFluidStandardSenderMK2) te).getSendingTanks();
                if (tanks != null) {
                    for (FluidTank t : tanks) {
                        if (t != null && t.getTankType() != Fluids.NONE && t.getFill() > 0) {
                            found = t.getTankType();
                            break;
                        }
                    }
                }
            }

            if (found == null || found == Fluids.NONE) continue;
            if (te instanceof IFluidConnectorMK2 && !((IFluidConnectorMK2) te).canConnect(found, dir.getOpposite())) continue;

            // Stick with the current type as long as some neighbour still offers it (avoids port churn).
            if (found == current) return current;
            if (first == Fluids.NONE) first = found;
        }
        return first;
    }

    // IFluidHandler drain only, Forge pipes can't push in.
    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        return 0;
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        if (resource == null || hbmTank.getFill() <= 0) return null;
        Fluid forgeFluid = FluidConverter.getForgeFluid(hbmTank.getTankType());
        if (forgeFluid == null || !forgeFluid.equals(resource.getFluid())) return null;
        int amount = Math.min(resource.amount, hbmTank.getFill());
        if (doDrain) hbmTank.setFill(hbmTank.getFill() - amount);
        return new FluidStack(forgeFluid, amount);
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        if (hbmTank.getFill() <= 0) return null;
        Fluid forgeFluid = FluidConverter.getForgeFluid(hbmTank.getTankType());
        if (forgeFluid == null) return null;
        int amount = Math.min(maxDrain, hbmTank.getFill());
        if (doDrain) hbmTank.setFill(hbmTank.getFill() - amount);
        return new FluidStack(forgeFluid, amount);
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        return false;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        if (hbmTank.getFill() <= 0) return false;
        Fluid forgeFluid = FluidConverter.getForgeFluid(hbmTank.getTankType());
        return forgeFluid != null && forgeFluid.equals(fluid);
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        net.minecraftforge.fluids.FluidTank forgeTank = new net.minecraftforge.fluids.FluidTank(hbmTank.getMaxFill());
        if (hbmTank.getFill() > 0 && hbmTank.getTankType() != Fluids.NONE) {
            Fluid forgeFluid = FluidConverter.getForgeFluid(hbmTank.getTankType());
            if (forgeFluid != null) {
                forgeTank.setFluid(new FluidStack(forgeFluid, hbmTank.getFill()));
            }
        }
        return new FluidTankInfo[] { forgeTank.getInfo() };
    }

    // NBT
    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        hbmTank.readFromNBT(nbt, "tank");
        // HBM NBT includes maxFill, but config takes precedence.
        hbmTank.changeTankSize(ModConfig.hbmToForgeBufferSize);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        hbmTank.writeToNBT(nbt, "tank");
    }
}
