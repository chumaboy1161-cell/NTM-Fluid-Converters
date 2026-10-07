package com.justus0405.ntmfluidconverters.tile;

import net.minecraft.nbt.NBTTagCompound;
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
import com.justus0405.ntmfluidconverters.FluidConverter;
import com.justus0405.ntmfluidconverters.ModConfig;

import api.hbm.fluidmk2.IFluidStandardSenderMK2;

// Accepts fluid from Forge pipes and pushes it into HBM networks.
// Patched for HBM NTM X5808+ (port-based fluid networks, tryProvide was removed).
public class TileEntityForgeToHbm extends TileEntityLoadedBase implements IFluidStandardSenderMK2, IFluidHandler {

    private final FluidTank hbmTank = new FluidTank(Fluids.NONE, ModConfig.forgeToHbmBufferSize);
    private PortDef[] cachedPorts;

    // IFluidUserMK2
    @Override
    public FluidTank[] getAllTanks() {
        return new FluidTank[] { hbmTank };
    }

    // IFluidStandardSenderMK2
    @Override
    public FluidTank[] getSendingTanks() {
        return new FluidTank[] { hbmTank };
    }

    private PortDef[] getPorts() {
        if (cachedPorts == null) cachedPorts = TilePortShapes.around(xCoord, yCoord, zCoord);
        return cachedPorts;
    }

    // Tick
    @Override
    public void updateEntity() {
        if (worldObj.isRemote) return;
        setupFluidPorts(getPorts());
        updatePortFIFO();
    }

    // IFluidHandler fill only, can't drain from Forge side.
    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        if (resource == null || resource.getFluid() == null || resource.amount <= 0) return 0;

        FluidType hbmType = FluidConverter.getHbmFluid(resource.getFluid());
        if (hbmType == Fluids.NONE) return 0;

        if (hbmTank.getTankType() != Fluids.NONE && hbmTank.getTankType() != hbmType && hbmTank.getFill() > 0) return 0;

        int space = hbmTank.getTankType() == hbmType ? hbmTank.getMaxFill() - hbmTank.getFill() : hbmTank.getMaxFill();
        int toFill = Math.min(resource.amount, space);

        if (doFill && toFill > 0) {
            // setTankType resets fill so only call it when switching fluid types.
            if (hbmTank.getTankType() != hbmType) {
                hbmTank.setTankType(hbmType);
            }
            hbmTank.setFill(hbmTank.getFill() + toFill);
        }
        return toFill;
    }

    // drain is HBM side only.
    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        return null;
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        return null;
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        FluidType hbmType = FluidConverter.getHbmFluid(fluid);
        if (hbmType == Fluids.NONE) return false;
        return hbmTank.getTankType() == Fluids.NONE || hbmTank.getTankType() == hbmType || hbmTank.getFill() <= 0;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        return false;
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
        hbmTank.changeTankSize(ModConfig.forgeToHbmBufferSize);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        hbmTank.writeToNBT(nbt, "tank");
    }
}
